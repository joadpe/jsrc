package com.jsrc.app.project;

import com.jsrc.app.config.ArchitectureConfig;
import com.jsrc.app.config.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SourceCompatibilityMatrixTest {

    @TempDir
    Path root;

    @Test
    void javaEightAcceptsLambdasAndDefaultMethodsButRejectsRecords() throws Exception {
        assertSource(8, """
                interface Action {
                    default int run() {
                        java.util.function.IntSupplier supplier = () -> 1;
                        return supplier.getAsInt();
                    }
                }
                """, true);
        assertSource(8, "record Feature(int value) {}", false);
        assertSource(8, "interface Feature { private void helper() {} }", false);
        assertSource(8, "class Feature { java.util.function.Function<String, String> f = (var x) -> x; }", false);
    }

    @Test
    void everyStableSourceLevelFromEightThroughTwentyOneIsConfigured() throws Exception {
        for (int version = 8; version <= 21; version++) {
            assertSource(version, "class Feature { int value() { return 1; } }", true);
        }
    }

    @Test
    void javaNineAcceptsModulesAndPrivateInterfaceMethods() throws Exception {
        assertSource(9, "interface Feature { private void helper() {} }", true);

        Path module = root.resolve("module-info.java");
        Files.writeString(module, "module feature {}\n");
        var config = new ProjectConfig(
                List.of(), List.of(), "9", ArchitectureConfig.empty());
        var outcome = new SourceCompatibilityScanner().scan(List.of(module), null, config);
        assertEquals(List.of(module), outcome.files(), outcome.diagnostics().toString());

        var errors = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "--release", "9", "-d",
                Files.createDirectories(root.resolve("module-classes")).toString(),
                module.toString());
        assertEquals(0, exit, "javac --release 9: " + errors);
    }

    @Test
    void javaElevenAcceptsLocalVarAndReportsVarLambdaParserLimitation() throws Exception {
        assertSource(11, """
                class Feature {
                    int run() {
                        var value = 1;
                        java.util.function.IntSupplier supplier = () -> value;
                        return supplier.getAsInt();
                    }
                }
                """, true);
        assertSource(11, "record Feature(int value) {}", false);
        assertParserLimitation(11, """
                class Feature {
                    java.util.function.Function<String, String> identity = (var value) -> value;
                }
                """);
    }

    @Test
    void javaSeventeenAcceptsRecordsSealedTextBlocksAndSwitchExpressions() throws Exception {
        assertSource(17, "sealed interface Shape permits Circle {}\n"
                + "record Circle(int radius) implements Shape {}\n"
                + "class Feature {\n"
                + "    String text = \"\"\"\n        hello\n        \"\"\";\n"
                + "    int run(int x) { return switch (x) { case 1 -> 1; default -> 2; }; }\n"
                + "}\n", true);
        assertSource(17, """
                sealed interface Shape permits Circle {}
                record Circle(int radius) implements Shape {}
                class Feature {
                    int run(Shape shape) {
                        return switch (shape) { case Circle(int radius) -> radius; };
                    }
                }
                """, false);
    }

    @Test
    void javaTwentyOneAcceptsRecordPatternsAndPatternSwitch() throws Exception {
        assertSource(21, """
                sealed interface Shape permits Circle {}
                record Circle(int radius) implements Shape {}
                class Feature {
                    int run(Shape shape) {
                        return switch (shape) { case Circle(int radius) -> radius; };
                    }
                }
                """, true);
    }

    @Test
    void unchangedIndexedSourceSkipsBothParsersButChangedSourceIsValidated()
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>cache</artifactId><version>1</version>
                  <properties><maven.compiler.release>8</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java"));
        Path file = sourceRoot.resolve("Feature.java");
        Files.writeString(file, "class Feature {}\n");
        assertEquals(0, com.jsrc.app.cli.JsrcCliFactory.create().execute(
                "--dir", root.toString(), "index"));
        ProjectModel model = new ProjectModelDetector().detect(root);

        var unchanged = new SourceCompatibilityScanner().scan(List.of(file), model, null);
        assertEquals(List.of(file), unchanged.files());
        assertEquals(0, unchanged.parsedFiles());

        Files.writeString(file, "record Feature(int value) {}\n");
        var changed = new SourceCompatibilityScanner().scan(List.of(file), model, null);
        assertEquals(1, changed.parsedFiles());
        assertEquals(List.of(), changed.files());
        assertEquals("SOURCE_SYNTAX_UNSUPPORTED", changed.diagnostics().getFirst().code());
    }

    private void assertSource(int version, String source, boolean expectedAccepted)
            throws Exception {
        Path file = root.resolve("Feature.java");
        Files.writeString(file, source);
        var config = new ProjectConfig(
                List.of(), List.of(), Integer.toString(version), ArchitectureConfig.empty());
        var outcome = new SourceCompatibilityScanner().scan(List.of(file), null, config);
        assertEquals(expectedAccepted, outcome.files().contains(file),
                "jsrc source acceptance for Java " + version + ": " + outcome.diagnostics());

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK");
        Path output = Files.createDirectories(root.resolve("classes-" + version));
        var errors = new ByteArrayOutputStream();
        int exit = compiler.run(null, null, errors, "--release", Integer.toString(version),
                "-d", output.toString(), file.toString());
        assertEquals(expectedAccepted, exit == 0,
                "javac --release " + version + ": " + errors);
    }

    private void assertParserLimitation(int version, String source) throws Exception {
        Path file = root.resolve("Feature.java");
        Files.writeString(file, source);
        var config = new ProjectConfig(
                List.of(), List.of(), Integer.toString(version), ArchitectureConfig.empty());
        var outcome = new SourceCompatibilityScanner().scan(List.of(file), null, config);
        assertEquals(List.of(), outcome.files());
        assertEquals("SOURCE_PARSER_LIMITATION", outcome.diagnostics().getFirst().code());

        var errors = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "--release", Integer.toString(version),
                "-d", Files.createDirectories(root.resolve("limitation-classes")).toString(),
                file.toString());
        assertEquals(0, exit, "Fixture must be valid Java " + version + ": " + errors);
    }
}
