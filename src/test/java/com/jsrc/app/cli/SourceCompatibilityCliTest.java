package com.jsrc.app.cli;

import com.jsrc.app.output.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCompatibilityCliTest {

    @TempDir
    Path projectRoot;

    @Test
    void quarantinesLanguageFeaturesBeforeTheirStableSourceRelease() throws Exception {
        assertSourceTransition(8, 9, "module-info.java", "module example.module {}");
        assertSourceTransition(8, 9, "Feature.java", """
                interface Feature { private void helper() {} }
                """);
        assertSourceTransition(8, 10, "Feature.java", """
                class Feature { void run() { var value = 1; } }
                """);
        assertSourceTransition(13, 14, "Feature.java", """
                class Feature { int run(int x) { return switch (x) {
                    case 1 -> 1; default -> 2; }; } }
                """);
        assertSourceTransition(14, 15, "Feature.java", """
                class Feature { String value = """ + "\"\"\"" + """
                    text
                    """ + "\"\"\"" + "; }");
        assertSourceTransition(15, 16, "Feature.java", "record Feature(int value) {}");
        assertSourceTransition(16, 17, "Feature.java", """
                sealed class Feature permits Child {}
                final class Child extends Feature {}
                """);
    }

    @Test
    void doesNotTreatJsrcRuntimeJdkAsSupportedSourceLevel() throws Exception {
        Path source = projectRoot.resolve("Feature.java");
        Files.writeString(source, "class Feature {}");
        var config = new com.jsrc.app.config.ProjectConfig(
                List.of(), List.of(), "22",
                com.jsrc.app.config.ArchitectureConfig.empty());
        var result = new com.jsrc.app.project.SourceCompatibilityScanner()
                .scan(List.of(source), null, config);
        assertTrue(result.files().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                "SOURCE_LEVEL_UNSUPPORTED".equals(diagnostic.code())));
    }

    private void assertSourceTransition(
            int rejectedVersion, int acceptedVersion, String fileName, String code)
            throws Exception {
        Path source = projectRoot.resolve(fileName);
        Files.writeString(source, code);
        var scanner = new com.jsrc.app.project.SourceCompatibilityScanner();
        var rejectedConfig = new com.jsrc.app.config.ProjectConfig(
                List.of(), List.of(), Integer.toString(rejectedVersion),
                com.jsrc.app.config.ArchitectureConfig.empty());
        var acceptedConfig = new com.jsrc.app.config.ProjectConfig(
                List.of(), List.of(), Integer.toString(acceptedVersion),
                com.jsrc.app.config.ArchitectureConfig.empty());
        assertFalse(scanner.scan(List.of(source), null, rejectedConfig).files().contains(source),
                fileName + " should be rejected under Java " + rejectedVersion);
        assertTrue(scanner.scan(List.of(source), null, acceptedConfig).files().contains(source),
                fileName + " should be accepted under Java " + acceptedVersion);
    }

    @Test
    void resolvesEveryStableSourceLevelFromEightThroughTwentyOne() throws Exception {
        Path pom = projectRoot.resolve("pom.xml");
        Path source = Files.createDirectories(projectRoot.resolve("src/main/java"))
                .resolve("Example.java");
        Files.writeString(source, "class Example {}");
        for (int version = 8; version <= 21; version++) {
            Files.writeString(pom, """
                    <project><modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId><artifactId>levels</artifactId><version>1</version>
                    <properties><maven.compiler.release>%d</maven.compiler.release></properties>
                    </project>
                    """.formatted(version));
            var model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
            var level = com.jsrc.app.project.SourceLevel.resolve(source, model, null);
            assertEquals(version, level.orElseThrow().version());
        }
    }

    @Test
    void usesMavenCompilerReleaseBeforeSourceAndIgnoresTarget() throws Exception {
        Path pom = projectRoot.resolve("pom.xml");
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>levels</artifactId><version>1</version>
                <properties><maven.compiler.source>8</maven.compiler.source>
                <maven.compiler.target>21</maven.compiler.target></properties>
                <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                <configuration><release>11</release></configuration>
                </plugin></plugins></build>
                </project>
                """);
        Path source = Files.createDirectories(projectRoot.resolve("src/main/java"))
                .resolve("Example.java");
        Files.writeString(source, "class Example {}");
        var model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertEquals(11, com.jsrc.app.project.SourceLevel.resolve(source, model, null)
                .orElseThrow().version());

        Files.writeString(pom, Files.readString(pom).replace(
                "<maven.compiler.source>8</maven.compiler.source>", ""));
        Files.writeString(pom, Files.readString(pom).replace(
                "<release>11</release>", ""));
        model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertTrue(com.jsrc.app.project.SourceLevel.resolve(source, model, null).isEmpty());

        Files.writeString(pom, Files.readString(pom).replace(
                "<maven.compiler.target>21</maven.compiler.target>",
                "<java.version>21</java.version>"));
        model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertTrue(com.jsrc.app.project.SourceLevel.resolve(source, model, null).isEmpty());
    }

    @Test
    void usesGradleReleaseBeforeCompatibilityAndIgnoresTargetOnly() throws Exception {
        Path build = projectRoot.resolve("build.gradle");
        Files.writeString(build, """
                plugins { id 'java' }
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_21
                tasks.withType(JavaCompile) { options.release.set(11) }
                """);
        Path source = Files.createDirectories(projectRoot.resolve("src/main/java"))
                .resolve("Example.java");
        Files.writeString(source, "class Example {}");
        var model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertEquals(11, com.jsrc.app.project.SourceLevel.resolve(source, model, null)
                .orElseThrow().version());

        Files.writeString(build, "targetCompatibility = JavaVersion.VERSION_21");
        model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertTrue(com.jsrc.app.project.SourceLevel.resolve(source, model, null).isEmpty());

        Files.writeString(build,
                "sourceCompatibility = providers.gradleProperty('javaVersion').get()");
        model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertTrue(com.jsrc.app.project.SourceLevel.resolve(source, model, null).isEmpty());

        Files.writeString(build, """
                // sourceCompatibility = JavaVersion.VERSION_21
                /* options.release.set(20) */
                sourceCompatibility = JavaVersion.VERSION_8
                """);
        model = new com.jsrc.app.project.ProjectModelDetector().detect(projectRoot);
        assertEquals(8, com.jsrc.app.project.SourceLevel.resolve(source, model, null)
                .orElseThrow().version());
    }

    @Test
    void moduleOverrideHandlesBuildWithUndeclaredSourceLevel() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>aggregate</artifactId><version>1</version>
                <packaging>pom</packaging><modules><module>dynamic</module></modules>
                </project>
                """);
        Path module = Files.createDirectories(projectRoot.resolve("dynamic"));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>dynamic</artifactId><version>1</version>
                </project>
                """);
        Files.writeString(projectRoot.resolve(".jsrc.yaml"), """
                moduleJavaVersions:
                  dynamic: "17"
                """);
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "record Point(int x) {}");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute("--dir", projectRoot.toString(),
                    "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }
        Map<?, ?> envelope = assertInstanceOf(Map.class,
                JsonReader.parse(captured.toString().trim()));
        assertEquals("ok", envelope.get("status"));
        assertEquals(1L, ((Map<?, ?>) envelope.get("data")).get("total"));
    }

    @Test
    void recognizesJavaNineModuleAndPrivateInterfaceMethod() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>modules</artifactId><version>1</version>
                <properties><maven.compiler.release>9</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("module-info.java"), "module example.module {}");
        Files.writeString(sourceRoot.resolve("Feature.java"), """
                interface Feature {
                    private void helper() {}
                    default void run() { helper(); }
                }
                """);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute("--dir", projectRoot.toString(),
                    "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }
        Map<?, ?> envelope = assertInstanceOf(Map.class,
                JsonReader.parse(captured.toString().trim()));
        assertEquals("ok", envelope.get("status"));
        assertEquals(1L, ((Map<?, ?>) envelope.get("data")).get("total"));
    }

    @Test
    void inheritsMavenReleaseOnlyFromVerifiedParent() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>aggregate</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                  <modules><module>child</module></modules>
                </project>
                """);
        Path module = Files.createDirectories(projectRoot.resolve("child"));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>example</groupId><artifactId>aggregate</artifactId>
                    <version>1</version></parent>
                  <artifactId>child</artifactId>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "public record Point(int x) {}");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute("--dir", projectRoot.toString(),
                    "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(Map.class,
                JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        assertTrue(diagnostics.stream().anyMatch(value ->
                "SOURCE_SYNTAX_UNSUPPORTED".equals(((Map<?, ?>) value).get("code"))));
        assertFalse(diagnostics.stream().anyMatch(value ->
                "SOURCE_LEVEL_UNKNOWN".equals(((Map<?, ?>) value).get("code"))));
    }

    @Test
    void legacyOutputReturnsNonzeroWhenSemanticFilesAreQuarantined() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Legacy.java"), "class Legacy {}");
        Files.writeString(sourceRoot.resolve("Point.java"), "record Point(int x) {}");
        assertNotEquals(0, JsrcCliFactory.create().execute(
                "--dir", projectRoot.toString(), "classes"));
    }

    @Test
    void reportsJavaEightRecordAsPartialWithoutPublishingItsSymbols() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>compatibility</artifactId>
                  <version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Legacy.java"), "public class Legacy {}");
        Files.writeString(sourceRoot.resolve("Point.java"), "public record Point(int x) {}");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", envelope.get("status"));
        Map<?, ?> data = assertInstanceOf(Map.class, envelope.get("data"));
        List<?> classes = assertInstanceOf(List.class, data.get("classes"));
        assertTrue(classes.stream().anyMatch(value ->
                "Legacy".equals(((Map<?, ?>) value).get("name"))));
        assertFalse(classes.stream().anyMatch(value ->
                "Point".equals(((Map<?, ?>) value).get("name"))));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        assertTrue(diagnostics.stream().anyMatch(value ->
                "SOURCE_SYNTAX_UNSUPPORTED".equals(((Map<?, ?>) value).get("code"))));
    }

    @Test
    void usesModuleSourceLevelInsteadOfAggregatorLevel() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>aggregate</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <modules><module>legacy</module></modules>
                </project>
                """);
        Path module = Files.createDirectories(projectRoot.resolve("legacy"));
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>legacy</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "public record Point(int x) {}");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", envelope.get("status"));
        Map<?, ?> data = assertInstanceOf(Map.class, envelope.get("data"));
        assertEquals(0L, data.get("total"));
    }

    @Test
    void doesNotInheritSourceLevelFromUnrelatedAggregator() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>aggregate</artifactId><version>1</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                  <modules><module>independent</module></modules>
                </project>
                """);
        Path module = Files.createDirectories(projectRoot.resolve("independent"));
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>independent</artifactId><version>1</version>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "public record Point(int x) {}");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        assertTrue(diagnostics.stream().anyMatch(value ->
                "SOURCE_LEVEL_UNKNOWN".equals(((Map<?, ?>) value).get("code"))));
    }

    @Test
    void doesNotClaimExactJavaEightAnalysisForAmbiguousVarDeclaration() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Example.java"), """
                public class Example {
                    void run() { var value = 1; }
                }
                """);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("partial", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        assertTrue(diagnostics.stream().anyMatch(value ->
                "SOURCE_SYNTAX_UNCERTAIN".equals(((Map<?, ?>) value).get("code"))));
    }

    @Test
    void acceptsJavaEightClassActuallyNamedVar() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Use.java"), """
                class var {}
                class Use {
                    void run() { var value = new var(); }
                }
                """);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1", "classes");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("ok", envelope.get("status"));
        Map<?, ?> data = assertInstanceOf(Map.class, envelope.get("data"));
        assertEquals(2L, data.get("total"));
    }

    @Test
    void resolvesCallsUsingTheDeclaredJavaEightGrammar() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Use.java"), """
                class var {
                    void ping() {}
                }
                class Use {
                    void run() { new var().ping(); }
                }
                """);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1",
                    "--full", "callees", "Use.run");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("ok", envelope.get("status"));
        List<?> callees = assertInstanceOf(List.class, envelope.get("data"));
        assertTrue(callees.stream().anyMatch(value ->
                "ping".equals(((Map<?, ?>) value).get("method"))));
    }

    @Test
    void indexedGraphUsesTheDeclaredJavaEightGrammar() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Use.java"), """
                class var {
                    void ping() {}
                }
                class Use {
                    void run() { new var().ping(); }
                }
                """);
        assertEquals(0, JsrcCliFactory.create().execute(
                "--dir", projectRoot.toString(), "index"));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1",
                    "--full", "--frozen-index", "callees", "Use.run");
        } finally {
            System.setOut(originalOut);
        }

        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("ok", envelope.get("status"));
        List<?> callees = assertInstanceOf(List.class, envelope.get("data"));
        assertTrue(callees.stream().anyMatch(value ->
                "ping".equals(((Map<?, ?>) value).get("method"))));
    }

    @Test
    void frozenIndexRejectsChangedLevelEvenWhenSourceBecomesInvalid() throws Exception {
        Path pom = projectRoot.resolve("pom.xml");
        Files.writeString(pom, """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Point.java"), "record Point(int x) {}");
        assertEquals(0, JsrcCliFactory.create().execute("--dir", projectRoot.toString(), "index"));
        Files.writeString(pom, Files.readString(pom).replace(
                "<maven.compiler.release>21</maven.compiler.release>",
                "<maven.compiler.release>8</maven.compiler.release>"));
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setErr(new PrintStream(captured));
            exitCode = JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1",
                    "--frozen-index", "classes");
        } finally {
            System.setErr(originalErr);
        }
        assertNotEquals(0, exitCode);
        assertTrue(captured.toString().contains("INDEX_SOURCE_LEVEL_MISMATCH"));
    }

    @Test
    void frozenIndexRejectsSourceLevelChangesWithoutJavaChanges() throws Exception {
        Path pom = projectRoot.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>compatibility</artifactId><version>1</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Legacy.java"), "public class Legacy {}");
        assertEquals(0, JsrcCliFactory.create().execute(
                "--dir", projectRoot.toString(), "index"));

        Files.writeString(pom, Files.readString(pom).replace(
                "<maven.compiler.release>21</maven.compiler.release>",
                "<maven.compiler.release>8</maven.compiler.release>"));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setErr(new PrintStream(captured));
            exitCode = JsrcCliFactory.create().execute(
                    "--dir", projectRoot.toString(), "--json", "--protocol", "1",
                    "--frozen-index", "classes");
        } finally {
            System.setErr(originalErr);
        }

        assertNotEquals(0, exitCode);
        Map<?, ?> envelope = assertInstanceOf(
                Map.class, JsonReader.parse(captured.toString().trim()));
        assertEquals("error", envelope.get("status"));
        List<?> diagnostics = assertInstanceOf(List.class, envelope.get("diagnostics"));
        assertEquals("INDEX_SOURCE_LEVEL_MISMATCH",
                ((Map<?, ?>) diagnostics.getFirst()).get("code"));
    }
}
