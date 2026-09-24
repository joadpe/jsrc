package com.jsrc.app.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Discovers Maven and Gradle project structure using local files only. */
public final class ProjectModelDetector {

    private static final Pattern QUOTED_VALUE = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_PROJECT = Pattern.compile(
            "project\\s*\\(\\s*[\"']:(.*?)[\"']\\s*\\)");
    private static final Pattern GRADLE_TOOLCHAIN = Pattern.compile(
            "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern GRADLE_COMPATIBILITY = Pattern.compile(
            "(?:sourceCompatibility|targetCompatibility)\\s*=\\s*(?:JavaVersion\\.VERSION_)?([0-9_]+)");

    public ProjectModel detect(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return detectMaven(root);
        }
        if (gradleSettings(root) != null || gradleBuild(root) != null) {
            return detectGradle(root);
        }
        return conventionalFallback(root);
    }

    private ProjectModel detectMaven(Path root) {
        List<ProjectDiagnostic> diagnostics = new ArrayList<>();
        try {
            Element rootPom = parseXml(root.resolve("pom.xml"));
            String javaVersion = mavenJavaVersion(rootPom);
            List<String> modulePaths = directChildren(rootPom, "modules", "module");
            if (modulePaths.isEmpty()) {
                String name = valueOrDefault(directText(rootPom, "artifactId"), rootName(root));
                return new ProjectModel(root, BuildSystem.MAVEN, javaVersion,
                        List.of(mavenModule(root, root, name, Set.of(), rootPom, diagnostics)),
                        diagnostics);
            }

            List<MavenModuleData> moduleData = new ArrayList<>();
            for (String modulePath : modulePaths) {
                Path path = resolveWithinRoot(
                        root, root, modulePath, diagnostics, "Maven module " + modulePath);
                if (path == null) {
                    continue;
                }
                try {
                    Element pom = parseXml(path.resolve("pom.xml"));
                    moduleData.add(new MavenModuleData(
                            valueOrDefault(
                                    directText(pom, "artifactId"), path.getFileName().toString()),
                            path,
                            dependencyArtifacts(pom),
                            pom));
                } catch (IOException | ParserConfigurationException | SAXException exception) {
                    diagnostics.add(new ProjectDiagnostic(
                            "BUILD_MODEL_PARTIAL",
                            "Could not interpret Maven module " + modulePath + ": "
                                    + exception.getMessage()));
                }
            }
            Set<String> moduleNames = moduleData.stream()
                    .map(MavenModuleData::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<ProjectModule> modules = new ArrayList<>();
            String rootModuleName = valueOrDefault(directText(rootPom, "artifactId"), rootName(root));
            ProjectModule rootModule = mavenModule(
                    root, root, rootModuleName, intersection(dependencyArtifacts(rootPom), moduleNames),
                    rootPom, diagnostics);
            if (hasExistingSources(rootModule)) {
                modules.add(rootModule);
            }
            moduleData.stream()
                    .map(data -> mavenModule(
                            root,
                            data.path(),
                            data.name(),
                            intersection(data.dependencies(), moduleNames),
                            data.pom(),
                            diagnostics))
                    .forEach(modules::add);
            return new ProjectModel(root, BuildSystem.MAVEN, javaVersion, modules, diagnostics);
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL", "Could not fully interpret pom.xml: " + exception.getMessage()));
            return fallback(root, BuildSystem.MAVEN, diagnostics);
        }
    }

    private ProjectModel detectGradle(Path root) {
        List<ProjectDiagnostic> diagnostics = new ArrayList<>();
        try {
            Path settings = gradleSettings(root);
            Path rootBuild = gradleBuild(root);
            String settingsText = settings == null ? "" : Files.readString(settings);
            String buildText = rootBuild == null ? "" : Files.readString(rootBuild);
            List<String> modulePaths = gradleModules(settingsText);
            String javaVersion = gradleJavaVersion(buildText);
            if (modulePaths.isEmpty()) {
                return new ProjectModel(root, BuildSystem.GRADLE, javaVersion,
                        List.of(gradleModule(
                                root, root, rootName(root), Set.of(), buildText, diagnostics)),
                        diagnostics);
            }

            List<GradleModuleData> moduleData = new ArrayList<>();
            for (String modulePath : modulePaths) {
                Path path = resolveWithinRoot(
                        root,
                        root,
                        modulePath.replace(':', '/'),
                        diagnostics,
                        "Gradle module " + modulePath);
                if (path == null) {
                    continue;
                }
                try {
                    Path buildFile = gradleBuild(path);
                    String moduleBuild = buildFile == null ? "" : Files.readString(buildFile);
                    moduleData.add(new GradleModuleData(
                            gradleModuleName(modulePath), path, moduleBuild));
                } catch (IOException exception) {
                    diagnostics.add(new ProjectDiagnostic(
                            "BUILD_MODEL_PARTIAL",
                            "Could not interpret Gradle module " + modulePath + ": "
                                    + exception.getMessage()));
                }
            }
            Set<String> moduleNames = moduleData.stream()
                    .map(GradleModuleData::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<ProjectModule> modules = new ArrayList<>();
            ProjectModule rootModule = gradleModule(
                    root,
                    root,
                    rootName(root),
                    intersection(gradleDependencies(buildText), moduleNames),
                    buildText,
                    diagnostics);
            if (hasExistingSources(rootModule)) {
                modules.add(rootModule);
            }
            for (GradleModuleData data : moduleData) {
                modules.add(gradleModule(
                        root,
                        data.path(),
                        data.name(),
                        intersection(gradleDependencies(data.buildFile()), moduleNames),
                        data.buildFile(),
                        diagnostics));
            }
            return new ProjectModel(root, BuildSystem.GRADLE, javaVersion, modules, diagnostics);
        } catch (IOException exception) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL", "Could not fully interpret Gradle files: " + exception.getMessage()));
            return fallback(root, BuildSystem.GRADLE, diagnostics);
        }
    }

    private ProjectModel conventionalFallback(Path root) {
        return fallback(root, BuildSystem.UNKNOWN, List.of(new ProjectDiagnostic(
                "BUILD_MODEL_FALLBACK", "No supported Maven or Gradle build metadata found")));
    }

    private ProjectModel fallback(
            Path root, BuildSystem buildSystem, List<ProjectDiagnostic> diagnostics) {
        return new ProjectModel(root, buildSystem, "unknown",
                List.of(conventionalModule(root, rootName(root), Set.of())), diagnostics);
    }

    private ProjectModule mavenModule(
            Path projectRoot,
            Path path,
            String name,
            Set<String> dependencies,
            Element pom,
            List<ProjectDiagnostic> diagnostics) {
        Element build = directElement(pom, "build");
        String sourceDirectory = build == null ? null : directText(build, "sourceDirectory");
        String testSourceDirectory = build == null ? null : directText(build, "testSourceDirectory");
        return module(
                path,
                name,
                dependencies,
                sourceDirectory == null
                        ? List.of(path.resolve("src/main/java"))
                        : confinedRoot(projectRoot, path, sourceDirectory, diagnostics,
                                "Maven main source root"),
                testSourceDirectory == null
                        ? List.of(path.resolve("src/test/java"), path.resolve("src/testFixtures/java"))
                        : confinedRoot(projectRoot, path, testSourceDirectory, diagnostics,
                                "Maven test source root"));
    }

    private ProjectModule gradleModule(
            Path projectRoot,
            Path path,
            String name,
            Set<String> dependencies,
            String content,
            List<ProjectDiagnostic> diagnostics) {
        if (content.contains("sourceSets") && !containsSimpleSourceDirectory(content)) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    "Dynamic Gradle source-set configuration in " + path));
        }
        List<Path> mainRoots = gradleSourceRoots(
                        content, "main", projectRoot, path, diagnostics)
                .orElse(List.of(path.resolve("src/main/java")));
        List<Path> testRoots = gradleSourceRoots(
                        content, "test", projectRoot, path, diagnostics)
                .orElse(List.of(path.resolve("src/test/java"), path.resolve("src/testFixtures/java")));
        return module(path, name, dependencies, mainRoots, testRoots);
    }

    private ProjectModule conventionalModule(Path path, String name, Set<String> dependencies) {
        return module(
                path,
                name,
                dependencies,
                List.of(path.resolve("src/main/java")),
                List.of(path.resolve("src/test/java"), path.resolve("src/testFixtures/java")));
    }

    private ProjectModule module(
            Path path,
            String name,
            Set<String> dependencies,
            List<Path> mainRoots,
            List<Path> testRoots) {
        return new ProjectModule(
                name,
                path,
                mainRoots,
                testRoots,
                List.of(
                        path.resolve("target/generated-sources/annotations"),
                        path.resolve("build/generated/sources/annotationProcessor/java/main")),
                List.of(path.resolve("target"), path.resolve("build"), path.resolve(".gradle")),
                List.copyOf(dependencies));
    }

    private static Element parseXml(Path pom)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(pom)) {
            return factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    private static String mavenJavaVersion(Element project) {
        for (String property : List.of("maven.compiler.release", "maven.compiler.source", "java.version")) {
            NodeList values = project.getElementsByTagName(property);
            if (values.getLength() > 0 && !values.item(0).getTextContent().isBlank()) {
                return normalizeJavaVersion(values.item(0).getTextContent().trim());
            }
        }
        return "unknown";
    }

    private static List<String> directChildren(
            Element parent, String containerName, String childName) {
        List<String> values = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && containerName.equals(element.getTagName())) {
                NodeList nested = element.getChildNodes();
                for (int j = 0; j < nested.getLength(); j++) {
                    Node item = nested.item(j);
                    if (item instanceof Element value && childName.equals(value.getTagName())) {
                        values.add(value.getTextContent().trim());
                    }
                }
            }
        }
        return values;
    }

    private static String directText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && name.equals(element.getTagName())) {
                return element.getTextContent().trim();
            }
        }
        return null;
    }

    private static Element directElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static Set<String> dependencyArtifacts(Element project) {
        Set<String> dependencies = new LinkedHashSet<>();
        NodeList nodes = project.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element dependency) {
                String artifact = directText(dependency, "artifactId");
                if (artifact != null) {
                    dependencies.add(artifact);
                }
            }
        }
        return dependencies;
    }

    private static List<String> gradleModules(String settings) {
        Set<String> modules = new LinkedHashSet<>();
        for (String line : settings.lines().toList()) {
            if (!line.stripLeading().startsWith("include")) {
                continue;
            }
            Matcher matcher = QUOTED_VALUE.matcher(line);
            while (matcher.find()) {
                String module = matcher.group(1).replaceFirst("^:", "");
                if (!module.isBlank()) {
                    modules.add(module);
                }
            }
        }
        return List.copyOf(modules);
    }

    private static Set<String> gradleDependencies(String buildFile) {
        Set<String> dependencies = new LinkedHashSet<>();
        Matcher matcher = GRADLE_PROJECT.matcher(buildFile);
        while (matcher.find()) {
            dependencies.add(gradleModuleName(matcher.group(1)));
        }
        return dependencies;
    }

    private static String gradleJavaVersion(String buildFile) {
        Matcher toolchain = GRADLE_TOOLCHAIN.matcher(buildFile);
        if (toolchain.find()) {
            return toolchain.group(1);
        }
        Matcher compatibility = GRADLE_COMPATIBILITY.matcher(buildFile);
        if (compatibility.find()) {
            return normalizeJavaVersion(compatibility.group(1).replace('_', '.'));
        }
        return "unknown";
    }

    private static java.util.Optional<List<Path>> gradleSourceRoots(
            String buildFile,
            String sourceSet,
            Path projectRoot,
            Path modulePath,
            List<ProjectDiagnostic> diagnostics) {
        Pattern pattern = Pattern.compile(
                sourceSet + "\\.java\\.srcDirs\\s*(?:=\\s*)?(?:\\(([^)]*)\\)|\\[([^]]*)\\])");
        Matcher sourceRoots = pattern.matcher(buildFile);
        if (!sourceRoots.find()) {
            return java.util.Optional.empty();
        }
        String values = sourceRoots.group(1) != null
                ? sourceRoots.group(1)
                : sourceRoots.group(2);
        List<Path> paths = new ArrayList<>();
        Matcher quoted = QUOTED_VALUE.matcher(values);
        while (quoted.find()) {
            Path path = resolveWithinRoot(
                    projectRoot,
                    modulePath,
                    quoted.group(1),
                    diagnostics,
                    "Gradle " + sourceSet + " source root");
            if (path != null) {
                paths.add(path);
            }
        }
        return java.util.Optional.of(List.copyOf(paths));
    }

    private static List<Path> confinedRoot(
            Path projectRoot,
            Path modulePath,
            String declaredPath,
            List<ProjectDiagnostic> diagnostics,
            String description) {
        Path path = resolveWithinRoot(
                projectRoot, modulePath, declaredPath, diagnostics, description);
        return path == null ? List.of() : List.of(path);
    }

    private static Path resolveWithinRoot(
            Path projectRoot,
            Path base,
            String declaredPath,
            List<ProjectDiagnostic> diagnostics,
            String description) {
        try {
            Path path = base.resolve(declaredPath).toAbsolutePath().normalize();
            if (path.startsWith(projectRoot)) {
                return path;
            }
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    description + " escapes project root: " + declaredPath));
        } catch (java.nio.file.InvalidPathException | SecurityException exception) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    description + " is invalid: " + declaredPath));
        }
        return null;
    }

    private static boolean hasExistingSources(ProjectModule module) {
        return java.util.stream.Stream.concat(
                        module.mainSourceRoots().stream(), module.testSourceRoots().stream())
                .anyMatch(Files::isDirectory);
    }

    private static String normalizeJavaVersion(String version) {
        return version.startsWith("1.") ? version.substring(2) : version;
    }

    private static Path gradleSettings(Path root) {
        return firstRegularFile(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"));
    }

    private static Path gradleBuild(Path root) {
        return firstRegularFile(root.resolve("build.gradle.kts"), root.resolve("build.gradle"));
    }

    private static Path firstRegularFile(Path first, Path second) {
        if (Files.isRegularFile(first)) return first;
        if (Files.isRegularFile(second)) return second;
        return null;
    }

    private static Set<String> intersection(Set<String> values, Set<String> allowed) {
        Set<String> result = new LinkedHashSet<>(values);
        result.retainAll(allowed);
        return result;
    }

    private static String gradleModuleName(String path) {
        int separator = path.lastIndexOf(':');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private static String rootName(Path root) {
        return root.getFileName() == null ? "project" : root.getFileName().toString();
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean containsSimpleSourceDirectory(String buildFile) {
        return buildFile.contains("srcDirs") || buildFile.contains("srcDir");
    }

    private record MavenModuleData(
            String name, Path path, Set<String> dependencies, Element pom) {}

    private record GradleModuleData(String name, Path path, String buildFile) {}
}
