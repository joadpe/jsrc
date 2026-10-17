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
    void ignoresCommentedOutGradleIncludes() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), """
                include 'a'
                /*
                include 'legacy'
                */
                include 'b' // , 'old'
                includeBuild 'tools'
                /* moved out of tools: */ include 'c'
                """);
        // Old code kept beside the build, which a phantom module would index.
        for (String directory : List.of("a", "legacy", "b", "old", "tools", "c")) {
            Files.createDirectories(projectRoot.resolve(directory).resolve("src/main/java"));
        }

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("a", "b", "c"),
                model.modules().stream().map(ProjectModule::name).toList());
    }

    @Test
    void ignoresGradleSettingsCallsThatOnlyStartWithInclude() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle.kts"), """
                pluginManagement {
                    includeBuild("build-logic")
                }
                dependencyResolutionManagement {
                    repositories {
                        maven {
                            url = uri("https://repo.example.com/maven2")
                            content {
                                includeGroup("com.example")
                                includeModule("org.example", "client")
                            }
                        }
                    }
                }
                include(":app")
                includeFlat("shared")
                includeBuild("../platform")
                """);
        writeGradleModule("app", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("app"), model.modules().stream().map(ProjectModule::name).toList());
        assertTrue(model.diagnostics().isEmpty());
    }

    @Test
    void readsGradleIncludesThroughABuildsOwnHelper() throws Exception {
        // JUnit 5 and AndroidX declare their projects through such a helper.
        Files.writeString(projectRoot.resolve("settings.gradle"), """
                def includeProject(String name) {
                    include(name)
                }
                includeProject 'api'
                includeProject(':core')
                includeBuild 'build-logic'
                includeFlat 'shared'
                """);
        writeGradleModule("api", "");
        writeGradleModule("core", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("api", "core"),
                model.modules().stream().map(ProjectModule::name).toList());
    }

    @Test
    void readsGradleIncludeListsThatSpanLines() throws Exception {
        assertEquals(List.of("app", "core", "legacy/billing"),
                gradleModulePaths("settings.gradle.kts", """
                        include(
                            ":app",
                            ":core", // shared code
                            /* ":old",
                            ":older", */ ":legacy:billing",
                        )
                        """));
        assertEquals(List.of("app", "core", "legacy/billing", "legacy/reports", "tools", "docs"),
                gradleModulePaths("settings.gradle", """
                        include 'app',
                                'core'
                        include 'legacy:billing', // moved from the old tree
                                'legacy:reports'
                        include('tools',
                                'docs')
                        """));
        // A list that is never closed counts its first line only, and a call left with a
        // trailing comma ends where the next include starts.
        assertEquals(List.of("app", "core", "docs"),
                gradleModulePaths("settings.gradle.kts", """
                        include(":app", ":core",
                            ":unclosed"
                        include(":docs")
                        """));
        assertEquals(List.of("app", "core", "docs"),
                gradleModulePaths("settings.gradle", """
                        include 'app', 'core',
                        include 'docs'
                        """));
    }

    @Test
    void readsGradleIncludesThatDoNotStartALine() throws Exception {
        assertEquals(List.of("app", "docs", "tools", "reports"),
                gradleModulePaths("settings.gradle", """
                        rootProject.name = 'shop'; include 'app'
                        if (hasProperty('withDocs')) { include 'docs' }
                        include 'tools' /* a comment that spans
                        lines */ include 'reports'
                        """));
        assertEquals(List.of("app", "docs", "tools", "reports"),
                gradleModulePaths("settings.gradle.kts", """
                        rootProject.name = "shop"; include(":app")
                        if (file("docs").isDirectory) { include(":docs") }
                        include(":tools") /* a comment that spans
                        lines */ include(":reports")
                        """));
    }

    @Test
    void readsOnlyLiteralArgumentsOfGradleIncludes() throws Exception {
        // Strings after the call, templates, concatenations and variables name no project.
        assertEquals(List.of("app", "core"), gradleModulePaths("settings.gradle", """
                include 'app'; rootProject.name = 'shop'
                include 'core' /* a comment that spans
                lines */ includeBuild 'build-logic'
                include "plugins:${pluginName}"
                file('modules').eachDir { include "modules:${it.name}" }
                ['tools'].each { include 'modules:' + it }
                """));
        assertEquals(List.of("app", "core"), gradleModulePaths("settings.gradle.kts", """
                include(":app"); rootProject.name = "shop"
                include(":core") /* a comment that spans
                lines */ includeBuild("build-logic")
                include(":plugins:$pluginName")
                file("modules").listFiles()?.forEach { include(":modules:${it.name}") }
                listOf("tools").forEach { include(":modules:" + it) }
                listOf("docs").forEach { include(it) }
                """));
    }

    @Test
    void readsGradleIncludeHelperListsThatSpanLines() throws Exception {
        // The repository filters of a settings script only start with include.
        assertEquals(List.of("api", "core", "docs"), gradleModulePaths("settings.gradle.kts", """
                fun includeProject(name: String, published: Boolean = false) {
                    include(name)
                }
                includeProject("api")
                includeProject(":core", published = true)
                includeProject(
                    ":docs",
                )
                includeBuild("build-logic")
                dependencyResolutionManagement {
                    repositories {
                        maven {
                            content {
                                includeGroup("com.example")
                                includeGroupByRegex("com.example.*")
                                includeGroupAndSubgroups("org.example")
                                includeModule("org.example", "client")
                                includeModuleByRegex("org.example", "client-.*")
                                includeVersion("org.example", "client", "1.0")
                                includeVersionByRegex("org.example", "client", "1.*")
                            }
                        }
                    }
                }
                """));
    }

    /** The module directories, relative to the project, that a settings script includes. */
    private List<String> gradleModulePaths(String settingsFile, String settings)
            throws Exception {
        Path project = Files.createTempDirectory(projectRoot, "settings");
        Files.writeString(project.resolve(settingsFile), settings);
        ProjectModel model = new ProjectModelDetector().detect(project);
        return model.modules().stream()
                .map(module -> model.root().relativize(module.path()).toString().replace('\\', '/'))
                .toList();
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
    void doesNotReportGradleBuildThatOnlyReadsSourceSets() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                plugins { id 'java' }
                java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
                tasks.register('runApp', JavaExec) {
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'com.example.App'
                }
                tasks.register('fatJar', Jar) {
                    from sourceSets.main.output
                }
                tasks.register('integrationTest', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                }
                javadoc {
                    source = sourceSets.main.allJava
                    classpath = sourceSets.main.compileClasspath
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of(), model.diagnostics());
        assertEquals(
                List.of(projectRoot.resolve("src/main/java")),
                model.modules().getFirst().mainSourceRoots());
    }

    @Test
    void doesNotReportKotlinGradleBuildThatOnlyReadsSourceSets() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle.kts"), """
                plugins { java }
                tasks.register<Jar>("fatJar") {
                    from(sourceSets["main"].output)
                }
                tasks.register<JavaExec>("runApp") {
                    classpath = sourceSets.main.get().runtimeClasspath
                }
                tasks.register<Test>("integrationTest") {
                    testClassesDirs = sourceSets.getByName("test").output.classesDirs
                    classpath = sourceSets.named("test").get().runtimeClasspath
                }
                tasks.register<Jar>("sourcesJar") {
                    from(sourceSets.main.map { it.allSource })
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void doesNotReportGradleSourceSetChangesThatLeaveJavaSourcesAlone() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), """
                plugins { id 'java' }
                sourceSets.test.compileClasspath += configurations.compileOnly
                sourceSets.main.output.resourcesDir = file("$buildDir/classes/java/main")
                sourceSets.main.resources.exclude '**/*.bak'
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void reportsDynamicSourceSetsOnlyForTheGradleModuleThatConfiguresThem() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'app', 'lib'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                subprojects {
                    apply plugin: 'java'
                    tasks.register('runApp', JavaExec) {
                        classpath = sourceSets.main.runtimeClasspath
                    }
                }
                """);
        writeBuildFile("app/build.gradle", "jar { from sourceSets.main.allSource }\n");
        writeBuildFile("lib/build.gradle", """
                sourceSets {
                    main {
                        java { exclude '**/Legacy*.java' }
                    }
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of(dynamicSourceSets("lib")), model.diagnostics());
    }

    @Test
    void reportsGradleSourceSetConfigurationWithoutSourceDirectories() throws Exception {
        writeBuildFile("block/build.gradle", """
                sourceSets {
                    main { java { exclude '**/Legacy*.java' } }
                }
                """);
        writeBuildFile("member/build.gradle", "sourceSets.main.java.exclude 'legacy/**'\n");
        writeBuildFile("created/build.gradle", "sourceSets.create('integrationTest')\n");
        writeBuildFile("all/build.gradle", "sourceSets.all { java.exclude 'legacy/**' }\n");
        writeBuildFile("closure/build.gradle.kts", """
                sourceSets.main { java.setSrcDirs(listOf("src")) }
                """);
        writeBuildFile("indexed/build.gradle.kts", """
                sourceSets["main"].java.exclude("legacy/**")
                """);
        writeBuildFile("named/build.gradle.kts", """
                sourceSets.named("main") { java.exclude("legacy/**") }
                """);
        writeBuildFile("delegated/build.gradle.kts", """
                val integrationTest by sourceSets.creating
                """);
        List<String> modules = List.of(
                "block", "member", "created", "all", "closure", "indexed", "named", "delegated");
        Files.writeString(projectRoot.resolve("settings.gradle"), "include "
                + String.join(", ", modules.stream().map(module -> "'" + module + "'").toList()));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(
                modules.stream().map(this::dynamicSourceSets).toList(), model.diagnostics());
    }

    @Test
    void reportsGradleSourceSetBlocksOnlyWhenTheyChangeJavaSources() throws Exception {
        writeBuildFile("output/build.gradle", """
                sourceSets {
                    main {
                        output.dir(generatedResources, builtBy: 'generateResources')
                    }
                    test {
                        compileClasspath += configurations.compileOnly
                    }
                }
                """);
        writeBuildFile("bare/build.gradle", """
                sourceSets {
                    test
                }
                """);
        writeBuildFile("excluded/build.gradle", """
                sourceSets {
                    test { java { exclude '**/Slow*.java' } }
                }
                """);
        writeBuildFile("extra/build.gradle", """
                sourceSets {
                    main { output.dir(generatedResources) }
                    intTest {
                        compileClasspath += sourceSets.main.output
                    }
                }
                """);
        writeBuildFile("declared/build.gradle", "sourceSets { integrationTest }\n");
        writeBuildFile("named/build.gradle.kts", """
                sourceSets.named("intTest") { compileClasspath += sourceSets.main.get().output }
                """);
        writeBuildFile("spread/build.gradle", "sourceSets*.java*.exclude 'legacy/**'\n");
        List<String> modules = List.of(
                "output", "bare", "excluded", "extra", "declared", "named", "spread");
        Files.writeString(projectRoot.resolve("settings.gradle"), "include "
                + String.join(", ", modules.stream().map(module -> "'" + module + "'").toList()));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(
                List.of("excluded", "extra", "declared", "named", "spread").stream()
                        .map(this::dynamicSourceSets).toList(),
                model.diagnostics());
    }

    @Test
    void ignoresGradleSourceSetsAndSourceDirectoriesInComments() throws Exception {
        Files.writeString(
                projectRoot.resolve("settings.gradle"), "include 'commented', 'configured'");
        writeBuildFile("commented/build.gradle", """
                // sourceSets { main { java { exclude 'legacy/**' } } }
                /*
                sourceSets.main.java.exclude 'legacy'
                */
                """);
        writeBuildFile("configured/build.gradle", """
                sourceSets {
                    main {
                        java { exclude 'legacy/' } // was srcDirs = ['src/old/java']
                    }
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of(dynamicSourceSets("configured")), model.diagnostics());
    }

    private ProjectDiagnostic dynamicSourceSets(String module) {
        return new ProjectDiagnostic(
                "BUILD_MODEL_PARTIAL",
                "Dynamic Gradle source-set configuration in " + projectRoot.resolve(module));
    }

    private void writeBuildFile(String path, String content) throws Exception {
        Path file = projectRoot.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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

    @Test
    void readsTaskListHeaderWithLongWhitespaceRunInBoundedTime() throws Exception {
        assertGradleLevelInBoundedTime("11",
                "configure([compileTestJava" + " ".repeat(5_000) + ", compileJava]) {\n"
                        + "    options.release = 11\n"
                        + "}\n");
    }

    @Test
    void readsConfigureHeaderWithLongWhitespaceRunInBoundedTime() throws Exception {
        assertGradleLevelInBoundedTime("11",
                "configure(" + " ".repeat(150_000) + "[compileJava]) {\n"
                        + "    options.release = 11\n"
                        + "}\n");
    }

    @Test
    void readsNamedTaskHeaderWithLongWhitespaceRunInBoundedTime() throws Exception {
        assertGradleLevelInBoundedTime("11",
                "tasks.named" + " ".repeat(150_000) + "(\"compileJava\") {\n"
                        + "    options.release = 11\n"
                        + "}\n");
    }

    @Test
    void readsSourceSetLookupWithLongWhitespaceRunInBoundedTime() throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"),
                "sourceSets.named" + " ".repeat(150_000)
                        + "(sourceSetName) { java.exclude('legacy/**') }\n");

        ProjectModel model = detectInBoundedTime();

        assertEquals("BUILD_MODEL_PARTIAL", model.diagnostics().getFirst().code());
    }

    @Test
    void readsSourceSetClosureWithLongWhitespaceRunInBoundedTime() throws Exception {
        assertGradleLevelInBoundedTime("11",
                "sourceCompatibility = 11\n"
                        + "sourceSets.main.configure" + " ".repeat(150_000) + "(closure)\n");
    }

    private void assertGradleLevelInBoundedTime(String expected, String build) throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"), "plugins { id 'java' }\n" + build);
        assertEquals(expected, detectInBoundedTime().javaVersion());
    }

    private ProjectModel detectInBoundedTime() {
        return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(20),
                () -> new ProjectModelDetector().detect(projectRoot));
    }

    @Test
    void readsGradleSourceRootsFromCodeAndNotFromComments() throws Exception {
        Files.writeString(
                projectRoot.resolve("settings.gradle"), "include 'commented', 'declared'");
        writeBuildFile("commented/build.gradle", """
                // sourceSets.main.java.srcDirs = ['generated/java']
                /* sourceSets.test.java.srcDirs = ['generated/test'] */
                plugins { id 'java' }
                """);
        writeBuildFile("declared/build.gradle", """
                // sourceSets.main.java.srcDirs = ['old/java']
                /* sourceSets.test.java.srcDirs = ['old/test'] */
                sourceSets {
                    main.java.srcDirs = ['src/core/java']
                    test.java.srcDirs = ['src/spec/java']
                }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        ProjectModule commented = model.module("commented").orElseThrow();
        assertEquals(
                List.of(projectRoot.resolve("commented/src/main/java")),
                commented.mainSourceRoots());
        assertEquals(
                List.of(projectRoot.resolve("commented/src/test/java"),
                        projectRoot.resolve("commented/src/testFixtures/java")),
                commented.testSourceRoots());
        ProjectModule declared = model.module("declared").orElseThrow();
        assertEquals(
                List.of(projectRoot.resolve("declared/src/core/java")),
                declared.mainSourceRoots());
        assertEquals(
                List.of(projectRoot.resolve("declared/src/spec/java")),
                declared.testSourceRoots());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void readsGradleLevelNextToEmptyAndSlashStarSlashBlockComments() throws Exception {
        assertEquals("17", gradleLevel("sourceCompatibility = /**/ 17\n"));
        // "/*/" opens a comment: it does not close itself.
        assertEquals("11", gradleLevel("/*/ sourceCompatibility = 8\n*/\nsourceCompatibility = 11\n"));
    }

    @Test
    void readsGradleLevelWhenASlashIsNoCommentMarker() throws Exception {
        // A division is code, so the block comment after the level is not its other end.
        assertEquals("11", gradleLevel(
                "def half = 10 / 2\nsourceCompatibility = 11\n/* note */\n"));
    }

    @Test
    void readsGradleLevelBeforeABlockCommentThatNeverCloses() throws Exception {
        // A /* that nothing closes is code, and the scan must still end.
        for (String tail : List.of("/* never closed\n", "/*/\n", "/*")) {
            String build = "sourceCompatibility = 11\n" + tail;
            assertEquals("11", org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(10), () -> gradleLevel(build)), build);
        }
    }

    @Test
    void readsGradleLevelBeforeALineCommentWithoutAFinalLineBreak() throws Exception {
        assertEquals("11", gradleLevel("sourceCompatibility = 11\n// end"));
        // The comment ends at a bare carriage return too.
        assertEquals("11", gradleLevel("// header\rsourceCompatibility = 11\r"));
    }

    @Test
    void stripsGradleCommentsAfterEmptyStringsAndEscapedBackslashes() throws Exception {
        for (String quote : List.of("\"", "'")) {
            // Two empty strings must not turn the lines between them into one string.
            assertEquals("11", gradleLevel("version = " + quote + quote + "\n"
                    + "// sourceCompatibility = 6\n"
                    + "description = " + quote + quote + "\n"
                    + "sourceCompatibility = 11\n"), quote);
        }
        // An empty string closes at once, so the apostrophe in the comment pairs with nothing.
        assertEquals("11", gradleLevel(
                "def a = '' // it's sourceCompatibility = 6\nsourceCompatibility = 11\n"));
        // An escaped backslash right before the closing quote does not escape that quote.
        assertEquals("11", gradleLevel(
                "def a = '\\\\' // sourceCompatibility = 6 '\nsourceCompatibility = 11\n"));
    }

    @Test
    void stripsGradleCommentsAfterUnclosedTripleQuotesAndLongQuoteRuns() throws Exception {
        // A triple quote that never closes, as in a slashy string, opens no string.
        assertEquals("11", gradleLevel(
                "def r = /\"\"\"/\n// sourceCompatibility = 6\nsourceCompatibility = 11\n"));
        // A string ends with the last quote of a run, so none is left to pair with the comment's.
        assertEquals("11", gradleLevel(
                "val q = \"\"\"\"a\"\"\"\" // \"sourceCompatibility = 6\"\nsourceCompatibility = 11\n"));
        // A script may end right after a triple-quoted string, or begin with a string.
        assertEquals("11", gradleLevel("sourceCompatibility = 11\ndef q = \"\"\"text\"\"\""));
        assertEquals("11", gradleLevel("\"x\"\nsourceCompatibility = 11\n"));
    }

    @Test
    void keepsAContinuedGradleStringWholeAndEndsAnUnpairedQuoteWithItsLine() throws Exception {
        // A backslash before a line break continues the string, so its // is no comment.
        assertEquals("11", gradleLevel("def s = 'a \\\r\n// b'; sourceCompatibility = 11\r\n"));
        // An apostrophe that opens no string, as in a slashy string, stops at the end of its line.
        assertEquals("11", gradleLevel(
                "def a = /it's/\n// sourceCompatibility = 6 '\nsourceCompatibility = 11\n"));
        assertEquals("11", gradleLevel(
                "def a = /it's/\r// sourceCompatibility = 6 '\rsourceCompatibility = 11\r"));
    }

    @Test
    void readsGradleModuleBuildAfterAByteOrderMark() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        Path billing = Files.createDirectories(projectRoot.resolve("billing"));
        // The mark must not hide that compileTestJava is not the main compilation.
        Files.writeString(billing.resolve("build.gradle"), "\uFEFF" + """
                compileTestJava { sourceCompatibility = JavaVersion.VERSION_1_6 }
                sourceCompatibility = JavaVersion.VERSION_1_8
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void readsAccentedGradleModuleNamesFromAWindows1252Settings() throws Exception {
        Path module = null;
        try {
            module = Files.createDirectories(projectRoot.resolve("caf\u00E9"));
        } catch (java.nio.file.InvalidPathException exception) {
            // The file system cannot name it: assumed away below.
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(module != null,
                "the file system cannot name a module with an accent");
        Files.write(projectRoot.resolve("settings.gradle"),
                "include 'caf\u00E9'\n".getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals(List.of("caf\u00E9"),
                model.modules().stream().map(ProjectModule::name).toList());
        assertEquals(List.of(), model.diagnostics());
    }

    @Test
    void readsGradleIncludesWrittenWithDoubleQuotesTabsAndCarriageReturns() throws Exception {
        assertEquals(List.of("app", "core", "docs"), gradleModulePaths("settings.gradle", """
                include "app"
                include "core", "docs"
                """));
        assertEquals(List.of("app", "tools"), gradleModulePaths("settings.gradle",
                "include\t'app'\nif (true) {\n\tinclude 'tools'\n}\n"));
        // A Windows checkout ends its lines in CR LF.
        assertEquals(List.of("app", "core"), gradleModulePaths("settings.gradle",
                "include 'app',\r\n        'core'\r\n"));
        assertEquals(List.of("app", "core"), gradleModulePaths("settings.gradle.kts",
                "include(\r\n    \":app\",\r\n    \":core\"\r\n)\r\n"));
    }

    @Test
    void readsGradleIncludesOnlyWhereTheyStartAStatement() throws Exception {
        assertEquals(List.of("a", "y"), gradleModulePaths("settings.gradle", """
                include 'a'
                files.include 'b'
                foo include 'c'
                include
                'd'
                def text = \"\"\"
                include 'x'
                \"\"\"
                include 'y'
                """));
        // A call without arguments, or an identifier, may be the last thing in the file.
        assertEquals(List.of("a"), gradleModulePaths("settings.gradle", "include 'a'\ninclude"));
        assertEquals(List.of("a"), gradleModulePaths("settings.gradle", "include 'a'\nfoo"));
    }

    @Test
    void readsGradleIncludeListsWithNestedCallsAndUnclosedLists() throws Exception {
        assertEquals(List.of("a", "b", "c"), gradleModulePaths("settings.gradle.kts",
                "include(\":a\", listOf(\":b\").first(), \":c\")\n"));
        assertEquals(List.of("a", "b"), gradleModulePaths("settings.gradle.kts",
                "include(\":a\", settings.include, \":b\")\n"));
        // A list that is never closed, and has nothing on its first line, adds nothing.
        assertEquals(List.of("c"), gradleModulePaths("settings.gradle.kts",
                "include(\n    \":a\",\n    \":b\"\ninclude(\":c\")\n"));
    }

    @Test
    void doesNotReadGradleIncludeArgumentsThatAreConcatenatedBlankOrUnclosed() throws Exception {
        // Without a literal module the build is a single project at the root.
        List<String> root = List.of("");
        assertEquals(root, gradleModulePaths("settings.gradle", "include 'a' + 'b'\n"));
        assertEquals(root, gradleModulePaths("settings.gradle.kts", "include(\":a\" +\n    \":b\")\n"));
        assertEquals(root, gradleModulePaths("settings.gradle.kts", "include(\":a\"\n    + \":b\")\n"));
        assertEquals(root, gradleModulePaths("settings.gradle", "include ''\ninclude ' '\ninclude 'a\n"));
    }

    @Test
    void readsGradleIncludesWhoseArgumentsAreVariablesTripleQuotedOrHoldDollarSigns() throws Exception {
        assertEquals(List.of("a", "b", "c$d"), gradleModulePaths("settings.gradle", """
                include name, 'a'
                include '''b'''
                include 'c$d'
                include "${prefix}-core"
                include "$other"
                """));
        // A dollar sign that starts no template belongs to the path, as in Kotlin.
        assertEquals(List.of("e$", "f$1"), gradleModulePaths("settings.gradle.kts",
                "include(\"e$\", \"f$1\")\n"));
    }

    @Test
    void doesNotReadAReferenceToTheGradleLevelAsADeclaration() throws Exception {
        // targetCompatibility = sourceCompatibility reads the level, whatever follows it.
        assertEquals("11", gradleLevel("""
                sourceCompatibility = 11
                targetCompatibility = sourceCompatibility
                version = '1.0'
                """));
        assertEquals("11", gradleLevel("""
                if (sourceCompatibility == JavaVersion.VERSION_1_8) { println 'old' }
                sourceCompatibility = 11
                """));
        // The method form with a literal sets the level, but its value is not read.
        assertEquals("unknown", gradleLevel("""
                sourceCompatibility '1.8'
                sourceCompatibility = 11
                """));
    }

    @Test
    void prefersGradleSourceCompatibilityOverTheToolchainOfTheSameScope() throws Exception {
        assertEquals("11", gradleLevel("""
                java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
                sourceCompatibility = JavaVersion.VERSION_11
                """));
    }

    @Test
    void ignoresGradleLevelsOfACompileTaskConditionWhereverItsStatementStarts() throws Exception {
        String main = "sourceCompatibility = JavaVersion.VERSION_1_8\n";
        String test = "if (name == 'compileTestJava') sourceCompatibility = JavaVersion.VERSION_1_6";
        for (String build : List.of(
                "tasks.withType(JavaCompile) { " + test + " }\n" + main,
                "println 'x'; " + test + "\n" + main,
                "repositories { mavenCentral() } " + test + "\n" + main,
                "println 'x'\n" + test + "\n" + main,
                "tasks.withType(JavaCompile) {\n    if (name == 'compileTestJava')\n    {\n"
                        + "        sourceCompatibility = JavaVersion.VERSION_1_6\n    }\n}\n" + main,
                "afterEvaluate { eclipse { jdt { sourceCompatibility = JavaVersion.VERSION_1_6 } } }\n"
                        + main)) {
            assertEquals("8", gradleLevel(build), build);
        }
    }

    @Test
    void keepsAGradleLevelOpenAcrossBlanksAfterTheEqualsSign() throws Exception {
        assertEquals("11", gradleLevel("sourceCompatibility = \n    JavaVersion.VERSION_11\n"));
    }

    @Test
    void readsGradleLevelsOfBuildsWithUnclosedBlocksAndCalls() throws Exception {
        // A block or call that is never closed does not hide the level declared before it.
        assertEquals("8", gradleLevel("""
                sourceCompatibility = JavaVersion.VERSION_1_8
                eclipse { def text = 1
                """));
        assertEquals("11", gradleLevel("sourceCompatibility = 11\nconfigure(\n"));
    }

    @Test
    void doesNotApplyGradleConfigureOfSomeProjectsToTheRoot() throws Exception {
        // allprojects.findAll { ... } may leave the root out, so it does not set the root level.
        assertEquals("unknown", gradleLevel("""
                configure(allprojects.findAll { it.name == 'x' }) {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }
                """));
        // The root keeps its own level when only some subprojects are configured.
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                sourceCompatibility = JavaVersion.VERSION_1_8
                configure(subprojects.findAll { it.name != 'docs' }) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);
        writeGradleModule("billing", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("8", model.javaVersion());
        assertEquals("unknown", model.module("billing").orElseThrow().javaVersion());
    }

    @Test
    void appliesGradleProjectBlocksInAnyOrderAndToNestedProjectPaths() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'libs:core'");
        writeGradleModule("billing", "");
        writeGradleModule("libs/core", "");
        // A project block may come before the subprojects block, name a path without the leading
        // colon, name a nested path, and be split in two.
        Files.writeString(projectRoot.resolve("build.gradle"), """
                project('billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                project(':libs:core') { sourceCompatibility = JavaVersion.VERSION_17 }
                project(':billing') { apply plugin: 'java' }
                subprojects { apply plugin: 'java' }
                """);

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("11", model.module("billing").orElseThrow().javaVersion());
        assertEquals("17", model.module("core").orElseThrow().javaVersion());
    }

    @Test
    void appliesGradleConfigureBlocksToProjectsListedOnePerLine() throws Exception {
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing', 'reports'");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                configure([
                    project(':billing'),
                    project(':reports'),
                ]) {
                    sourceCompatibility = JavaVersion.VERSION_11
                }
                """);
        writeGradleModule("billing", "");
        writeGradleModule("reports", "");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        assertEquals("11", model.module("billing").orElseThrow().javaVersion());
        assertEquals("11", model.module("reports").orElseThrow().javaVersion());
    }

    @Test
    void ignoresGradleLevelsOfEveryFormOfIdeAndOtherCompileTaskConfiguration() throws Exception {
        String other = "sourceCompatibility = JavaVersion.VERSION_1_6";
        for (String configuration : List.of(
                "eclipse { " + other + " }",
                "idea { module { " + other + " } }",
                "idea { " + other + " }",
                "jdt { " + other + " }",
                "compileGroovy { " + other + " }",
                "compileScala { " + other + " }",
                "compileKotlin { " + other + " }",
                "compileIntegrationTestJava { " + other + " }",
                "eclipse." + other,
                "eclipse.jdt." + other,
                "idea.project." + other,
                "jdt." + other,
                "compileTestJava." + other,
                "tasks.getByName('compileTestJava') { " + other + " }",
                "tasks.named('compileTestJava', JavaCompile) { " + other + " }",
                "tasks.named('compileTestJava').configure { " + other + " }",
                "tasks.getByName('compileTestJava').configure { " + other + " }",
                "configure([tasks.compileTestJava, tasks.compileTestGroovy]) { " + other + " }",
                "configure([compileTestJava, compileTestGroovy,]) { " + other + " }",
                "configure(compileTestJava) { " + other + " }",
                "configure(tasks.compileTestJava) { " + other + " }",
                "if (name == 'compileJava') {\n} else if (name == 'compileTestJava') {\n    " + other
                        + "\n}",
                "if ('compileTestJava' == name) " + other,
                "if (name.equals('compileTestJava')) " + other,
                "tasks.withType(JavaCompile) { if (it.name == 'compileTestJava') " + other + " }",
                "tasks.register('x') { " + other + " }",
                "tasks.create('x') { " + other + " }",
                "task foo { " + other + " }",
                "task foo(type: JavaCompile) { " + other + " }",
                "task(foo) { " + other + " }",
                "tasks.withType(JavaExec) { javaLauncher = javaToolchains.launcherFor { "
                        + "languageVersion = JavaLanguageVersion.of(6) } }",
                "javadoc { javadocTool = javaToolchains.javadocToolFor { "
                        + "languageVersion = JavaLanguageVersion.of(6) } }")) {
            String build = configuration + "\nsourceCompatibility = JavaVersion.VERSION_1_8\n";
            assertEquals("8", gradleLevel(build), build);
        }
    }

    @Test
    void ignoresKotlinGradleLevelsOfEveryFormOfOtherCompileTaskConfiguration() throws Exception {
        String other = "sourceCompatibility = JavaVersion.VERSION_1_6";
        for (String configuration : List.of(
                "tasks.named<JavaCompile>(\"compileTestJava\") { " + other + " }",
                "when (name) {\n    \"compileTestJava\" -> " + other + "\n}",
                "val x by tasks.registering { " + other + " }",
                "val y by tasks.creating { " + other + " }")) {
            String build = configuration + "\nsourceCompatibility = JavaVersion.VERSION_1_8\n";
            assertEquals("8", kotlinGradleLevel(build), build);
        }
    }

    @Test
    void readsGradleLevelOfConfigureBlocksThatSelectTasksButNotOfThoseThatMaySelectProjects()
            throws Exception {
        // Selecting compile tasks, whichever way, sets the level of this project...
        for (String selector : List.of(
                "tasks.withType(JavaCompile)",
                "tasks.matching { it.name.startsWith('compile') }",
                "getTasks().withType(JavaCompile)",
                "\n    tasks.withType(JavaCompile)\n")) {
            String build = "configure(" + selector + ") { options.release = 11 }\n";
            assertEquals("11", gradleLevel(build), build);
        }
        // ...while a selector that names projects, or no task, may leave this one out.
        for (String selector : List.of(
                "subprojects.collectMany { it.tasks.withType(JavaCompile) }",
                "allprojects.collectMany { it.tasks.withType(JavaCompile) }",
                "rootProject.tasks.withType(JavaCompile)",
                "project(':a').tasks.withType(JavaCompile)",
                "childProjects.values().collectMany { it.tasks.withType(JavaCompile) }",
                "tasksToConfigure")) {
            String build = "configure(" + selector + ") { options.release = 11 }\n";
            assertEquals("unknown", gradleLevel(build), build);
        }
        // A configure call without a block configures nothing.
        assertEquals("11", gradleLevel(
                "configure(javaProjects)\nsourceCompatibility = 11\ndependencies { }\n"));
    }

    @Test
    void appliesGradleConfigureOfSubprojectsToTheRootOnlyWhenTheSelectorMayHoldIt() throws Exception {
        for (String selector : List.of(
                "subprojects.findAll { it != rootProject }",
                "subprojects.findAll { it in allprojects }",
                "subprojects.findAll { it.name != 'docs' } + javaProjects",
                "subprojects.plus(javaProjects)")) {
            String build = "sourceCompatibility = JavaVersion.VERSION_1_8\nconfigure(" + selector
                    + ") { sourceCompatibility = JavaVersion.VERSION_11 }\n";
            assertEquals("unknown", gradleLevel(build), build);
        }
    }

    @Test
    void appliesGradleProjectBlocksWrittenWithBlanksAndToTheRootProject() throws Exception {
        assertEquals("11", gradleLevel(
                "project(':') { sourceCompatibility = JavaVersion.VERSION_11 }\n"));
        assertEquals("11", gradleLevel("configure(rootProject) { sourceCompatibility = 11 }\n"));
        Files.writeString(projectRoot.resolve("settings.gradle"), "include 'billing'");
        writeGradleModule("billing", "");
        Files.writeString(projectRoot.resolve("build.gradle"), """
                project (':billing') { sourceCompatibility = JavaVersion.VERSION_11 }
                """);
        assertEquals("11", new ProjectModelDetector().detect(projectRoot)
                .module("billing").orElseThrow().javaVersion());
        Files.writeString(projectRoot.resolve("build.gradle"), """
                configure ([project(':billing')]) { sourceCompatibility = JavaVersion.VERSION_17 }
                """);
        assertEquals("17", new ProjectModelDetector().detect(projectRoot)
                .module("billing").orElseThrow().javaVersion());
    }

    @Test
    void doesNotTakeAProjectExpressionForAProjectBlock() throws Exception {
        // project(":app") starts a line but opens no block; the expression goes on below.
        assertEquals("11", kotlinGradleLevel("""
                val phoneSigning =
                  project(":app")
                    .extensions
                    .findByName("signing")
                sourceCompatibility = 11
                android { namespace = "x" }
                """));
    }

    @Test
    void doesNotReadGradleReferencesOrLookalikesAsDeclarationsOfAModule() throws Exception {
        // The subprojects block sets 8, and no build file below declares a level of its own.
        List<String> builds = List.of(
                "println sourceCompatibility as String",
                "if (sourceCompatibility in [JavaVersion.VERSION_1_8]) { println 'old' }",
                "def old = sourceCompatibility instanceof JavaVersion",
                "def mysourceCompatibility = 7",
                "foo.xoptions.release = 7",
                "def xlanguageVersion = 5",
                "kotlin { compilerOptions { languageVersion.set(KotlinVersion.KOTLIN_2_0) } }",
                "kotlin { compilerOptions { languageVersion.value(KotlinVersion.KOTLIN_2_0) } }",
                "kotlin { compilerOptions { languageVersion.convention(KotlinVersion.KOTLIN_2_0) } }",
                "kotlin { compilerOptions { languageVersion = KotlinVersion.KOTLIN_2_0 } }",
                "kotlin { compilerOptions { languageVersion = \"1.9\" } }");
        List<String> modules = new ArrayList<>();
        for (int index = 0; index < builds.size(); index++) {
            modules.add("m" + index);
            Files.writeString(
                    Files.createDirectories(projectRoot.resolve("m" + index)).resolve("build.gradle"),
                    builds.get(index) + "\n");
        }
        Files.writeString(projectRoot.resolve("settings.gradle"), "include "
                + String.join(", ", modules.stream().map(module -> "'" + module + "'").toList()));
        Files.writeString(projectRoot.resolve("build.gradle"),
                "subprojects { sourceCompatibility = JavaVersion.VERSION_1_8 }\n");

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);

        for (int index = 0; index < builds.size(); index++) {
            assertEquals("8", model.module(modules.get(index)).orElseThrow().javaVersion(),
                    builds.get(index));
        }
    }

    @Test
    void readsGradleBuildsWithLongBlankRunsAndUnclosedCallsInLinearTime() throws Exception {
        // From each of 100 000 blank lines, a \s* at the start of a line would read to the end of
        // the run.
        String blank = "\n".repeat(100_000) + "x = 1\n";
        assertEquals("unknown", org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> gradleLevel(blank)));
        // Every unclosed configure( reads to the end of the script unless the first one is
        // remembered.
        String unclosed = "sourceCompatibility = 11\n" + "configure(\n".repeat(100_000);
        assertEquals("11", org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> gradleLevel(unclosed)));
    }

    @Test
    void readsGradleBlockHeaderOfManyDottedNamesWithoutOverflowingTheStack() throws Exception {
        String dotted = "a.".repeat(50_000) + "a {\n    sourceCompatibility = 11\n}\n";
        assertEquals("11", org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> gradleLevel(dotted)));
    }

    @Test
    void reportsEveryGradleSourceSetContainerCallThatAddsRemovesOrSelectsSourceSets() throws Exception {
        for (String build : List.of(
                "sourceSets.register('extra')",
                "sourceSets.maybeCreate('extra')",
                "sourceSets.add(extra)",
                "sourceSets.addAll([a, b])",
                "sourceSets.remove(extra)",
                "sourceSets.removeAll([a])",
                "sourceSets.removeIf({ it.name == 'x' })",
                "sourceSets.retainAll([sourceSets.main])",
                "sourceSets.clear()",
                "sourceSets.configureEach { java.exclude 'x' }",
                "sourceSets.each { it.java.exclude 'x' }",
                "sourceSets.forEach { it.java.exclude 'x' }",
                "sourceSets.matching { it.name != 'main' }.all { java.exclude 'x' }",
                "sourceSets.withType(SourceSet) { java.exclude 'x' }",
                "sourceSets.whenObjectAdded { it.java.exclude 'x' }",
                "sourceSets.getByName('main') { java.exclude 'x' }",
                "sourceSets.getByName('intTest') { compileClasspath += x }",
                "sourceSets.findByName('main').java.exclude 'x'",
                "sourceSets.getAt('main').java.exclude 'x'",
                "sourceSets.named('intTest', SourceSet) { compileClasspath += x }",
                "sourceSets.named(name) { java.exclude 'x' }",
                "sourceSets.named(name.replace(\"a\", \"b\")) { java.exclude 'x' }",
                "sourceSets.allTests { compileClasspath += x }",
                "sourceSets {\n    create('integrationTest')\n}",
                "sourceSets {\n    all { java.exclude 'x' }\n}",
                "sourceSets {\n    variant { compileClasspath += configurations.compileOnly }\n}",
                "sourceSets {\n    intTest\n}")) {
            assertGradleSourceSetsReported(true, "build.gradle", build + "\n");
        }
        // Calls on every source set report only a change of Java sources, not any closure.
        for (String build : List.of(
                "sourceSets.configureEach { compileClasspath += configurations.compileOnly }",
                "sourceSets.all { compileClasspath += configurations.compileOnly }",
                "sourceSets.each { compileClasspath += configurations.compileOnly }",
                "sourceSets.forEach { compileClasspath += configurations.compileOnly }",
                "sourceSets.matching { it.name != 'main' }",
                "sourceSets.whenObjectAdded { compileClasspath += configurations.compileOnly }",
                "sourceSets.getByName('main') { compileClasspath += x }",
                "sourceSets.named(name) { compileClasspath += x }",
                "sourceSets.addons.output.dir(x)",
                "def intTest = sourceSets.intTest",
                "sourceSets {\n    main { output.dir(x) }",
                "def s = sourceSets")) {
            assertGradleSourceSetsReported(false, "build.gradle", build);
        }
    }

    @Test
    void reportsKotlinGradleSourceSetCallsInEveryForm() throws Exception {
        for (String build : List.of(
                "sourceSets.creating(\"extra\")",
                "sourceSets.registering(\"extra\")",
                "sourceSets.getByName<SourceSet>(\"intTest\") { compileClasspath += x }",
                "sourceSets.getByName<SourceSet>(\"main\") { java.exclude(\"x\") }",
                "sourceSets.named(\"main\").get().java.exclude(\"x\")",
                "sourceSets.named(\"main\")?.get()?.java?.exclude(\"x\")",
                "sourceSets.named(\"main\").configure { java.exclude(\"x\") }",
                "sourceSets.named(\"main\").configureEach { java.exclude(\"x\") }",
                "sourceSets.main.get().apply { java.exclude(\"x\") }",
                "sourceSets.main.get().run { java.exclude(\"x\") }",
                "sourceSets.main?.apply { java.exclude(\"x\") }",
                "sourceSets {\n    \"main\" { java.exclude(\"x\") }\n}",
                "sourceSets {\n    val intTest by creating\n}",
                "sourceSets {\n    val intTest by registering\n}",
                "sourceSets {\n    register(\"integrationTest\")\n}",
                "sourceSets {\n    named(\"main\") { java.exclude(\"x\") }\n}")) {
            assertGradleSourceSetsReported(true, "build.gradle.kts", build + "\n");
        }
    }

    @Test
    void doesNotReportKotlinGradleBuildThatOnlyReadsACustomSourceSet() throws Exception {
        for (String build : List.of(
                "val integration = sourceSets[\"intTest\"]",
                "val integration = sourceSets.getByName(\"intTest\")",
                "val integration = sourceSets.named(\"intTest\")",
                "tasks.register<Test>(\"integrationTest\") {\n"
                        + "    classpath = sourceSets[\"intTest\"].runtimeClasspath\n}")) {
            assertGradleSourceSetsReported(false, "build.gradle.kts", build + "\n");
        }
    }

    @Test
    void reportsGradleSourceSetMembersThatChangeWhichFilesAreJava() throws Exception {
        for (String build : List.of(
                "sourceSets.main.java.source(other.java)",
                "sourceSets.main.java.include 'x'",
                "sourceSets.main.java.includes = ['x']",
                "sourceSets.main.java.excludes = ['x']",
                "sourceSets.main.java.setIncludes(['x'])",
                "sourceSets.main.java.setExcludes(['x'])",
                "sourceSets.main.java.filter.exclude 'x'",
                "sourceSets.main.java.getFilter().exclude 'x'",
                "sourceSets.main.getJava().exclude 'x'",
                "sourceSets.main.java?.exclude 'x'",
                "sourceSets?.main?.java?.exclude 'x'",
                "sourceSets. main.java.exclude 'x'",
                "sourceSets.main.with { java.exclude 'x' }",
                "sourceSets.main.all { java.exclude 'x' }",
                "sourceSets.named('main').configure({ java.exclude 'x' })")) {
            assertGradleSourceSetsReported(true, "build.gradle", build + "\n");
        }
        // A source directory is a static root, whichever way it is added.
        for (String build : List.of(
                "sourceSets.main.java.srcDir 'src/other/java'",
                "sourceSets { main { java { srcDir 'src/other/java' } } }",
                "sourceSets.main.java.srcDirs = ['src/other/java']")) {
            assertGradleSourceSetsReported(false, "build.gradle", build + "\n");
        }
    }

    @Test
    void reportsGradleSourceSetEntriesInsideStatementsOnlyWhenTheyChangeJavaSources() throws Exception {
        for (String statement : List.of(
                "if (flag) { main { java.exclude 'x' } }",
                "for (n in names) { main { java.exclude 'x' } }",
                "while (c) { main { java.exclude 'x' } }",
                "switch (x) { case 1: main { java.exclude 'x' } }",
                "def f = { main { java.exclude 'x' } }",
                "return main { java.exclude 'x' }",
                "try { main { java.exclude 'x' } } catch (e) { }",
                "if (flag) {\n    }\n    else {\n        main { java.exclude 'x' }\n    }")) {
            assertGradleSourceSetsReported(
                    true, "build.gradle", "sourceSets {\n    " + statement + "\n}\n");
        }
        for (String statement : List.of(
                "if (flag) {\n    }\n    else {\n        main { output.dir(x) }\n    }",
                "try { main { output.dir(x) } } catch (e) { }")) {
            assertGradleSourceSetsReported(
                    false, "build.gradle", "sourceSets {\n    " + statement + "\n}\n");
        }
        for (String statement : List.of(
                "when (x) { 1 -> main { java.exclude(\"x\") } }",
                "val f = { main { java.exclude(\"x\") } }",
                "var f = { main { java.exclude(\"x\") } }")) {
            assertGradleSourceSetsReported(
                    true, "build.gradle.kts", "sourceSets {\n    " + statement + "\n}\n");
        }
    }

    private void assertGradleSourceSetsReported(boolean reported, String file, String build)
            throws Exception {
        Path project = Files.createTempDirectory(projectRoot, "sourcesets");
        Files.writeString(project.resolve(file), build);

        ProjectModel model = new ProjectModelDetector().detect(project);

        assertEquals(reported
                        ? List.of(new ProjectDiagnostic("BUILD_MODEL_PARTIAL",
                                "Dynamic Gradle source-set configuration in " + project))
                        : List.of(),
                model.diagnostics(), build);
    }

    @Test
    void endsAGradleIncludeCommandWhereAnotherOperandFollowsWithoutAComma() throws Exception {
        // A block comment that spanned lines left two statements on one line.
        for (String operand : List.of("'a'", "\"a\"")) {
            assertEquals(List.of("a", "b"), gradleModulePaths("settings.gradle",
                    "include " + operand + " /* c\n */ include 'b'\n"), operand);
        }
        for (String operand : List.of("a", "a1", "a(1)", "a[1]")) {
            assertEquals(List.of("b"), gradleModulePaths("settings.gradle",
                    "include " + operand + " /* c\n */ include 'b'\n"), operand);
        }
        // What follows an argument is no argument, whatever it is.
        assertEquals(List.of(""), gradleModulePaths("settings.gradle",
                "include a /* c\n */ call 'b'\n"));
    }
}
