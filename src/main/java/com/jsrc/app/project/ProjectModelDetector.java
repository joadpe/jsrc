package com.jsrc.app.project;

import com.jsrc.app.util.SourceText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Discovers Maven and Gradle project structure using local files only. */
public final class ProjectModelDetector {

    private static final Pattern QUOTED_VALUE = Pattern.compile("[\"']([^\"']+)[\"']");
    /** The source sets whose sources jsrc looks for at their conventional roots. */
    private static final Set<String> CONVENTIONAL_SOURCE_SETS = Set.of("main", "test");
    private static final Pattern GRADLE_SOURCE_SETS = Pattern.compile("\\bsourceSets\\b");
    /** A source-set container call that adds or removes source sets. */
    private static final Pattern GRADLE_SOURCE_SET_CHANGE = Pattern.compile(
            "create|register|maybeCreate|creating|registering|add|addAll|remove|removeAll"
                    + "|removeIf|retainAll|clear");
    /** A source-set container call that applies to every source set, or to those it selects. */
    private static final Pattern GRADLE_EVERY_SOURCE_SET = Pattern.compile(
            "all|configureEach|each|forEach|matching|withType|whenObjectAdded");
    /** A source-set container call that looks one source set up by name. */
    private static final Pattern GRADLE_SOURCE_SET_LOOKUP = Pattern.compile(
            "named|getByName|findByName|getAt");
    /**
     * The name in such a lookup, as in named("main") or getByName<SourceSet>('test'). The white
     * space around the type argument is possessive, so that a long run of it is not retried at
     * every split between the two.
     */
    private static final Pattern GRADLE_SOURCE_SET_ARGUMENT = Pattern.compile(
            "\\s*+(?:<[^<>]*>)?\\s*+\\(\\s*[\"'](\\w+)[\"']\\s*(?:,[^()]*)?\\)");
    /** A source set read from the container, as in .main or ["main"], or a container call. */
    private static final Pattern GRADLE_SOURCE_SET_ACCESS = Pattern.compile(
            "\\s*(?:\\??\\.\\s*(\\w+)|\\[\\s*[\"'](\\w+)[\"']\\s*])");
    /** One entry of a sourceSets block, which starts with a source set or a container call. */
    private static final Pattern GRADLE_SOURCE_SET_ENTRY = Pattern.compile(
            "(?:(\\w+)|[\"'](\\w+)[\"'])");
    /** A statement that only Gradle evaluates, as in if (...) { main { ... } }. */
    private static final Pattern GRADLE_STATEMENT_KEYWORD = Pattern.compile(
            "if|else|for|while|switch|when|try|def|val|var|return");
    /** A source set created through a Kotlin delegate, as in val intTest by creating. */
    private static final Pattern GRADLE_DELEGATED_SOURCE_SET = Pattern.compile(
            "\\bby\\s+(?:creating|registering)\\b");
    private static final Pattern GRADLE_PROVIDER_GET = Pattern.compile(
            "\\s*\\??\\.\\s*get\\s*\\(\\s*\\)");
    /**
     * What opens a closure that configures the source set just named. The white space around
     * the optional parenthesis is possessive, as in GRADLE_SOURCE_SET_ARGUMENT.
     */
    private static final Pattern GRADLE_SOURCE_SET_CLOSURE = Pattern.compile(
            "\\s*(?:\\??\\.\\s*(?:configure|configureEach|all|with|apply|run)\\s*+\\(?\\s*+)?\\{");
    private static final String JAVA_SOURCE_CHANGE = "(?:java|getJava\\s*\\(\\s*\\))\\s*"
            + "(?:\\{|[?*]?\\.\\s*(?:srcDirs?|setSrcDirs|source|include|exclude|includes"
            + "|excludes|setIncludes|setExcludes|filter|getFilter)\\b)";
    /**
     * A change to the java directory set of a source set, as in java.exclude '...' or a
     * java { } block. Its other members, such as output, resources or compileClasspath, do
     * not change which files are Java sources.
     */
    private static final Pattern GRADLE_JAVA_SOURCE_CHANGE =
            Pattern.compile("\\b" + JAVA_SOURCE_CHANGE);
    /** The same change as a member of the source set just named, as in .java.exclude. */
    private static final Pattern GRADLE_JAVA_MEMBER_CHANGE =
            Pattern.compile("\\s*[?*]?\\.\\s*" + JAVA_SOURCE_CHANGE);
    private static final Pattern GRADLE_PROJECT = Pattern.compile(
            "project\\s*\\(\\s*[\"']:(.*?)[\"']\\s*\\)");
    /** Gradle API calls whose names start with include and that do not add a project. */
    private static final Set<String> GRADLE_NON_PROJECT_INCLUDES = Set.of(
            "includeBuild", "includeFlat", "includeGroup", "includeGroupByRegex",
            "includeGroupAndSubgroups", "includeModule", "includeModuleByRegex",
            "includeVersion", "includeVersionByRegex");
    /**
     * A root-build block for one project, or a configure(...) call that may open one. Only
     * spaces and tabs may come before it on its line: a {@code \s*} after the line start would
     * also cross blank lines, and retrying it from each of them takes quadratic time.
     */
    private static final Pattern GRADLE_TARGETED_BLOCK = Pattern.compile(
            "(?m)^[^\\S\\r\\n]*(?:(?<project>project\\s*\\(\\s*[\"'][^\"']*[\"']\\s*\\))\\s*\\{"
                    + "|configure\\s*\\()");
    private static final Pattern GRADLE_PROJECT_PATH = Pattern.compile(
            "project\\s*\\(\\s*[\"']:?([^\"']*)[\"']\\s*\\)");
    /** A configure(...) selector of tasks, not projects, such as [compileJava, compileTestJava]. */
    private static final Pattern GRADLE_TASK_SELECTOR = Pattern.compile(
            "(?s)(?!.*\\b(?:subprojects|allprojects|rootProject|projects?|childProjects)\\b)"
                    + ".*\\b(?:tasks|compile\\w*Java|JavaCompile)\\b");
    /** A selector that may add the root project to subprojects. */
    private static final Pattern GRADLE_ROOT_SELECTOR = Pattern.compile(
            "\\brootProject\\b|\\ballprojects\\b|\\+|\\bplus\\b");
    /** The compile task of a source set other than main, or of another language. */
    private static final String OTHER_COMPILE_TASK =
            "compile(?!Java\\b)\\w*(?:Java|Groovy|Scala|Kotlin)";
    private static final String OTHER_COMPILE_TASK_NAME = "[\"']" + OTHER_COMPILE_TASK + "[\"']";
    /**
     * A condition that holds for that task alone, as in if (name == 'compileTestJava'), or a
     * Kotlin when branch for it. if (name != 'compileTestJava') holds for compileJava too.
     */
    private static final String OTHER_COMPILE_TASK_CONDITION =
            "(?:else\\s+)?if\\s*\\(\\s*(?:(?:[\\w.]+\\.)?name\\s*==\\s*" + OTHER_COMPILE_TASK_NAME
                    + "|" + OTHER_COMPILE_TASK_NAME + "\\s*==\\s*(?:[\\w.]+\\.)?name"
                    + "|(?:[\\w.]+\\.)?name\\s*\\.\\s*equals\\s*\\(\\s*" + OTHER_COMPILE_TASK_NAME
                    + "\\s*\\))\\s*\\)"
                    + "|" + OTHER_COMPILE_TASK_NAME + "\\s*->";
    /**
     * The header of a block that configures something other than the main compilation: IDE
     * metadata; the compile task of another source set or language, by name, through a
     * condition on its name or in configure(...); a task that the block registers; or the Java
     * tool of tests or Javadoc. Only the block's target counts, so a condition such as
     * if (name != 'compileTestJava') { ... } does not hide what it guards. A dotted name is
     * checked whole before it is searched, so that a long header takes linear time, and the
     * repeated groups are possessive, so that they do not recurse once per repetition. The
     * white space next to an optional token is possessive too, so that a long run of it is not
     * retried at every split between the quantifiers that could take it.
     */
    private static final Pattern GRADLE_OTHER_TARGET = Pattern.compile(
            "(?=\\w++(?:\\s*+\\.\\s*+\\w++)*+$).*?\\b(?:eclipse|idea|jdt|" + OTHER_COMPILE_TASK
                    + ")\\b.*"
                    + "|(?:[\\w.]*\\.)?(?:named|getByName)\\s*+(?:<[\\w.]+>)?\\s*+\\(\\s*"
                    + OTHER_COMPILE_TASK_NAME + "(?:\\s*,\\s*[\\w.:]+)?\\s*\\)(?:\\s*\\.\\s*\\w+)*+"
                    + "|configure\\s*\\(\\s*+\\[?\\s*+(?:tasks\\s*\\.\\s*)?" + OTHER_COMPILE_TASK
                    + "(?:\\s*,\\s*(?:tasks\\s*\\.\\s*)?" + OTHER_COMPILE_TASK
                    + ")*+\\s*+,?\\s*+\\]?\\s*+\\)"
                    + "|" + OTHER_COMPILE_TASK_CONDITION
                    + "|(?:val\\s+\\w+\\s+by\\s+)?(?:[\\w.]*\\.)?"
                    + "(?:register|create|registering|creating)\\b[^{};]*"
                    + "|task\\s+\\w+\\s*(?:\\([^{};]*\\))?|task\\s*\\([^{};]*\\)"
                    + "|.*\\b(?:launcherFor|javadocToolFor)");
    /** The same targets as the receiver of a statement, as in eclipse.jdt.sourceCompatibility. */
    private static final Pattern GRADLE_OTHER_RECEIVER = Pattern.compile(
            "\\b(?:eclipse|idea|jdt)\\b|\\b" + OTHER_COMPILE_TASK + "\\b");
    /** What may precede such a statement on its own, as in "compileTestJava" -> ... */
    private static final Pattern GRADLE_OTHER_CONDITION = Pattern.compile(
            "\\s*(?:" + OTHER_COMPILE_TASK_CONDITION + ")\\s*");
    /** The level keywords, strongest first, as gradleJavaVersion checks them. */
    private static final List<String> GRADLE_LEVEL_KEYWORDS =
            List.of("options.release", "sourceCompatibility", "languageVersion");
    private static final Pattern GRADLE_LEVEL_KEYWORD = Pattern.compile(
            "\\boptions\\.release\\b|\\bsourceCompatibility\\b|\\blanguageVersion\\b");
    /**
     * What follows a keyword that sets it: =, a property method such as set(...), or Groovy's
     * method form, with or without parentheses.
     */
    private static final Pattern GRADLE_LEVEL_WRITE = Pattern.compile(
            "[ \\t]*(?:=(?!=)|\\(|\\.\\s*(?:set|value|convention)\\s*\\()"
                    + "|[ \\t]+(?!(?:as|in|instanceof)\\b)[\\w'\"]");
    /** A Kotlin language version such as "1.9" or KotlinVersion.KOTLIN_2_0, not a toolchain. */
    private static final Pattern KOTLIN_LANGUAGE_VERSION = Pattern.compile(
            "languageVersion\\s*(?:=|\\.\\s*(?:set|value|convention)\\s*\\()\\s*"
                    + "(?:[\"']|KotlinVersion\\b)");
    private static final Pattern GRADLE_BLOCK_OPENING = Pattern.compile("\\s*\\{");
    /** A toolchain set to a literal right after its keyword, not a computed one. */
    private static final Pattern GRADLE_TOOLCHAIN = Pattern.compile(
            "languageVersion\\s*(?:=|\\.\\s*(?:set|value|convention)\\s*\\()\\s*"
                    + "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)");
    /** A whole number; the lookahead keeps 1.8 from reading as Java 1. */
    private static final Pattern GRADLE_RELEASE = Pattern.compile(
            "options\\.release(?:\\.set\\s*\\(\\s*|\\s*=\\s*)(\\d+)(?![\\w.])\\s*\\)?");
    /**
     * A version number or string such as 8, 1.8, '1.8' or "11.0.2", a
     * {@code JavaVersion.VERSION_} constant, or {@code JavaVersion.toVersion} of either.
     * The lookahead takes the number whole: when the token goes on, as in 1_8 or 11L, the
     * level is unknown instead of read from a prefix. The possessive group matches a long
     * dotted number without recursing once per part.
     */
    private static final Pattern GRADLE_COMPATIBILITY = Pattern.compile(
            "sourceCompatibility\\s*=\\s*(?:JavaVersion\\.toVersion\\s*\\(\\s*)?"
                    + "(?:JavaVersion\\.VERSION_(?<constant>(?:1_)?\\d+)"
                    + "|(?<quote>[\"']?)(?<number>\\d+(?:\\.\\d+)*+)(?![\\w.])\\k<quote>)");
    /** An allprojects or subprojects block; see GRADLE_TARGETED_BLOCK for the line start. */
    private static final Pattern GRADLE_SHARED_BLOCK = Pattern.compile(
            "(?m)^[^\\S\\r\\n]*(allprojects|subprojects)\\s*\\{");
    private static final Pattern XML_DECLARED_ENCODING = Pattern.compile(
            "<\\?xml\\s[^>]*?\\sencoding\\s*=\\s*[\"']([^\"']*)[\"']");
    /**
     * Largest pom.xml read whole to choose its decoding; real ones take kilobytes. Anything
     * larger, such as a device, is streamed to the parser as is.
     */
    private static final int MAX_BUFFERED_XML_BYTES = 16 * 1024 * 1024;

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
                        List.of(mavenModule(root, root, name, Set.of(), rootPom, diagnostics,
                                javaVersion)),
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
                    rootPom, diagnostics, javaVersion);
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
                            diagnostics,
                            mavenEffectiveVersion(root, data.path(), rootPom, data.pom())))
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
            String settingsText = settings == null ? "" : readGradleScript(settings);
            String buildText = rootBuild == null ? "" : readGradleScript(rootBuild);
            List<String> modulePaths = gradleModules(settingsText);
            GradleScopes scopes = gradleScopes(buildText);
            String javaVersion = scopes.rootLevel();
            if (modulePaths.isEmpty()) {
                return new ProjectModel(root, BuildSystem.GRADLE, javaVersion,
                        List.of(gradleModule(
                                root, root, rootName(root), Set.of(), buildText,
                                diagnostics, javaVersion)),
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
                    String moduleBuild = buildFile == null ? "" : readGradleScript(buildFile);
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
                    diagnostics,
                    javaVersion);
            if (hasExistingSources(rootModule)) {
                modules.add(rootModule);
            }
            java.util.Map<Path, String> projectBlocks = scopes.projectBlocksByDirectory(root);
            for (GradleModuleData data : moduleData) {
                modules.add(gradleModule(
                        root,
                        data.path(),
                        data.name(),
                        intersection(gradleDependencies(data.buildFile()), moduleNames),
                        data.buildFile(),
                        diagnostics,
                        scopes.moduleLevel(projectBlocks.get(data.path()), data.buildFile())));
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
            List<ProjectDiagnostic> diagnostics,
            String javaVersion) {
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
                                "Maven test source root"),
                javaVersion);
    }

    private static String mavenEffectiveVersion(
            Path root, Path modulePath, Element rootPom, Element modulePom) {
        Element parent = directElement(modulePom, "parent");
        if (parent == null) {
            return mavenJavaVersion(modulePom);
        }
        String relativePath = directText(parent, "relativePath");
        if (relativePath != null && relativePath.isEmpty()) {
            return mavenJavaVersion(modulePom);
        }
        Path parentPom = modulePath.resolve(relativePath == null
                        ? "../pom.xml" : relativePath)
                .toAbsolutePath().normalize();
        if (Files.isDirectory(parentPom)) {
            parentPom = parentPom.resolve("pom.xml");
        }
        if (!parentPom.equals(root.resolve("pom.xml").toAbsolutePath().normalize())
                || !java.util.Objects.equals(directText(parent, "groupId"),
                        directText(rootPom, "groupId"))
                || !java.util.Objects.equals(directText(parent, "artifactId"),
                        directText(rootPom, "artifactId"))
                || !java.util.Objects.equals(directText(parent, "version"),
                        directText(rootPom, "version"))) {
            return mavenJavaVersion(modulePom);
        }
        Element childProperties = directElement(modulePom, "properties");
        Element parentProperties = directElement(rootPom, "properties");
        for (String setting : List.of("release", "source")) {
            String raw = compilerPluginSettingIn(
                    mavenPlugins(modulePom), setting);
            if (raw == null) {
                raw = compilerPluginSettingIn(mavenPlugins(rootPom), setting);
            }
            if (raw == null) {
                raw = compilerPluginSettingIn(
                        mavenManagedPlugins(modulePom), setting);
            }
            if (raw == null) {
                raw = compilerPluginSettingIn(
                        mavenManagedPlugins(rootPom), setting);
            }
            if (raw == null) {
                raw = childProperties == null ? null
                        : directText(childProperties, "maven.compiler." + setting);
            }
            if (raw == null) {
                raw = parentProperties == null ? null
                        : directText(parentProperties, "maven.compiler." + setting);
            }
            if (raw != null) {
                return resolveMavenVersion(raw, childProperties, parentProperties);
            }
        }
        return "unknown";
    }

    private ProjectModule gradleModule(
            Path projectRoot,
            Path path,
            String name,
            Set<String> dependencies,
            String content,
            List<ProjectDiagnostic> diagnostics,
            String javaVersion) {
        String activeBuild = withoutComments(content);
        if (configuresJavaSourceSets(activeBuild) && !containsSimpleSourceDirectory(activeBuild)) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    "Dynamic Gradle source-set configuration in " + path));
        }
        List<Path> mainRoots = gradleSourceRoots(
                        activeBuild, "main", projectRoot, path, diagnostics)
                .orElse(List.of(path.resolve("src/main/java")));
        List<Path> testRoots = gradleSourceRoots(
                        activeBuild, "test", projectRoot, path, diagnostics)
                .orElse(List.of(path.resolve("src/test/java"), path.resolve("src/testFixtures/java")));
        return module(path, name, dependencies, mainRoots, testRoots, javaVersion);
    }

    private ProjectModule conventionalModule(Path path, String name, Set<String> dependencies) {
        return module(
                path,
                name,
                dependencies,
                List.of(path.resolve("src/main/java")),
                List.of(path.resolve("src/test/java"), path.resolve("src/testFixtures/java")),
                "unknown");
    }

    private ProjectModule module(
            Path path,
            String name,
            Set<String> dependencies,
            List<Path> mainRoots,
            List<Path> testRoots,
            String javaVersion) {
        return new ProjectModule(
                name,
                path,
                mainRoots,
                testRoots,
                List.of(
                        path.resolve("target/generated-sources/annotations"),
                        path.resolve("build/generated/sources/annotationProcessor/java/main")),
                List.of(path.resolve("target"), path.resolve("build"), path.resolve(".gradle")),
                List.copyOf(dependencies),
                javaVersion);
    }

    private static Element parseXml(Path pom)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Parse errors become diagnostics; without a handler the parser also prints them to stderr.
        builder.setErrorHandler(new DefaultHandler());
        try (InputStream input = Files.newInputStream(pom)) {
            byte[] content = input.readNBytes(MAX_BUFFERED_XML_BYTES + 1);
            InputSource source = content.length > MAX_BUFFERED_XML_BYTES
                    ? new InputSource(new SequenceInputStream(
                            new ByteArrayInputStream(content), input))
                    : xmlSource(content);
            return builder.parse(source).getDocumentElement();
        }
    }

    /**
     * Legacy Windows projects save pom.xml files that the XML parser cannot read as written.
     * Some declare windows-1252, a charset that native images leave out. Others are saved as
     * Windows-1252 while their XML declaration names UTF-8 or no encoding, which means UTF-8;
     * Maven reads them, but the parser rejects their bytes. Both are decoded with SourceText
     * instead, on the JVM too so that both read them alike, and parsed as text; a UTF-8 byte
     * order mark before the declaration is dropped, as the parser does. Valid UTF-8 and every
     * other declared or signaled encoding are left to the parser.
     */
    private static InputSource xmlSource(byte[] content) {
        byte[] body = withoutUtf8ByteOrderMark(content);
        String windows1252 = SourceText.decodeWindows1252(body);
        Matcher encoding = XML_DECLARED_ENCODING.matcher(windows1252);
        if (encoding.lookingAt() && isWindows1252(encoding.group(1))) {
            return new InputSource(new StringReader(windows1252));
        }
        if (!SourceText.isUtf8(content)) {
            String text = SourceText.decode(body);
            if (declaresUtf8(text)) {
                return new InputSource(new StringReader(text));
            }
        }
        return new InputSource(new ByteArrayInputStream(content));
    }

    /**
     * Whether XML decoded one char per byte starts like ASCII, unlike UTF-16, UTF-32 and EBCDIC,
     * and has no XML declaration or one that names UTF-8 or no encoding.
     */
    private static boolean declaresUtf8(String text) {
        if (text.isEmpty() || "<\t\n\r ".indexOf(text.charAt(0)) < 0
                || text.chars().limit(4).anyMatch(character -> character == 0)) {
            return false;
        }
        Matcher encoding = XML_DECLARED_ENCODING.matcher(text);
        return !encoding.lookingAt() || encoding.group(1).equalsIgnoreCase("UTF-8");
    }

    /** Whether the JDK resolves an encoding name to windows-1252. */
    private static boolean isWindows1252(String encoding) {
        return switch (encoding.toLowerCase(Locale.ROOT)) {
            case "windows-1252", "cp1252", "cp5348", "ibm-1252", "ibm1252" -> true;
            default -> false;
        };
    }

    private static String mavenJavaVersion(Element project) {
        Element properties = directElement(project, "properties");
        for (String setting : List.of("release", "source")) {
            String raw = mavenSetting(project, setting);
            if (raw != null) {
                return resolveMavenVersion(raw, properties, null);
            }
        }
        return "unknown";
    }

    private static String mavenSetting(Element project, String setting) {
        String explicit = compilerPluginSetting(project, setting);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Element properties = directElement(project, "properties");
        String property = properties == null ? null
                : directText(properties, "maven.compiler." + setting);
        return property == null || property.isBlank() ? null : property;
    }

    private static String resolveMavenVersion(
            String raw, Element childProperties, Element parentProperties) {
        String value = raw;
        if (value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            value = childProperties == null ? null : directText(childProperties, key);
            if (value == null && parentProperties != null) {
                value = directText(parentProperties, key);
            }
        }
        return value == null || !value.matches("(?:1\\.)?\\d+")
                ? "unknown" : normalizeJavaVersion(value);
    }

    private static String compilerPluginSetting(Element project, String setting) {
        String direct = compilerPluginSettingIn(mavenPlugins(project), setting);
        if (direct != null) {
            return direct;
        }
        return compilerPluginSettingIn(mavenManagedPlugins(project), setting);
    }

    private static Element mavenPlugins(Element project) {
        Element build = directElement(project, "build");
        return build == null ? null : directElement(build, "plugins");
    }

    private static Element mavenManagedPlugins(Element project) {
        Element build = directElement(project, "build");
        Element management = build == null ? null : directElement(build, "pluginManagement");
        return management == null ? null : directElement(management, "plugins");
    }

    private static String compilerPluginSettingIn(Element plugins, String setting) {
        if (plugins == null) {
            return null;
        }
        NodeList children = plugins.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element plugin
                    && "plugin".equals(plugin.getTagName())
                    && "maven-compiler-plugin".equals(directText(plugin, "artifactId"))) {
                Element configuration = directElement(plugin, "configuration");
                return configuration == null ? null : directText(configuration, setting);
            }
        }
        return null;
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

    /**
     * The project paths, without the leading colon, that the {@code include} calls of a
     * settings script add. A call counts where it starts a statement: at the start of a line,
     * after a {@code ;} or a <code>{</code>, or right after another include call. Its whole
     * argument list is read: an {@code include(...)} list up to the parenthesis that closes
     * it, however many lines later, and a Groovy command call such as {@code include 'a', 'b'}
     * up to the end of its statement, which goes on to the next line after a trailing comma.
     * Comments are ignored. A build's own helper named {@code include} and a capital letter,
     * such as {@code includeProject("a")}, counts as an include call. A Gradle call whose name
     * only starts with {@code include}, such as {@code includeBuild} or {@code includeGroup},
     * adds no project, and neither does an argument that is not a literal string, such as
     * {@code include(it)}, {@code include("lib-$name")} or {@code include('lib-' + name)}.
     */
    private static List<String> gradleModules(String settings) {
        String code = withoutComments(settings);
        Set<String> modules = new LinkedHashSet<>();
        boolean statementStart = true;
        int index = 0;
        while (index < code.length()) {
            if (statementStart && isIncludeAt(code, index)) {
                int name = tokenEnd(code, index);
                int arguments = name;
                while (arguments < code.length() && isSpace(code.charAt(arguments))) {
                    arguments++;
                }
                char first = arguments < code.length() ? code.charAt(arguments) : '\n';
                if (first == '(') {
                    index = readIncludeList(code, arguments, modules);
                    continue;
                }
                if (first == '\'' || first == '"'
                        || arguments > name && Character.isJavaIdentifierStart(first)) {
                    index = readIncludeCommand(code, arguments, modules);
                    continue;
                }
            }
            statementStart = startsStatement(code.charAt(index), statementStart);
            index = tokenEnd(code, index);
        }
        return List.copyOf(modules);
    }

    /**
     * Adds the project paths of the {@code include(...)} list that opens at {@code open} and
     * returns the index after the parenthesis that closes it. A list that is still open where
     * the script ends, or where another include starts a statement, was never closed: it adds
     * only the paths on its first line and returns the index where it stopped.
     */
    private static int readIncludeList(String code, int open, Set<String> modules) {
        List<String> paths = new ArrayList<>();
        int firstLinePaths = -1;
        int depth = 1;
        char previous = '(';
        boolean statementStart = false;
        int index = open + 1;
        while (index < code.length() && !(statementStart && isIncludeAt(code, index))) {
            char character = code.charAt(index);
            int end = tokenEnd(code, index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                if (--depth == 0) {
                    modules.addAll(paths);
                    return end;
                }
            } else if (character == '\'' || character == '"') {
                addProjectPath(code, index, end, previous, paths);
            } else if (isLineBreak(character) && firstLinePaths < 0) {
                firstLinePaths = paths.size();
            }
            if (!isSpace(character) && !isLineBreak(character)) {
                previous = code.charAt(end - 1);
            }
            statementStart = startsStatement(character, statementStart);
            index = end;
        }
        modules.addAll(firstLinePaths < 0 ? paths : paths.subList(0, firstLinePaths));
        return index;
    }

    /**
     * Adds the project paths of a Groovy command call such as {@code include 'a', 'b'} whose
     * arguments start at {@code start}, and returns the index where they end: at a {@code ;}
     * or a <code>}</code>, at the end of a line that does not end with a comma or of the
     * script, where another include starts a statement, or where an operand follows another
     * without a comma, as when a block comment that spanned lines joined two statements.
     */
    private static int readIncludeCommand(String code, int start, Set<String> modules) {
        // The first argument follows the name as one follows a comma.
        char previous = ',';
        boolean statementStart = false;
        int index = start;
        while (index < code.length() && !(statementStart && isIncludeAt(code, index))) {
            char character = code.charAt(index);
            boolean quote = character == '\'' || character == '"';
            if (character == ';' || character == '}' || isLineBreak(character) && previous != ','
                    || (quote || Character.isJavaIdentifierStart(character))
                            && endsOperand(previous)) {
                break;
            }
            int end = tokenEnd(code, index);
            if (quote) {
                addProjectPath(code, index, end, previous, modules);
            }
            if (!isSpace(character) && !isLineBreak(character)) {
                previous = code.charAt(end - 1);
            }
            statementStart = startsStatement(character, statementStart);
            index = end;
        }
        return index;
    }

    /**
     * Adds the project path that the string from {@code start} to {@code end} names, unless
     * the quote opens no string or the string is only a part of the argument: a template such
     * as {@code "lib-$name"} or {@code ":${it.name}"}, or an operand of a {@code +}, as in
     * {@code 'lib-' + name}. {@code previous} is the last character before the string that is
     * not a space or a line break.
     */
    private static void addProjectPath(
            String code, int start, int end, char previous, java.util.Collection<String> paths) {
        if (end == start + 1 || previous == '+') {
            return;
        }
        int next = end;
        while (next < code.length()
                && (isSpace(code.charAt(next)) || isLineBreak(code.charAt(next)))) {
            next++;
        }
        if (next < code.length() && code.charAt(next) == '+') {
            return;
        }
        char quote = code.charAt(start);
        int quotes = code.startsWith(String.valueOf(quote).repeat(3), start) ? 3 : 1;
        String value = code.substring(start + quotes, end - quotes);
        if (quote == '"' && isTemplate(value)) {
            return;
        }
        String path = value.replaceFirst("^:", "");
        if (!path.isBlank()) {
            paths.add(path);
        }
    }

    /** Whether a double-quoted Groovy or Kotlin string holds a $name or ${...} template. */
    private static boolean isTemplate(String value) {
        for (int dollar = value.indexOf('$');
                dollar >= 0 && dollar + 1 < value.length();
                dollar = value.indexOf('$', dollar + 1)) {
            char next = value.charAt(dollar + 1);
            if ((dollar == 0 || value.charAt(dollar - 1) != '\\')
                    && (next == '{' || Character.isJavaIdentifierStart(next))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the identifier at {@code index} is include or a helper that wraps it: include and
     * a capital letter, such as includeProject, other than a Gradle call that adds no project.
     */
    private static boolean isIncludeAt(String code, int index) {
        if (!code.startsWith("include", index)) {
            return false;
        }
        int end = tokenEnd(code, index);
        return end == index + "include".length()
                || Character.isUpperCase(code.charAt(index + "include".length()))
                        && !GRADLE_NON_PROJECT_INCLUDES.contains(code.substring(index, end));
    }

    /** The end of the string, the identifier or the other single character at {@code index}. */
    private static int tokenEnd(String code, int index) {
        char character = code.charAt(index);
        if (character == '\'' || character == '"') {
            return stringEnd(code, index);
        }
        int end = index + 1;
        if (Character.isJavaIdentifierStart(character)) {
            while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
                end++;
            }
        }
        return end;
    }

    /** Whether a statement starts after {@code character}, given whether one did before it. */
    private static boolean startsStatement(char character, boolean statementStart) {
        return isLineBreak(character) || character == ';' || character == '{'
                || statementStart && isSpace(character);
    }

    /** Whether a token that ends with {@code last} ends a string, a name or a call. */
    private static boolean endsOperand(char last) {
        return last == '\'' || last == '"' || last == ')' || last == ']'
                || Character.isJavaIdentifierPart(last);
    }

    private static boolean isSpace(char character) {
        return character == ' ' || character == '\t' || character == '\f' || character == '\u000B';
    }

    private static boolean isLineBreak(char character) {
        return character == '\n' || character == '\r';
    }

    private static Set<String> gradleDependencies(String buildFile) {
        Set<String> dependencies = new LinkedHashSet<>();
        Matcher matcher = GRADLE_PROJECT.matcher(buildFile);
        while (matcher.find()) {
            dependencies.add(gradleModuleName(matcher.group(1)));
        }
        return dependencies;
    }

    /** The level that one declaration, from its keyword to its end, states. */
    private static String gradleJavaVersion(String buildFile) {
        String activeBuild = withoutComments(buildFile);
        Matcher release = GRADLE_RELEASE.matcher(activeBuild);
        if (activeBuild.contains("options.release")) {
            return release.find() ? release.group(1) : "unknown";
        }
        Matcher compatibility = GRADLE_COMPATIBILITY.matcher(activeBuild);
        if (activeBuild.contains("sourceCompatibility")) {
            if (!compatibility.find()) {
                return "unknown";
            }
            String constant = compatibility.group("constant");
            return gradleFeatureVersion(constant != null
                    ? constant.replace('_', '.')
                    : compatibility.group("number"));
        }
        Matcher toolchain = GRADLE_TOOLCHAIN.matcher(activeBuild);
        if (activeBuild.contains("languageVersion")) {
            return toolchain.find() ? toolchain.group(1) : "unknown";
        }
        return "unknown";
    }

    /**
     * A module's own level against the level that the root build gives it: unknown when both
     * are known and differ, the shared one when the module sets none (null).
     */
    private static String effectiveGradleModuleVersion(String ownVersion, String sharedVersion) {
        if (ownVersion == null) {
            return sharedVersion;
        }
        if (!"unknown".equals(sharedVersion)
                && !"unknown".equals(ownVersion)
                && !sharedVersion.equals(ownVersion)) {
            return "unknown";
        }
        return ownVersion;
    }

    /** Two levels that apply together: null when neither scope sets one, unknown when they differ. */
    private static String combineGradleLevels(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.equals(second) ? first : "unknown";
    }

    private static String orUnknown(String level) {
        return level == null ? "unknown" : level;
    }

    /**
     * The level that a scope sets for the main compilation, "unknown" when it cannot be read,
     * or null when the scope sets none. Every write of the strongest kind must state the same
     * literal level: Gradle keeps whichever runs last, which depends on conditions and
     * evaluation order that are not modeled here. A read, as in
     * targetCompatibility = sourceCompatibility, sets nothing, and neither does a write for
     * another target, as in eclipse.jdt.sourceCompatibility = 1.8 or
     * if (name == 'compileTestJava') options.release = 17.
     */
    private static String scopeLevel(String scope) {
        return writtenLevel(scope).level();
    }

    /**
     * The level that scopeLevel reads from a scope, with the rank of the kind of declaration
     * that sets it, higher for a stronger one (see GRADLE_LEVEL_KEYWORDS), or 0 when the scope
     * sets none. Gradle prefers the kinds in that order whichever scope sets them, so a scope
     * that writes a stronger kind than another overrides its level.
     */
    private static WrittenLevel writtenLevel(String scope) {
        String text = mainCompilationText(scope);
        java.util.Map<String, Set<String>> levels = new java.util.HashMap<>();
        Matcher keyword = GRADLE_LEVEL_KEYWORD.matcher(text);
        Matcher write = GRADLE_LEVEL_WRITE.matcher(text);
        Matcher otherReceiver = GRADLE_OTHER_RECEIVER.matcher(text);
        Matcher otherCondition = GRADLE_OTHER_CONDITION.matcher(text);
        int previousEnd = 0;
        boolean found = keyword.find();
        while (found) {
            String name = keyword.group();
            int start = keyword.start();
            int end = keyword.end();
            found = keyword.find();
            int limit = found ? keyword.start() : text.length();
            // The receiver and what precedes it are read back, but not into the previous keyword.
            int receiver = receiverStart(text, previousEnd, start);
            boolean other = otherReceiver.region(receiver, start).find()
                    || otherCondition.region(statementStart(text, previousEnd, receiver), receiver)
                            .matches();
            previousEnd = end;
            if (other || !write.region(end, text.length()).lookingAt()) {
                continue;
            }
            String statement = text.substring(start, statementEnd(text, end, limit));
            if (name.equals("languageVersion")
                    && KOTLIN_LANGUAGE_VERSION.matcher(statement).lookingAt()) {
                continue;
            }
            levels.computeIfAbsent(name, key -> new LinkedHashSet<>())
                    .add(gradleJavaVersion(statement));
        }
        for (int index = 0; index < GRADLE_LEVEL_KEYWORDS.size(); index++) {
            Set<String> written = levels.get(GRADLE_LEVEL_KEYWORDS.get(index));
            if (written != null) {
                return new WrittenLevel(
                        written.size() == 1 ? written.iterator().next() : "unknown",
                        GRADLE_LEVEL_KEYWORDS.size() - index);
            }
        }
        return new WrittenLevel(null, 0);
    }

    /**
     * Where the receiver of the keyword at {@code keyword} starts, such as eclipse.jdt. in
     * eclipse.jdt.sourceCompatibility or tasks.getByName('compileTestJava'). before
     * options.release, but not before {@code from}. A call or index counts when a dot follows it.
     */
    private static int receiverStart(String text, int from, int keyword) {
        int start = keyword;
        while (start > from) {
            char character = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(character) || character == '.') {
                start--;
            } else if (")]>".indexOf(character) >= 0 && text.charAt(start) == '.') {
                int opening = openingBefore(text, from, start - 1);
                if (opening < 0) {
                    break;
                }
                start = opening;
            } else {
                break;
            }
        }
        return start;
    }

    /** The bracket that opens the one at {@code closing}, or -1 when it is before {@code from}. */
    private static int openingBefore(String text, int from, int closing) {
        char close = text.charAt(closing);
        char open = close == ')' ? '(' : close == ']' ? '[' : '<';
        int depth = 0;
        for (int index = closing; index >= from; index--) {
            char character = text.charAt(index);
            if (character == close) {
                depth++;
            } else if (character == open && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /** Where the statement that reaches {@code end} starts, but not before {@code from}. */
    private static int statementStart(String text, int from, int end) {
        int start = end;
        while (start > from && "\n;{}".indexOf(text.charAt(start - 1)) < 0) {
            start--;
        }
        return start;
    }

    /**
     * Where a declaration that continues at {@code from} ends: at the end of its line or
     * statement outside brackets, unless the line ends with "=", at the bracket that closes
     * the block or call around it, or at {@code limit}.
     */
    private static int statementEnd(String text, int from, int limit) {
        int depth = 0;
        char last = ' ';
        for (int index = from; index < limit; index++) {
            char character = text.charAt(index);
            if (character == '(' || character == '[' || character == '{') {
                depth++;
            } else if (character == ')' || character == ']' || character == '}') {
                if (depth == 0) {
                    return index;
                }
                depth--;
            } else if ((character == '\n' || character == ';') && depth == 0 && last != '=') {
                return index;
            }
            if (!Character.isWhitespace(character)) {
                last = character;
            }
        }
        return limit;
    }

    /**
     * A scope without comments and without the blocks that configure something other than the
     * main compilation (see GRADLE_OTHER_TARGET). A block's header is the code before its
     * brace on the same line, or on the line above when the brace starts its line.
     */
    private static String mainCompilationText(String scope) {
        String active = withoutComments(scope);
        StringBuilder text = new StringBuilder(active.length());
        int cursor = 0;
        int headerStart = 0;
        int lineAboveStart = -1;
        boolean lineHasCode = false;
        for (int index = 0; index < active.length(); index++) {
            char character = active.charAt(index);
            if (character == '{') {
                int start = !lineHasCode && lineAboveStart >= 0 ? lineAboveStart : headerStart;
                if (GRADLE_OTHER_TARGET.matcher(active.substring(start, index).strip())
                        .matches()) {
                    int closing = closingEnd(active, index, '{', '}');
                    if (closing < 0) {
                        break;
                    }
                    text.append(active, cursor, start).append('\n');
                    cursor = closing;
                    index = closing - 1;
                }
                headerStart = index + 1;
                lineAboveStart = -1;
                lineHasCode = false;
            } else if (character == '\n') {
                if (lineHasCode) {
                    lineAboveStart = headerStart;
                }
                headerStart = index + 1;
                lineHasCode = false;
            } else if (character == ';' || character == '}') {
                headerStart = index + 1;
                lineAboveStart = -1;
                lineHasCode = false;
            } else if (!Character.isWhitespace(character)) {
                lineHasCode = true;
            }
        }
        text.append(active, cursor, active.length());
        return text.toString();
    }

    private static GradleScopes gradleScopes(String buildFile) {
        String active = withoutComments(buildFile);
        StringBuilder root = new StringBuilder();
        StringBuilder allProjects = new StringBuilder();
        StringBuilder subprojects = new StringBuilder();
        java.util.Map<String, StringBuilder> projectBodies = new java.util.LinkedHashMap<>();
        StringBuilder someProjects = new StringBuilder();
        StringBuilder someSubprojects = new StringBuilder();
        int cursor = 0;
        for (ScopeBlock block : scopeBlocks(active)) {
            if (block.start() < cursor) {
                // Inside a block already read, so not a block of the root build.
                continue;
            }
            int closing = closingEnd(active, block.opening(), '{', '}');
            if (closing < 0) {
                break;
            }
            root.append(active, cursor, block.start());
            String body = active.substring(block.opening() + 1, closing - 1) + '\n';
            String selector = block.selector().strip();
            List<String> paths = projectPaths(selector);
            if ("allprojects".equals(selector)) {
                allProjects.append(body);
            } else if ("subprojects".equals(selector)) {
                subprojects.append(body);
            } else if (paths != null) {
                for (String path : paths) {
                    if (path.isEmpty()) {
                        root.append(body);
                    } else {
                        projectBodies.computeIfAbsent(path, key -> new StringBuilder())
                                .append(body);
                    }
                }
            } else if (selector.startsWith("subprojects")
                    && !GRADLE_ROOT_SELECTOR.matcher(selector).find()) {
                someSubprojects.append(body);
            } else {
                someProjects.append(body);
            }
            cursor = closing;
        }
        root.append(active.substring(cursor));
        java.util.Map<String, String> projects = new java.util.LinkedHashMap<>();
        projectBodies.forEach((path, bodies) -> projects.put(path, bodies.toString()));
        WrittenLevel all = writtenLevel(allProjects.toString());
        WrittenLevel sub = writtenLevel(subprojects.toString());
        return new GradleScopes(scopeLevel(root.toString()), all.level(), sub.level(), projects,
                scopeLevel(someProjects.toString()), scopeLevel(someSubprojects.toString()),
                Math.max(all.rank(), sub.rank()));
    }

    /**
     * The allprojects, subprojects, project(...) and configure(...) blocks in text order. A
     * configure(...) inside the selector of an earlier one, or after a selector that never
     * closes, is not a block of its own: skipping it keeps each selector scanned once.
     */
    private static List<ScopeBlock> scopeBlocks(String text) {
        List<ScopeBlock> blocks = new ArrayList<>();
        Matcher shared = GRADLE_SHARED_BLOCK.matcher(text);
        while (shared.find()) {
            blocks.add(new ScopeBlock(shared.start(), shared.end() - 1, shared.group(1)));
        }
        Matcher targeted = GRADLE_TARGETED_BLOCK.matcher(text);
        Matcher opening = GRADLE_BLOCK_OPENING.matcher(text);
        int selectorsEnd = 0;
        while (targeted.find()) {
            if (targeted.group("project") != null) {
                blocks.add(new ScopeBlock(
                        targeted.start(), targeted.end() - 1, targeted.group("project")));
                continue;
            }
            if (targeted.start() < selectorsEnd) {
                continue;
            }
            int selectorEnd = closingEnd(text, targeted.end() - 1, '(', ')');
            selectorsEnd = selectorEnd < 0 ? text.length() : selectorEnd;
            if (selectorEnd > 0 && opening.region(selectorEnd, text.length()).lookingAt()) {
                String selector = text.substring(targeted.end(), selectorEnd - 1);
                // configure([compileJava, ...]) { } configures tasks of the enclosing project.
                if (!GRADLE_TASK_SELECTOR.matcher(selector).lookingAt()) {
                    blocks.add(new ScopeBlock(targeted.start(), opening.end() - 1, selector));
                }
            }
        }
        blocks.sort(java.util.Comparator.comparingInt(ScopeBlock::start));
        return blocks;
    }

    /**
     * The paths that a selector names, without the leading colon and with "" for the root:
     * project(':a'), [project(':a'), project(':b')] or rootProject. Null for anything else,
     * such as subprojects.findAll { ... }, whose projects are only known when Gradle runs.
     */
    private static List<String> projectPaths(String selector) {
        if ("rootProject".equals(selector)) {
            return List.of("");
        }
        String list = selector.startsWith("[") && selector.endsWith("]")
                ? selector.substring(1, selector.length() - 1)
                : selector;
        List<String> paths = new ArrayList<>();
        for (String item : list.split(",")) {
            if (item.isBlank()) {
                // After the trailing comma of a list written one project per line.
                continue;
            }
            Matcher project = GRADLE_PROJECT_PATH.matcher(item.strip());
            if (!project.matches()) {
                return null;
            }
            paths.add(project.group(1));
        }
        return paths;
    }

    /**
     * The index just past the bracket that closes the one at {@code opening}, or -1. A bracket
     * in a string does not count.
     */
    private static int closingEnd(String text, int opening, char open, char close) {
        int depth = 0;
        for (int index = opening; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\'' || character == '"') {
                index = stringEnd(text, index) - 1;
            } else if (character == open) {
                depth++;
            } else if (character == close && --depth == 0) {
                return index + 1;
            }
        }
        return -1;
    }

    /**
     * Replaces each comment in a Groovy or Kotlin build script with a space. Strings are copied
     * whole, so the comment markers in a glob such as {@code 'src/*.xml'} or a URL such as
     * {@code 'https://repo.example.com'} stay in their string. A quote that opens no string, as
     * in a Groovy slashy string, is code, and so is a {@code /*} that never closes.
     */
    private static String withoutComments(String script) {
        StringBuilder code = new StringBuilder(script.length());
        int lastCommentEnd = script.lastIndexOf("*/");
        int start = 0;
        while (start < script.length()) {
            char character = script.charAt(start);
            int end = start + 1;
            if (script.startsWith("//", start)) {
                while (end < script.length() && script.charAt(end) != '\n'
                        && script.charAt(end) != '\r') {
                    end++;
                }
                code.append(' ');
            } else if (script.startsWith("/*", start) && start + 2 <= lastCommentEnd) {
                end = script.indexOf("*/", start + 2) + 2;
                code.append(' ');
            } else {
                if (character == '\'' || character == '"') {
                    end = stringEnd(script, start);
                }
                code.append(script, start, end);
            }
            start = end;
        }
        return code.toString();
    }

    /**
     * Where the string that opens at {@code start} ends, or {@code start + 1} when that quote
     * opens no string. A single- or double-quoted string must close on its line, and a
     * backslash in it escapes the next character, a line break too. A triple-quoted string,
     * raw in Kotlin, ends with the next run of three or more quotes. A quote right after a
     * backslash opens no string: only a slashy string or a misread template leaves one in
     * code, and skipping those quotes keeps the scan linear.
     */
    private static int stringEnd(String script, int start) {
        char quote = script.charAt(start);
        if (start > 0 && script.charAt(start - 1) == '\\') {
            return start + 1;
        }
        String triple = String.valueOf(quote).repeat(3);
        if (script.startsWith(triple, start)) {
            int end = script.indexOf(triple, start + 3);
            if (end < 0) {
                return start + 1;
            }
            end += 3;
            while (end < script.length() && script.charAt(end) == quote) {
                end++;
            }
            return end;
        }
        int end = start + 1;
        while (end < script.length()
                && script.charAt(end) != '\n' && script.charAt(end) != '\r') {
            if (script.charAt(end) == quote) {
                return end + 1;
            }
            end += script.charAt(end) != '\\' ? 1 : script.startsWith("\r\n", end + 1) ? 3 : 2;
        }
        return start + 1;
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
            if (path.startsWith(projectRoot) && realPathIsWithinRoot(projectRoot, path)) {
                return path;
            }
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    description + " escapes project root: " + declaredPath));
        } catch (IOException exception) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    description + " could not be resolved safely: " + declaredPath));
        } catch (java.nio.file.InvalidPathException | SecurityException exception) {
            diagnostics.add(new ProjectDiagnostic(
                    "BUILD_MODEL_PARTIAL",
                    description + " is invalid: " + declaredPath));
        }
        return null;
    }

    private static boolean realPathIsWithinRoot(Path projectRoot, Path path) throws IOException {
        Path existingAncestor = path;
        while (existingAncestor != null
                && !Files.exists(existingAncestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        return existingAncestor != null
                && existingAncestor.toRealPath().startsWith(projectRoot.toRealPath());
    }

    private static boolean hasExistingSources(ProjectModule module) {
        return java.util.stream.Stream.concat(
                        module.mainSourceRoots().stream(), module.testSourceRoots().stream())
                .anyMatch(Files::isDirectory);
    }

    private static String normalizeJavaVersion(String version) {
        return version.startsWith("1.") ? version.substring(2) : version;
    }

    /** As Gradle's JavaVersion.toVersion: 1.8 and 1.8.0 are Java 8, 11.0.2 is Java 11. */
    private static String gradleFeatureVersion(String version) {
        String[] numbers = version.split("\\.");
        return "1".equals(numbers[0]) && numbers.length > 1 ? numbers[1] : numbers[0];
    }

    private static Path gradleSettings(Path root) {
        return firstRegularFile(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"));
    }

    private static Path gradleBuild(Path root) {
        return firstRegularFile(root.resolve("build.gradle.kts"), root.resolve("build.gradle"));
    }

    /** Reads a Gradle script without the byte order mark that some Windows editors write. */
    private static String readGradleScript(Path script) throws IOException {
        return SourceText.decode(withoutUtf8ByteOrderMark(Files.readAllBytes(script)));
    }

    /**
     * The bytes after a leading UTF-8 byte order mark, EF BB BF, or all of them when there is
     * none. Drop the mark before decoding: when the text after it is not valid UTF-8, it
     * decodes as Windows-1252, which reads the mark as "\u00EF\u00BB\u00BF".
     */
    private static byte[] withoutUtf8ByteOrderMark(byte[] content) {
        boolean utf8Bom = content.length >= 3 && content[0] == (byte) 0xEF
                && content[1] == (byte) 0xBB && content[2] == (byte) 0xBF;
        return utf8Bom ? Arrays.copyOfRange(content, 3, content.length) : content;
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

    /**
     * Whether a build script without comments changes which Java files belong to a source
     * set: through the java directory set of one, by adding or removing source sets, or by
     * configuring one other than main and test, whose sources jsrc does not look for. Reading a
     * source set, as in sourceSets.main.runtimeClasspath, or changing something else about
     * main or test, such as its output or compileClasspath, does not count.
     */
    private static boolean configuresJavaSourceSets(String script) {
        Matcher use = GRADLE_SOURCE_SETS.matcher(script);
        int from = 0;
        while (from < script.length() && use.find(from)) {
            int index = use.end();
            while (index < script.length() && Character.isWhitespace(script.charAt(index))) {
                index++;
            }
            if (index < script.length() && script.charAt(index) == '{') {
                int closing = closingEnd(script, index, '{', '}');
                int bodyEnd = closing < 0 ? script.length() : closing - 1;
                if (sourceSetBlockConfiguresJava(script.substring(index + 1, bodyEnd))) {
                    return true;
                }
                from = closing < 0 ? script.length() : closing;
                continue;
            }
            int end = statementEnd(script, index, script.length());
            if (sourceSetUseConfiguresJava(script.substring(index, end))) {
                return true;
            }
            from = Math.max(end, use.end());
        }
        return false;
    }

    /** Whether one use of the container, from just after its name, configures Java sources. */
    private static boolean sourceSetUseConfiguresJava(String use) {
        if (use.startsWith("*")) {
            // A spread, as in sourceSets*.java.exclude '...', reaches every source set.
            return GRADLE_JAVA_SOURCE_CHANGE.matcher(use).find();
        }
        Matcher access = GRADLE_SOURCE_SET_ACCESS.matcher(use);
        if (!access.lookingAt()) {
            return false;
        }
        return access.group(1) != null
                ? sourceSetConfiguresJava(use, access.end(), access.group(1), true, false)
                : sourceSetConfiguresJava(use, access.end(), access.group(2), false, false);
    }

    /** Whether the body of a sourceSets { } block configures Java sources, entry by entry. */
    private static boolean sourceSetBlockConfiguresJava(String body) {
        int start = 0;
        while (start < body.length()) {
            char character = body.charAt(start);
            if (Character.isWhitespace(character) || ";)]}".indexOf(character) >= 0) {
                start++;
                continue;
            }
            int end = statementEnd(body, start, body.length());
            String entry = body.substring(start, end);
            Matcher name = GRADLE_SOURCE_SET_ENTRY.matcher(entry);
            if (GRADLE_DELEGATED_SOURCE_SET.matcher(entry).find()) {
                return true;
            }
            if (name.lookingAt()) {
                boolean configures = name.group(1) == null
                        ? sourceSetConfiguresJava(entry, name.end(), name.group(2), false, true)
                        : GRADLE_STATEMENT_KEYWORD.matcher(name.group(1)).matches()
                                ? GRADLE_JAVA_SOURCE_CHANGE.matcher(entry).find()
                                : sourceSetConfiguresJava(
                                        entry, name.end(), name.group(1), true, true);
                if (configures) {
                    return true;
                }
            }
            start = Math.max(end, start + 1);
        }
        return false;
    }

    /**
     * Whether {@code text}, from {@code index} on, configures the Java sources of the source
     * set {@code name}, or of those a container call named {@code name} reaches when
     * {@code call} allows one. A closure for main or test counts only when it changes their
     * java directory set; a closure for any other source set counts, and so does its bare
     * name in a sourceSets block ({@code inBlock}), which creates it.
     */
    private static boolean sourceSetConfiguresJava(
            String text, int index, String name, boolean call, boolean inBlock) {
        if (call && GRADLE_SOURCE_SET_CHANGE.matcher(name).matches()) {
            return true;
        }
        if (call && GRADLE_EVERY_SOURCE_SET.matcher(name).matches()) {
            return GRADLE_JAVA_SOURCE_CHANGE.matcher(text).region(index, text.length()).find();
        }
        if (call && GRADLE_SOURCE_SET_LOOKUP.matcher(name).matches()) {
            Matcher argument = GRADLE_SOURCE_SET_ARGUMENT.matcher(text)
                    .region(index, text.length());
            if (!argument.lookingAt()) {
                return GRADLE_JAVA_SOURCE_CHANGE.matcher(text).region(index, text.length())
                        .find();
            }
            name = argument.group(1);
            index = argument.end();
        }
        Matcher get = GRADLE_PROVIDER_GET.matcher(text).region(index, text.length());
        if (get.lookingAt()) {
            index = get.end();
        }
        if (GRADLE_SOURCE_SET_CLOSURE.matcher(text).region(index, text.length()).lookingAt()
                || inBlock && text.substring(index).isBlank()) {
            return !CONVENTIONAL_SOURCE_SETS.contains(name)
                    || GRADLE_JAVA_SOURCE_CHANGE.matcher(text).region(index, text.length())
                            .find();
        }
        return GRADLE_JAVA_MEMBER_CHANGE.matcher(text).region(index, text.length()).lookingAt();
    }

    private static boolean containsSimpleSourceDirectory(String buildFile) {
        return buildFile.contains("srcDirs") || buildFile.contains("srcDir");
    }

    private record MavenModuleData(
            String name, Path path, Set<String> dependencies, Element pom) {}

    private record GradleModuleData(String name, Path path, String buildFile) {}

    /**
     * The levels that the parts of the root build set, each read once, or null where a part
     * sets none: {@code own} for the root project's code outside the blocks below and its
     * rootProject blocks, {@code allProjects} and {@code subprojects} for those blocks, and
     * {@code someProjects} and {@code someSubprojects} for configure(...) blocks whose
     * selector, such as subprojects.findAll { ... }, is only evaluated by Gradle.
     * {@code projects} maps a project path to the bodies of its project(...) and
     * configure(...) blocks. {@code sharedRank} is the rank of the strongest kind of
     * declaration that the allprojects and subprojects blocks write.
     */
    private record GradleScopes(
            String own,
            String allProjects,
            String subprojects,
            java.util.Map<String, String> projects,
            String someProjects,
            String someSubprojects,
            int sharedRank) {

        /** The bodies of the project blocks by the directory of the project they configure. */
        java.util.Map<Path, String> projectBlocksByDirectory(Path root) {
            java.util.Map<Path, StringBuilder> bodies = new java.util.HashMap<>();
            projects.forEach((path, body) -> {
                try {
                    bodies.computeIfAbsent(root.resolve(path.replace(':', '/')).toAbsolutePath()
                            .normalize(), directory -> new StringBuilder()).append(body);
                } catch (java.nio.file.InvalidPathException exception) {
                    // Not a directory path, so not an included module either.
                }
            });
            java.util.Map<Path, String> blocks = new java.util.HashMap<>();
            bodies.forEach((directory, text) -> blocks.put(directory, text.toString()));
            return blocks;
        }

        String rootLevel() {
            return unlessSelectedOtherwise(
                    orUnknown(combineGradleLevels(own, allProjects)), false);
        }

        /**
         * The level of a module with these root-build project blocks (null for none) and this
         * build file. The project blocks run in the same script as the allprojects and
         * subprojects blocks, in an order not modeled here, so they keep their level only when
         * those set none or the same one, readable or not. When they do not, the module's own
         * level stands unless they write a stronger kind of declaration than the module does,
         * which Gradle applies instead of it, whatever level it states.
         */
        String moduleLevel(String projectBlocks, String moduleBuild) {
            WrittenLevel block = projectBlocks == null
                    ? new WrittenLevel(null, 0) : writtenLevel(projectBlocks);
            WrittenLevel own = writtenLevel(moduleBuild);
            String rootBuildLevel = combineGradleLevels(
                    block.level(), combineGradleLevels(allProjects, subprojects));
            if ("unknown".equals(orUnknown(rootBuildLevel))
                    && Math.max(block.rank(), sharedRank) > own.rank()) {
                return "unknown";
            }
            return unlessSelectedOtherwise(effectiveGradleModuleVersion(
                    own.level(), orUnknown(rootBuildLevel)), true);
        }

        /**
         * {@code level}, or unknown when a configure(...) block that may select the project
         * sets a different level.
         */
        String unlessSelectedOtherwise(String level, boolean subproject) {
            boolean differs = differs(someProjects, level)
                    || subproject && differs(someSubprojects, level);
            return differs ? "unknown" : level;
        }

        private static boolean differs(String selectedLevel, String level) {
            return selectedLevel != null && !selectedLevel.equals(level);
        }
    }

    private record ScopeBlock(int start, int opening, String selector) {}

    private record WrittenLevel(String level, int rank) {}
}
