package com.jsrc.app.project;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectModelDetectorTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

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
    void readsGradleSourceCompatibilityBetweenGlobPatterns() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                apply plugin: 'java'
                processResources { include '**/*.xml' }
                sourceCompatibility = JavaVersion.VERSION_1_8
                jar { exclude '**/*.properties' }
                """);

        assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion());

        // With double quotes, and with a later test-only declaration that must not win.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                apply plugin: 'java'
                processResources { include "**/*.xml" }
                sourceCompatibility = JavaVersion.VERSION_1_8
                processTestResources { exclude "**/*.properties" }
                compileTestJava { sourceCompatibility = JavaVersion.VERSION_11 }
                """);

        assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion());
    }

    @Test
    void readsGlobPatternsInEveryGradleStringForm() throws Exception {
        for (String description : List.of(
                "'Won\\'t copy **/*.bak files'",
                "\"Won't copy **/*.bak files\"",
                "'''Won't copy\n**/*.bak files'''",
                "\"\"\"Won't copy\n**/*.bak files\"\"\"")) {
            Files.writeString(projectRoot.resolve("build.gradle"),
                    "description = " + description + "\n" + """
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    jar { exclude '**/*.properties' }
                    """);

            assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion(),
                    description);
        }
    }

    @Test
    void stripsGradleCommentsNextToRawContinuedAndUnpairedQuotes() throws Exception {
        // Each build comments out Java 6 next to a string, or a quote that opens none.
        List<List<String>> builds = List.of(
                List.of("build.gradle.kts", "17", """
                        val tools = \"\"\"C:\\tools\\\"\"\"
                        // java.sourceCompatibility = JavaVersion.VERSION_1_6
                        java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
                        """),
                List.of("build.gradle.kts", "17", """
                        val q = \"\"\"\"a\"\"\"\" // sourceCompatibility = JavaVersion.VERSION_1_6
                        java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
                        """),
                List.of("build.gradle", "8", """
                        description = 'first part \\
                        second part' /* note:
                        sourceCompatibility = JavaVersion.VERSION_1_6 */
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        """.replace("\n", "\r\n")),
                List.of("build.gradle", "8", """
                        def unquote = { it.replaceAll(/'/, '') } /* legacy:
                        sourceCompatibility = JavaVersion.VERSION_1_6 */
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        """));
        for (int i = 0; i < builds.size(); i++) {
            List<String> build = builds.get(i);
            Path project = Files.createDirectories(projectRoot.resolve("build" + i));
            Files.writeString(project.resolve(build.get(0)), build.get(2));

            assertEquals(build.get(1), new ProjectModelDetector().detect(project).javaVersion(),
                    build.get(2));
        }
    }

    @Test
    void repositoryUrlDoesNotHideTheEndOfAGradleSubprojectsBlock() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    repositories { maven { url 'https://repo.example.com/maven2' } }
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }
                """);
        Files.createDirectories(projectRoot.resolve("billing"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void readsGradleSubprojectsBlockAfterAByteOrderMark() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        // Written as UTF-8, U+FEFF is the byte order mark EF BB BF.
        Files.writeString(projectRoot.resolve("build.gradle"), "\uFEFF" + """
                subprojects {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }
                """);
        Files.createDirectories(projectRoot.resolve("billing"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void readsGradleIncludeAfterAByteOrderMark() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"),
                "\uFEFFinclude 'billing', 'reports'\n");
        Files.writeString(projectRoot.resolve("build.gradle"),
                "subprojects { sourceCompatibility = JavaVersion.VERSION_1_8 }");
        Files.createDirectories(projectRoot.resolve("billing"));
        Files.createDirectories(projectRoot.resolve("reports"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("billing", "reports"),
                model.modules().stream().map(ProjectModule::name).toList());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void readsGradleSubprojectsBlockAfterAByteOrderMarkInAWindows1252Script() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        // "ï»¿" is written as EF BB BF, a UTF-8 byte order mark, but the "ó"
        // after it is the Windows-1252 byte F3, so the script is not valid UTF-8.
        Files.write(projectRoot.resolve("build.gradle"), ("ï»¿" + """
                subprojects {
                    // configuración
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }
                """).getBytes(WINDOWS_1252));
        Files.createDirectories(projectRoot.resolve("billing"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void readsGradleIncludeAfterAByteOrderMarkInAWindows1252Script() throws Exception {
        // As above, a byte order mark before Windows-1252 text.
        Files.write(projectRoot.resolve("settings.gradle"), ("ï»¿" + """
                include 'billing', 'reports'
                // módulos de catálogo
                """).getBytes(WINDOWS_1252));
        Files.writeString(projectRoot.resolve("build.gradle"),
                "subprojects { sourceCompatibility = JavaVersion.VERSION_1_8 }");
        Files.createDirectories(projectRoot.resolve("billing"));
        Files.createDirectories(projectRoot.resolve("reports"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("billing", "reports"),
                model.modules().stream().map(ProjectModule::name).toList());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void appliesGradleProjectBlocksToTheirModuleInsteadOfTheRoot() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'reports'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java' }
                project(':billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        writeGradleModule("billing", "");
        writeGradleModule("reports", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("11", model.module("billing").orElseThrow().javaVersion());
        assertEquals("unknown", model.module("reports").orElseThrow().javaVersion());

        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java'; sourceCompatibility = JavaVersion.VERSION_1_8 }
                project(':billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        model = new ProjectModelDetector().detect(projectRoot);

        // Like a module's own build file, a project block that disagrees with subprojects is
        // not settled by evaluation order.
        assertEquals("unknown", model.javaVersion());
        assertEquals("unknown", model.module("billing").orElseThrow().javaVersion());
        assertEquals("8", model.module("reports").orElseThrow().javaVersion());

        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java' }
                project(':billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                project(':billing') { dependencies { implementation project(':reports') } }
                """);
        assertEquals("11", new ProjectModelDetector().detect(projectRoot)
                .module("billing").orElseThrow().javaVersion());

        // The subprojects block runs after the project block and sets a level that cannot
        // be read.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                project(':billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                subprojects { apply plugin: 'java'; sourceCompatibility = javaLevel }
                """);
        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot)
                .module("billing").orElseThrow().javaVersion());
    }

    @Test
    void appliesGradleConfigureBlocksOnlyToTheProjectsTheyName() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'reports'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                configure([project(':billing'), project(':reports')]) {
                    apply plugin: 'java'
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);
        writeGradleModule("billing", "");
        writeGradleModule("reports", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertEquals("11", model.module("billing").orElseThrow().javaVersion());
        assertEquals("11", model.module("reports").orElseThrow().javaVersion());

        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java'; sourceCompatibility = JavaVersion.VERSION_1_8 }
                configure(subprojects.findAll { it.name == 'billing' }) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);
        model = new ProjectModelDetector().detect(projectRoot);

        // findAll may select any subproject, so each of them may compile at 8 or at 11.
        assertEquals("unknown", model.javaVersion());
        assertEquals("unknown", model.module("billing").orElseThrow().javaVersion());
        assertEquals("unknown", model.module("reports").orElseThrow().javaVersion());

        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java'; sourceCompatibility = JavaVersion.VERSION_1_8 }
                configure(subprojects.findAll { it.name != 'docs' }) {
                    targetCompatibility = sourceCompatibility
                }
                """);
        model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
        assertEquals("8", model.module("reports").orElseThrow().javaVersion());

        Files.writeString(projectRoot.resolve("build.gradle"), """
                configure(rootProject) { sourceCompatibility = JavaVersion.VERSION_11 }
                subprojects { apply plugin: 'java'; sourceCompatibility = JavaVersion.VERSION_1_8 }
                """);
        model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("11", model.javaVersion());
        assertEquals("8", model.module("billing").orElseThrow().javaVersion());

        // Selectors that may hold the root project make its level unknown too.
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                configure(subprojects.findAll { it.name != 'docs' } + rootProject) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """));
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                configure(javaProjects) { sourceCompatibility = JavaVersion.VERSION_11 }
                """));

        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects { apply plugin: 'java' }
                configure([project(':billing'), project(':reports'),]) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);
        model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("11", model.module("billing").orElseThrow().javaVersion());
        assertEquals("11", model.module("reports").orElseThrow().javaVersion());
    }

    @Test
    void ignoresGradleLevelsForIdeMetadataAndTestCompilation() throws Exception {
        assertEquals("11", gradleLevel("""
                plugins { id 'java'; id 'eclipse' }
                eclipse { jdt { sourceCompatibility = JavaVersion.VERSION_1_8 } }
                compileTestJava { options.release = 17 }
                tasks.named('compileTestJava') {
                    javaCompiler = javaToolchains.compilerFor {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                java { sourceCompatibility = JavaVersion.VERSION_11 }
                """));
        assertEquals("11", gradleLevel("""
                eclipse.jdt.sourceCompatibility = JavaVersion.VERSION_1_8
                compileTestJava.sourceCompatibility = JavaVersion.VERSION_17
                sourceCompatibility = JavaVersion.VERSION_11
                """));
        assertEquals("11", gradleLevel("""
                java { toolchain { languageVersion = JavaLanguageVersion.of(11) } }
                tasks.withType(Test).configureEach {
                    javaLauncher = javaToolchains.launcherFor {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                tasks.withType(Javadoc).configureEach {
                    javadocTool = javaToolchains.javadocToolFor {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                }
                """));
        assertEquals("17", gradleLevel("""
                java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
                tasks.withType<KotlinCompile> { kotlinOptions { languageVersion = "1.9" } }
                """));

        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        Files.writeString(projectRoot.resolve("build.gradle"),
                "subprojects { sourceCompatibility = JavaVersion.VERSION_1_8 }");
        writeGradleModule("billing",
                "eclipse { jdt { sourceCompatibility = JavaVersion.VERSION_1_6 } }");

        assertEquals("8", new ProjectModelDetector().detect(projectRoot)
                .module("billing").orElseThrow().javaVersion());
    }

    @Test
    void doesNotHideGradleLevelsWhoseConditionNamesAnotherCompileTask() throws Exception {
        // A level that depends on the task name cannot be read, but it is not test-only.
        assertEquals("unknown", gradleLevel("""
                tasks.withType(JavaCompile).configureEach {
                    if (name != 'compileTestJava') {
                        options.release = 8
                    } else {
                        options.release = 17
                    }
                }
                """));
        assertEquals("unknown", gradleLevel("""
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                tasks.withType(JavaCompile).configureEach {
                    options.release = name == 'compileTestJava' ? 21 : 8
                }
                """));
        assertEquals("8", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_6
                (tasks.withType(JavaCompile) - compileTestJava).each { it.options.release = 8 }
                """));
        assertEquals("8", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_6
                [compileJava, compileTestJava].each { it.options.release = 8 }
                """));
        assertEquals("8", gradleLevel("""
                configure([compileJava, compileTestJava]) {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    targetCompatibility = JavaVersion.VERSION_1_8
                }
                """));
    }

    @Test
    void ignoresGradleLevelsOfOtherCompileTasksInEveryForm() throws Exception {
        // The brace of a block may start the line after its header.
        assertEquals("11", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_11
                tasks.named('compileTestJava')
                {
                    options.release = 17
                }
                eclipse
                {
                    jdt { sourceCompatibility = JavaVersion.VERSION_1_6 }
                }
                """));
        // A receiver may go through a call, a cast or an index.
        assertEquals("8", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                tasks.getByName('compileTestJava').options.release = 17
                """));
        assertEquals("11", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_11
                (compileTestJava as JavaCompile).options.release = 17
                """));
        assertEquals("11", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_11
                tasks['compileTestJava'].options.release = 17
                """));
        // A condition that holds for that task alone.
        assertEquals("11", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_11
                tasks.withType(JavaCompile).configureEach {
                    if (name == 'compileTestJava') {
                        options.release = 17
                    }
                    if (it.name == 'compileTestJava') options.release = 17
                    if (name.equals('compileTestJava')) {
                        options.release = 17
                    }
                    if ('compileTestJava' == it.name) {
                        options.release = 17
                    }
                }
                """));
        assertEquals("8", gradleLevel("""
                tasks.withType(JavaCompile) {
                    if (name == 'compileTestJava') {
                        options.release = 17
                    } else {
                        options.release = 8
                    }
                }
                """));
        assertEquals("11", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_11
                configure([compileTestJava]) { options.release = 17 }
                """));
        // Tasks that the build registers, and the compile task of another source set.
        assertEquals("8", gradleLevel("""
                compileJava { options.release = 8 }
                compileJava9Java { options.release = 9 }
                task compileModuleInfo(type: JavaCompile) {
                    options.release = 9
                }
                """));
    }

    @Test
    void ignoresKotlinGradleLevelsOfOtherCompileTasksAndOfKotlin() throws Exception {
        assertEquals("11", kotlinGradleLevel("""
                plugins { java }
                java { sourceCompatibility = JavaVersion.VERSION_11 }
                tasks.named<JavaCompile>("compileTestJava").get().options.release.set(17)
                """));
        assertEquals("8", kotlinGradleLevel("""
                plugins { java }
                tasks.compileJava { options.release.set(8) }
                val compileModuleInfo by tasks.registering(JavaCompile::class) {
                    options.release.set(9)
                }
                """));
        assertEquals("8", kotlinGradleLevel("""
                plugins { java }
                tasks.withType<JavaCompile>().configureEach {
                    when (name) {
                        "compileTestJava" -> { options.release.set(17) }
                        else -> { options.release.set(8) }
                    }
                }
                """));
        assertEquals("8", kotlinGradleLevel("""
                plugins { java }
                tasks.withType<JavaCompile>().configureEach {
                    when (name) {
                        "compileTestJava" -> options.release.set(17)
                        else -> options.release.set(8)
                    }
                }
                """));
        assertEquals("17", kotlinGradleLevel("""
                plugins { java; kotlin("jvm") }
                java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
                kotlin { compilerOptions { languageVersion.set(KotlinVersion.KOTLIN_1_9) } }
                tasks.compileTestKotlin {
                    compilerOptions { languageVersion.set(KotlinVersion.KOTLIN_2_0) }
                }
                """));
    }

    @Test
    void readsGradleLevelsWrittenOverSeveralLines() throws Exception {
        assertEquals("11", gradleLevel("""
                sourceCompatibility =
                        JavaVersion.VERSION_11
                """));
        assertEquals("17", gradleLevel("""
                java {
                    toolchain {
                        languageVersion.set(
                                JavaLanguageVersion.of(17)
                        )
                    }
                }
                """));
    }

    @Test
    void readsLargeGradleBuildsInLinearTime() throws Exception {
        StringBuilder settings = new StringBuilder();
        StringBuilder build = new StringBuilder("// header\n".repeat(20_000))
                .append(" ".repeat(100_000)).append('\n');
        for (int index = 0; index < 5_000; index++) {
            settings.append("include 'm").append(index).append("'\n");
            build.append("\n".repeat(20))
                    .append("project(':m").append(index)
                    .append("') { sourceCompatibility = JavaVersion.VERSION_11 }\n")
                    .append("configure(subprojects.findAll { it.name == 'm").append(index)
                    .append("' }) { apply plugin: 'java' }\n");
        }
        build.append("subprojects { ")
                .append("sourceCompatibility = JavaVersion.VERSION_11 ".repeat(20_000))
                .append("}\n");
        Files.writeString(projectRoot.resolve("settings.gradle"), settings);
        Files.writeString(projectRoot.resolve("build.gradle"), build);

        ProjectModel model = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10),
                () -> new ProjectModelDetector().detect(projectRoot));

        assertEquals("11", model.module("m4999").orElseThrow().javaVersion());
    }

    @Test
    void readsGradleToolchainSetThroughPropertyMethods() throws Exception {
        assertEquals("17", gradleLevel("""
                java { toolchain { languageVersion.value(JavaLanguageVersion.of(17)) } }
                """));
        assertEquals("17", gradleLevel("""
                java { toolchain { languageVersion.convention(JavaLanguageVersion.of(17)) } }
                """));
    }

    @Test
    void gradleScopeWhoseDeclarationsDisagreeIsUnknown() throws Exception {
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                sourceCompatibility = 11
                """));
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                java { sourceCompatibility = JavaVersion.toVersion(findProperty('javaLevel')) }
                """));
        assertEquals("unknown", gradleLevel("""
                java {
                    toolchain {
                        languageVersion = legacy ? JavaLanguageVersion.of(8) : JavaLanguageVersion.of(17)
                    }
                }
                """));
        assertEquals("8", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                compileJava { sourceCompatibility = JavaVersion.VERSION_1_8 }
                targetCompatibility = sourceCompatibility
                """));
        // Groovy sets a property through a method of the same name, too.
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                sourceCompatibility(JavaVersion.VERSION_11)
                """));
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                sourceCompatibility JavaVersion.VERSION_11
                """));

        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'child'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    if (name.startsWith('legacy')) {
                        sourceCompatibility = JavaVersion.VERSION_1_6
                    } else {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                """);
        writeGradleModule("child", "");

        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot)
                .module("child").orElseThrow().javaVersion());
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
    void readsGradleSourceCompatibilityOneDotEightAsJavaEight() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                plugins { id 'java' }
                sourceCompatibility = 1.8
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.javaVersion());
        assertEquals("8", model.modules().getFirst().javaVersion());
    }

    @Test
    void readsGradleSourceCompatibilityFromNumbersStringsAndJavaVersion() throws Exception {
        assertGradleJavaVersion("8", "sourceCompatibility = '1.8'");
        assertGradleJavaVersion("8", "sourceCompatibility = \"1.8\"");
        assertGradleJavaVersion("11", "sourceCompatibility = '11'");
        assertGradleJavaVersion("11", "sourceCompatibility = 11");
        assertGradleJavaVersion("10", "sourceCompatibility = 1.10");
        assertGradleJavaVersion("8", "sourceCompatibility = '1.8.0'");
        assertGradleJavaVersion("11", "sourceCompatibility = 11.0");
        // Groovy reads 1_8 as 18: a level comes from the whole number or not at all.
        assertGradleJavaVersion("unknown", "sourceCompatibility = 1_8");
        assertGradleJavaVersion("8", "sourceCompatibility = JavaVersion.VERSION_1_8");
        assertGradleJavaVersion("8", "sourceCompatibility = JavaVersion.toVersion('1.8')");
        assertGradleJavaVersion("17", "java { sourceCompatibility = JavaVersion.toVersion(17) }");
    }

    @Test
    void gradleModulesShareSourceCompatibilityWrittenInDifferentForms() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'reports'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    sourceCompatibility = 1.8
                }
                """);
        Path billing = Files.createDirectories(projectRoot.resolve("billing"));
        Files.writeString(billing.resolve("build.gradle"),
                "sourceCompatibility = JavaVersion.VERSION_1_8");
        Files.createDirectories(projectRoot.resolve("reports"));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
        assertEquals("8", model.module("reports").orElseThrow().javaVersion());
    }

    @Test
    void doesNotReadGradleReleaseOneDotEightAsJavaOne() throws Exception {
        // options.release takes a whole number such as 8, never 1.8.
        assertGradleJavaVersion(
                "unknown", "tasks.withType(JavaCompile) { options.release = 1.8 }");
    }

    @Test
    void readsLongDottedGradleVersionWithoutOverflowingTheStack() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"),
                "sourceCompatibility = '1" + ".8".repeat(10_000) + "'\n");

        assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion());
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
    void detectsJavaVersionFromWindows1252GradleBuild() throws Exception {
        Files.write(projectRoot.resolve("build.gradle"), """
                plugins { id 'java' }
                // configuración
                java { sourceCompatibility = JavaVersion.VERSION_1_8 }
                """.getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(BuildSystem.GRADLE, model.buildSystem());
        assertEquals("8", model.javaVersion());
        assertEquals("8", model.modules().getFirst().javaVersion());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void detectsGradleModulesFromWindows1252SettingsAndModuleBuild() throws Exception {
        Files.write(projectRoot.resolve("settings.gradle"), """
                // módulos de catálogo
                include 'billing'
                """.getBytes(WINDOWS_1252));
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java' }");
        Path billing = Files.createDirectories(projectRoot.resolve("billing"));
        Files.write(billing.resolve("build.gradle"), """
                // compilación para Java 8
                sourceCompatibility = JavaVersion.VERSION_1_8
                """.getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
        assertEquals(List.of(), model.diagnostics());
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
    void readsPomThatDeclaresWindows1252WithoutTheJdkCharset() throws Exception {
        // Native images lack the windows-1252 charset, so jsrc decodes such a pom itself. The
        // byte 0x81, which windows-1252 leaves undefined, shows which decoder ran: the JDK
        // charset reads U+FFFD, SourceText U+0081. ISO-8859-1 writes 0x81 and every other
        // character here as windows-1252 does; "ï»¿" is written as EF BB BF, a UTF-8 byte
        // order mark.
        List<String> prologs = new ArrayList<>(List.of(
                "<?xml version=\"1.0\" encoding=\"windows-1252\"?>\n",
                "<?xml version='1.0' encoding='WINDOWS-1252' standalone='no'?>\n",
                "<?xml version=\"1.0\" encoding = \"Cp1252\" ?>\n",
                "ï»¿<?xml version=\"1.0\" encoding=\"windows-1252\"?>\n"));
        for (String alias : WINDOWS_1252.aliases()) {
            prologs.add("<?xml version=\"1.0\" encoding=\"" + alias + "\"?>\n");
        }
        for (String prolog : prologs) {
            Files.write(projectRoot.resolve("pom.xml"), (prolog + """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <!-- configuración -->
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId><artifactId>catálogo\u0081</artifactId>
                      <version>1</version>
                      <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                    </project>
                    """).getBytes(ISO_8859_1));

            ProjectModel model = new ProjectModelDetector().detect(projectRoot);

            assertEquals("8", model.javaVersion(), prolog);
            assertEquals("catálogo\u0081", model.modules().getFirst().name(), prolog);
            assertEquals(List.of(), model.diagnostics(), prolog);
        }
    }

    @Test
    void keepsMavenModuleWhosePomDeclaresWindows1252() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <modules><module>billing</module></modules>
                </project>
                """);
        Path billing = Files.createDirectories(projectRoot.resolve("billing"));
        Files.write(billing.resolve("pom.xml"), """
                <?xml version="1.0" encoding="windows-1252"?>
                <project>
                  <!-- módulo de catálogo -->
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  </parent>
                  <artifactId>billing</artifactId>
                  <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                </project>
                """.getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void rejectsDoctypeInPomThatDeclaresWindows1252() throws Exception {
        Path level = Files.writeString(projectRoot.resolve("level.txt"), "1.8");
        Files.write(projectRoot.resolve("pom.xml"), """
                <?xml version="1.0" encoding="windows-1252"?>
                <!-- configuración -->
                <!DOCTYPE project [<!ENTITY level SYSTEM "%s">]>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.source>&level;</maven.compiler.source></properties>
                </project>
                """.formatted(level.toUri()).getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertTrue(model.diagnostics().getFirst().message().contains("DOCTYPE"),
                model.diagnostics().toString());
    }

    @Test
    void leavesOtherDeclaredEncodingsToTheParser() throws Exception {
        // windows-1250 writes "č" as E8, which windows-1252 reads as "è".
        Files.write(projectRoot.resolve("pom.xml"), """
                <?xml version="1.0" encoding="windows-1250"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>účetnictví</artifactId><version>1</version>
                  <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                </project>
                """.getBytes(Charset.forName("windows-1250")));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("účetnictví", model.modules().getFirst().name());
        assertEquals("8", model.javaVersion());
        assertEquals(List.of(), model.diagnostics());
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

    @Test
    void detectsJavaVersionFromWindows1252PomThatDeclaresUtf8OrNoEncoding() throws Exception {
        // "\u00EF\u00BB\u00BF" is written as EF BB BF, a UTF-8 byte order mark.
        for (String prolog : List.of(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n",
                "<?xml version='1.0' encoding='utf-8' standalone='no'?>\n",
                "<?xml version=\"1.0\"?>\n",
                "",
                "\u00EF\u00BB\u00BF<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")) {
            Files.write(projectRoot.resolve("pom.xml"), (prolog + """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <!-- configuración -->
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId><artifactId>billing</artifactId><version>1</version>
                      <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                    </project>
                    """).getBytes(WINDOWS_1252));

            ProjectModel model = new ProjectModelDetector().detect(projectRoot);

            assertEquals("8", model.javaVersion(), prolog);
            assertEquals(List.of(), model.diagnostics(), prolog);
        }
    }

    @Test
    void rejectsDoctypeInWindows1252PomThatDeclaresUtf8() throws Exception {
        Path level = Files.writeString(projectRoot.resolve("level.txt"), "1.8");
        Files.write(projectRoot.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- configuración -->
                <!DOCTYPE project [<!ENTITY level SYSTEM "%s">]>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.source>&level;</maven.compiler.source></properties>
                </project>
                """.formatted(level.toUri()).getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("unknown", model.javaVersion());
        assertTrue(model.diagnostics().getFirst().message().contains("DOCTYPE"),
                model.diagnostics().toString());
    }

    @Test
    void keepsWindows1252MavenModuleThatDeclaresUtf8() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <modules><module>billing</module></modules>
                </project>
                """);
        Path billing = Files.createDirectories(projectRoot.resolve("billing"));
        Files.write(billing.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <!-- módulo de catálogo -->
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  </parent>
                  <artifactId>billing</artifactId>
                  <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                </project>
                """.getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void readsPomInTheEncodingItDeclaresOrSignals() throws Exception {
        String[][] encodings = {
                // Notepad's "Unicode" and Windows PowerShell 5 redirection write a byte order mark.
                {"UTF-16LE", "\uFEFF", "UTF-16", "catálogo"},
                {"UTF-16LE", "", "UTF-16", "catálogo"},
                {"IBM037", "", "IBM037", "catálogo"},
                // ISO-8859-15 writes "œ" as BD, which Windows-1252 would read as "½".
                {"ISO-8859-15", "", "ISO-8859-15", "œuvres"}};
        for (int i = 0; i < encodings.length; i++) {
            String[] encoding = encodings[i];
            Path project = Files.createDirectories(projectRoot.resolve("project" + i));
            Files.write(project.resolve("pom.xml"), (encoding[1] + """
                    <?xml version="1.0" encoding="%s"?>
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId><artifactId>%s</artifactId><version>1</version>
                      <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                    </project>
                    """.formatted(encoding[2], encoding[3])).getBytes(Charset.forName(encoding[0])));

            ProjectModel model = new ProjectModelDetector().detect(project);

            String label = String.join(" ", encoding);
            assertEquals(encoding[3], model.modules().getFirst().name(), label);
            assertEquals("8", model.javaVersion(), label);
            assertEquals(List.of(), model.diagnostics(), label);
        }
    }

    @Test
    void reportsMalformedPomWithoutPrintingParserErrors() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project>");
        var originalErr = System.err;
        var capturedErr = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(capturedErr));
        ProjectModel model;
        try {
            model = new ProjectModelDetector().detect(projectRoot);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals("BUILD_MODEL_PARTIAL", model.diagnostics().getFirst().code());
        assertEquals("", capturedErr.toString());
    }

    @Test
    void dropsMavenModuleWhosePomIsADevice() throws Exception {
        Path zero = Path.of("/dev/zero");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(zero), "No /dev/zero here");
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0.0</version>
                  <modules><module>zero</module></modules>
                </project>
                """);
        Files.createSymbolicLink(
                Files.createDirectories(projectRoot.resolve("zero")).resolve("pom.xml"), zero);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertTrue(model.module("zero").isEmpty());
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

    private String gradleLevel(String rootBuild) throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), rootBuild);
        return new ProjectModelDetector().detect(projectRoot).javaVersion();
    }

    private String kotlinGradleLevel(String rootBuild) throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), rootBuild);
        return new ProjectModelDetector().detect(projectRoot).javaVersion();
    }

    private void writeGradleModule(String module, String body) throws Exception {
        Path directory = Files.createDirectories(projectRoot.resolve(module));
        Files.writeString(directory.resolve("build.gradle.kts"), "plugins { java }\n" + body);
    }

    private void assertGradleJavaVersion(String expected, String declaration) throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"),
                "plugins { id 'java' }\n" + declaration + "\n");
        assertEquals(expected, new ProjectModelDetector().detect(projectRoot).javaVersion(),
                declaration);
    }

    @Test
    void braceInAGradleStringDoesNotEndTheBlockAroundIt() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        Files.createDirectories(projectRoot.resolve("billing"));
        // A closing brace in a string of any form, before the level or right before the closing
        // brace of the block, does not end the project block.
        for (String declaration : List.of(
                "ext.pattern = '}'",
                "ext.pattern = \"}\"",
                "ext.pattern = 'it\\'s }'",
                "ext.pattern = \"${'}'}\"",
                "ext.pattern = '''it's }'''",
                "ext.pattern = \"\"\"say \"}\" \"\"\"")) {
            Files.writeString(projectRoot.resolve("build.gradle"), """
                    subprojects { apply plugin: 'java' }
                    project(':billing') {
                        %s
                        sourceCompatibility = JavaVersion.VERSION_11
                        %s}
                    apply plugin: 'java'
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    """.formatted(declaration, declaration));

            ProjectModel model = new ProjectModelDetector().detect(projectRoot);

            assertEquals("8", model.javaVersion(), declaration);
            assertEquals("11", model.module("billing").orElseThrow().javaVersion(), declaration);
        }

        // An opening brace in a string does not keep the shared block open.
        for (String declaration : List.of(
                "description = '{'", "description = \"{\"", "description = '''{'''")) {
            Files.writeString(projectRoot.resolve("build.gradle"), """
                    subprojects {
                        apply plugin: 'java'
                        %s
                        sourceCompatibility = JavaVersion.VERSION_1_8
                    }
                    """.formatted(declaration));

            ProjectModel model = new ProjectModelDetector().detect(projectRoot);

            assertEquals("unknown", model.javaVersion(), declaration);
            assertEquals("8", model.module("billing").orElseThrow().javaVersion(), declaration);
        }

        // Nor does a parenthesis in a string end the selector of a configure(...) call, whose
        // block then configures some subprojects, not the root.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                configure(subprojects.findAll { it.name == 'a)' }) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);

        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot).javaVersion());
    }

    @Test
    void readsRepeatedGradleProjectBlocksInLinearTime() throws Exception {
        // Joining the bodies of one project block after block copied them all each time, which
        // took more than a minute for this build.
        assertGradleProjectBlocksReadInLinearTime(":x");
    }

    @Test
    void readsGradleProjectBlocksThatShareADirectoryInLinearTime() throws Exception {
        // Each path is different but all of them resolve to the directory of x.
        assertGradleProjectBlocksReadInLinearTime(":p%d:..:x");
    }

    private void assertGradleProjectBlocksReadInLinearTime(String pathFormat) throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'x'");
        Files.createDirectories(projectRoot.resolve("x"));
        StringBuilder build = new StringBuilder("apply plugin: 'java'\n");
        for (int index = 0; index < 200_000; index++) {
            build.append("project('").append(pathFormat.formatted(index))
                    .append("') { sourceCompatibility = JavaVersion.VERSION_11 }\n");
        }
        Files.writeString(projectRoot.resolve("build.gradle"), build);

        ProjectModel model = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(30),
                () -> new ProjectModelDetector().detect(projectRoot));

        assertEquals("11", model.module("x").orElseThrow().javaVersion());
    }

    @Test
    void doesNotKeepModuleLevelThatAStrongerKindInTheRootBuildOverrides() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'b'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    compileJava.options.release = 8
                }
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                """);
        writeGradleModule("b", "java { sourceCompatibility = JavaVersion.VERSION_17 }");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        // Gradle compiles b with release 8, which wins over every source level, and the root
        // blocks do not agree on one level for b to be read from.
        assertEquals("21", model.javaVersion());
        assertEquals("unknown", model.module("b").orElseThrow().javaVersion());

        // The same holds when allprojects sets the release and subprojects the source level.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    compileJava.options.release = 8
                }
                subprojects { java.sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // A project block counts like the shared ones.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                subprojects { java.sourceCompatibility = JavaVersion.VERSION_11 }
                project(':b') { compileJava.options.release = 8 }
                """);
        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // A source level wins over a toolchain.
        writeGradleModule("b",
                "java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                subprojects { java.sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        assertEquals("unknown", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());
    }

    @Test
    void keepsModuleLevelWhenRootBlocksDisagreeOnTheSameKindOrAWeakerOne() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'b'");
        writeGradleModule("b", "java { sourceCompatibility = JavaVersion.VERSION_17 }");

        // The module's build file runs after the root blocks, so its level stands when they
        // disagree on the same kind of declaration,
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                subprojects { java.sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        assertEquals("17", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // when one of them cannot be read,
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                subprojects {
                    java.sourceCompatibility = JavaVersion.toVersion(findProperty('level') ?: 11)
                }
                """);
        assertEquals("17", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // when the only one cannot be read,
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.toVersion(findProperty('level') ?: 21)
                }
                """);
        assertEquals("17", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // or when they only disagree on a weaker kind.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.toolchain.languageVersion = JavaLanguageVersion.of(21)
                }
                subprojects { java.toolchain.languageVersion = JavaLanguageVersion.of(11) }
                """);
        assertEquals("17", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());

        // A stronger kind in the module itself wins over them all.
        writeGradleModule("b",
                "tasks.withType<JavaCompile>().configureEach { options.release.set(11) }");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    java.sourceCompatibility = JavaVersion.VERSION_21
                }
                subprojects { java.sourceCompatibility = JavaVersion.VERSION_1_8 }
                """);
        assertEquals("11", new ProjectModelDetector().detect(projectRoot)
                .module("b").orElseThrow().javaVersion());
    }
}
