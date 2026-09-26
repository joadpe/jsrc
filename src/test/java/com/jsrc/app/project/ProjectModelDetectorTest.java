package com.jsrc.app.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectModelDetectorTest {

    @TempDir
    Path projectRoot;

    @Test
    void detectsMavenModulesJavaVersionAndInternalDependencies() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>shop</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <modules><module>domain</module><module>application</module></modules>
                </project>
                """);
        writePom("domain", """
                <artifactId>domain</artifactId>
                """);
        writePom("application", """
                <artifactId>application</artifactId>
                <dependencies><dependency>
                  <groupId>com.example</groupId><artifactId>domain</artifactId><version>1.0.0</version>
                </dependency></dependencies>
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(BuildSystem.MAVEN, model.buildSystem());
        assertEquals("21", model.javaVersion());
        assertEquals(2, model.modules().size());
        ProjectModule application = model.module("application").orElseThrow();
        assertEquals(
                projectRoot.resolve("application/src/main/java"),
                application.mainSourceRoots().getFirst());
        assertEquals(
                projectRoot.resolve("application/src/test/java"),
                application.testSourceRoots().getFirst());
        assertEquals(java.util.List.of("domain"), application.internalDependencies());
        assertTrue(model.diagnostics().isEmpty());
    }

    @Test
    void inheritedMavenReleaseTakesPrecedenceOverChildSource() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <modules><module>child</module></modules>
                </project>
                """);
        writePom("child", """
                <artifactId>child</artifactId>
                <properties><maven.compiler.source>8</maven.compiler.source></properties>
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("17", model.module("child").orElseThrow().javaVersion());
    }

    @Test
    void inheritsMavenCompilerReleaseFromPluginManagement() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <packaging>pom</packaging><modules><module>child</module></modules>
                  <build><pluginManagement><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><release>8</release></configuration>
                  </plugin></plugins></pluginManagement></build>
                </project>
                """);
        writePom("child", "<artifactId>child</artifactId>");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("child").orElseThrow().javaVersion());
    }

    @Test
    void inheritedMavenPluginReleaseTakesPrecedenceOverChildProperty() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <modules><module>child</module></modules>
                  <build><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><release>8</release></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        writePom("child", """
                <artifactId>child</artifactId>
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("child").orElseThrow().javaVersion());
    }

    @Test
    void conflictingGradleSharedLevelsRemainUnknownForChild() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), "include(\":child\")");
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                allprojects { sourceCompatibility = JavaVersion.VERSION_17 }
                subprojects { sourceCompatibility = JavaVersion.VERSION_8 }
                """);
        writeGradleModule("child", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("17", model.javaVersion());
        assertEquals("unknown", model.module("child").orElseThrow().javaVersion());
    }

    @Test
    void inheritsGradleSubprojectsSourceCompatibilityOnlyForChildren() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), "include(\":child\")");
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                subprojects {
                    sourceCompatibility = JavaVersion.VERSION_8
                }
                """);
        writeGradleModule("child", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("8", model.module("child").orElseThrow().javaVersion());
    }

    @Test
    void dynamicGradleReleaseDoesNotFallBackToStaticSource() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                sourceCompatibility = JavaVersion.VERSION_17
                tasks.withType<JavaCompile>().configureEach {
                    options.release.set(providers.gradleProperty("javaRelease").map(String::toInt))
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
    }

    @Test
    void detectsGradleModulesToolchainAndProjectDependencies() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), """
                rootProject.name = "shop"
                include(":domain", ":application")
                """);
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                java {
                    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
                }
                """);
        writeGradleModule("domain", "");
        writeGradleModule("application", "implementation(project(\":domain\"))");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(BuildSystem.GRADLE, model.buildSystem());
        assertEquals("17", model.javaVersion());
        assertEquals(2, model.modules().size());
        assertEquals(
                java.util.List.of("domain"),
                model.module("application").orElseThrow().internalDependencies());
        assertTrue(model.diagnostics().isEmpty());
    }

    @Test
    void fallsBackToConventionalRootsWithStructuredDiagnostic() {
        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(BuildSystem.UNKNOWN, model.buildSystem());
        assertEquals(1, model.modules().size());
        assertEquals(
                projectRoot.resolve("src/main/java"),
                model.modules().getFirst().mainSourceRoots().getFirst());
        assertEquals("BUILD_MODEL_FALLBACK", model.diagnostics().getFirst().code());
    }

    @Test
    void detectsSingleModuleMavenCustomSourceRoots() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>single</artifactId><version>1</version>
                  <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                  <build>
                    <sourceDirectory>src/core/java</sourceDirectory>
                    <testSourceDirectory>src/spec/java</testSourceDirectory>
                  </build>
                </project>
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.javaVersion());
        ProjectModule module = model.modules().getFirst();
        assertEquals(List.of(projectRoot.resolve("src/core/java")), module.mainSourceRoots());
        assertEquals(List.of(projectRoot.resolve("src/spec/java")), module.testSourceRoots());
    }

    @Test
    void detectsSingleModuleGradleStaticSourceSets() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                sourceCompatibility = JavaVersion.VERSION_11
                sourceSets {
                    main.java.srcDirs = ['src/core/java']
                    test.java.srcDirs = ['src/spec/java']
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("11", model.javaVersion());
        ProjectModule module = model.modules().getFirst();
        assertEquals(List.of(projectRoot.resolve("src/core/java")), module.mainSourceRoots());
        assertEquals(List.of(projectRoot.resolve("src/spec/java")), module.testSourceRoots());
        assertTrue(model.diagnostics().isEmpty());
    }

    @Test
    void rejectsMavenModulesAndSourceRootsOutsideProjectRoot() throws Exception {
        Path outside = Files.createDirectories(projectRoot.getParent().resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>outside</artifactId></project>
                """);
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion><artifactId>root</artifactId>
                  <modules><module>../outside</module><module>inside</module></modules>
                </project>
                """);
        writePom("inside", """
                <artifactId>inside</artifactId>
                <build><sourceDirectory>../../outside/src</sourceDirectory></build>
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertTrue(model.module("outside").isEmpty());
        assertEquals(List.of(), model.module("inside").orElseThrow().mainSourceRoots());
        assertEquals(2, model.diagnostics().size());
        assertTrue(model.diagnostics().stream()
                .allMatch(diagnostic -> "BUILD_MODEL_PARTIAL".equals(diagnostic.code())));
    }

    @Test
    void rejectsGradleModulesAndSourceRootsOutsideProjectRoot() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include('../outside', 'inside')");
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java' }");
        Path inside = Files.createDirectories(projectRoot.resolve("inside"));
        Files.writeString(inside.resolve("build.gradle"), """
                sourceSets { main.java.srcDirs = ['../../outside/src'] }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertTrue(model.module("outside").isEmpty());
        assertEquals(List.of(), model.module("inside").orElseThrow().mainSourceRoots());
        assertEquals(2, model.diagnostics().size());
    }

    @Test
    void rejectsMavenModuleAndSourceRootSymlinksOutsideProjectRoot() throws Exception {
        Path outside = Files.createDirectories(projectRoot.getParent().resolve("outside-maven"));
        Files.createDirectories(outside.resolve("src"));
        Files.writeString(outside.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>outside</artifactId></project>
                """);
        Files.createSymbolicLink(projectRoot.resolve("outside-module"), outside);
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion><artifactId>root</artifactId>
                  <modules><module>outside-module</module><module>inside</module></modules>
                </project>
                """);
        writePom("inside", """
                <artifactId>inside</artifactId>
                <build><sourceDirectory>linked-src/generated/java</sourceDirectory></build>
                """);
        Files.createSymbolicLink(projectRoot.resolve("inside/linked-src"), outside.resolve("src"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertTrue(model.module("outside").isEmpty());
        assertEquals(List.of(), model.module("inside").orElseThrow().mainSourceRoots());
        assertEquals(2, model.diagnostics().size());
    }

    @Test
    void rejectsGradleModuleAndSourceRootSymlinksOutsideProjectRoot() throws Exception {
        Path outside = Files.createDirectories(projectRoot.getParent().resolve("outside-gradle"));
        Files.createDirectories(outside.resolve("src"));
        Files.writeString(outside.resolve("build.gradle"), "plugins { id 'java' }");
        Files.createSymbolicLink(projectRoot.resolve("outside-module"), outside);
        Files.writeString(
                projectRoot.resolve("settings.gradle"), "include('outside-module', 'inside')");
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java' }");
        Path inside = Files.createDirectories(projectRoot.resolve("inside"));
        Files.writeString(inside.resolve("build.gradle"), """
                sourceSets { main.java.srcDirs = ['linked-src/generated/java'] }
                """);
        Files.createSymbolicLink(inside.resolve("linked-src"), outside.resolve("src"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertTrue(model.module("outside-module").isEmpty());
        assertEquals(List.of(), model.module("inside").orElseThrow().mainSourceRoots());
        assertEquals(2, model.diagnostics().size());
    }

    @Test
    void includesMavenRootSourcesAlongsideChildModules() throws Exception {
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion><artifactId>root</artifactId>
                  <modules><module>child</module></modules>
                </project>
                """);
        writePom("child", "<artifactId>child</artifactId>");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(2, model.modules().size());
        assertTrue(model.module("root").isPresent());
        assertTrue(model.module("child").isPresent());
    }

    @Test
    void includesGradleRootSourcesAlongsideChildModules() throws Exception {
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("settings.gradle"), "include('child')");
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java' }");
        writeGradleModule("child", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(2, model.modules().size());
        assertTrue(model.module(projectRoot.getFileName().toString()).isPresent());
        assertTrue(model.module("child").isPresent());
    }

    @Test
    void keepsValidMavenModulesWhenAnotherModuleIsInvalid() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion><artifactId>root</artifactId>
                  <modules><module>valid</module><module>invalid</module></modules>
                </project>
                """);
        writePom("valid", "<artifactId>valid</artifactId>");
        Path invalid = Files.createDirectories(projectRoot.resolve("invalid"));
        Files.writeString(invalid.resolve("pom.xml"), "<project>");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(1, model.modules().size());
        assertTrue(model.module("valid").isPresent());
        assertTrue(model.module("invalid").isEmpty());
        assertEquals("BUILD_MODEL_PARTIAL", model.diagnostics().getFirst().code());
    }

    private void writePom(String module, String body) throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve(module));
        Files.writeString(directory.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId><artifactId>shop</artifactId>
                    <version>1.0.0</version>
                  </parent>
                """ + body + "</project>");
    }

    private void writeGradleModule(String module, String body) throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve(module));
        Files.writeString(directory.resolve("build.gradle.kts"), "plugins { java }\n" + body);
    }
}
