package com.jsrc.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.navigate.DepsCommand;
import com.jsrc.app.command.navigate.ReadCommand;

/**
 * Edge case tests identified by Rune during benchmark review.
 */
class EdgeCaseRegressionTest {

    @TempDir
    Path tempDir;

    // --- Class in file with different name ---

    @Test
    void readClass_classInFileWithDifferentName() throws Exception {
        // OrderService lives in Services.java (not OrderService.java)
        Path file = tempDir.resolve("Services.java");
        Files.writeString(file, """
                package com.app;
                public class OrderService {
                    public void process() {}
                }
                class HelperService {
                    public void help() {}
                }
                """);

        var indexed = buildIndex(file);
        // findFileForClass should find OrderService via index, not by filename
        var filePath = indexed.findFileForClass("OrderService");
        assertTrue(filePath.isPresent(), "Should find OrderService even in Services.java");
    }

    @Test
    void deps_classInFileWithDifferentName() throws Exception {
        Path file = tempDir.resolve("Services.java");
        Files.writeString(file, """
                package com.app;
                import java.util.List;
                public class OrderService {
                    private List<String> items;
                    public void process() {}
                }
                """);

        var indexed = buildIndex(file);
        var deps = indexed.getDependencies("OrderService");
        assertTrue(deps.isPresent(), "Should find deps for class in differently-named file");
        assertFalse(deps.get().imports().isEmpty());
    }

    // --- Inner class ---

    @Test
    void deps_innerClassNotMixedWithOuter() throws Exception {
        Path file = tempDir.resolve("Outer.java");
        Files.writeString(file, """
                package com.app;
                import java.util.Map;
                public class Outer {
                    private String outerField;
                    public static class Inner {
                        private int innerField;
                    }
                }
                """);

        var indexed = buildIndex(file);
        var outerDeps = indexed.getDependencies("Outer");
        assertTrue(outerDeps.isPresent());
        assertTrue(outerDeps.get().fieldDependencies().stream()
                .anyMatch(f -> f.name().equals("outerField")));
        // Inner's field should not be in Outer's deps
        assertFalse(outerDeps.get().fieldDependencies().stream()
                .anyMatch(f -> f.name().equals("innerField")),
                "Inner class fields should not leak into outer class deps");
    }

    // --- Multiple constructors ---

    @Test
    void deps_multipleConstructors() throws Exception {
        Path file = tempDir.resolve("Service.java");
        Files.writeString(file, """
                package com.app;
                public class Service {
                    private String name;
                    public Service(String name) { this.name = name; }
                    public Service(String name, int priority) { this.name = name; }
                }
                """);

        var indexed = buildIndex(file);
        var deps = indexed.getDependencies("Service");
        assertTrue(deps.isPresent());
        // Should include params from both constructors
        var ctorParams = deps.get().constructorDependencies();
        assertTrue(ctorParams.size() >= 2, "Should include params from multiple constructors, got: " + ctorParams);
    }

    // --- Regression guards ---

    @Test
    void parse_enumWithBody() throws Exception {
        Path file = tempDir.resolve("Status.java");
        Files.writeString(file, """
                package com.app;
                public enum Status {
                    ACTIVE("a"), INACTIVE("i");
                    private final String code;
                    Status(String code) { this.code = code; }
                    public String getCode() { return code; }
                }
                """);

        var parser = new HybridJavaParser();
        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty(), "Enum with body should parse");
    }

    @Test
    void parse_lambdaInFieldNotCountedAsMethod() throws Exception {
        Path file = tempDir.resolve("Handler.java");
        Files.writeString(file, """
                package com.app;
                public class Handler {
                    private Runnable task = () -> System.out.println("run");
                    public void execute() { task.run(); }
                }
                """);

        var parser = new HybridJavaParser();
        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty());
        // Lambda in field should not count as a method
        var methods = classes.getFirst().methods();
        assertTrue(methods.stream().noneMatch(m -> m.name().equals("run")),
                "Lambda in field should not appear as method. Got: " + methods);
    }

    @Test
    void parse_genericMethodWithBounds() throws Exception {
        Path file = tempDir.resolve("Util.java");
        Files.writeString(file, """
                package com.app;
                public class Util {
                    public <T extends Comparable<T>> T max(T a, T b) {
                        return a.compareTo(b) >= 0 ? a : b;
                    }
                }
                """);

        var parser = new HybridJavaParser();
        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty());
        assertTrue(classes.getFirst().methods().stream()
                .anyMatch(m -> m.name().equals("max")));
    }

    @Test
    void parse_annotationWithArrayValue() throws Exception {
        Path file = tempDir.resolve("Suppressed.java");
        Files.writeString(file, """
                package com.app;
                @SuppressWarnings({"unchecked", "rawtypes"})
                public class Suppressed {
                    public void work() {}
                }
                """);

        var parser = new HybridJavaParser();
        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty());
        assertEquals("Suppressed", classes.getFirst().name());
    }

    // --- Multiple classes in one file ---

    @Test
    void parse_multipleClassesInOneFile() throws Exception {
        Path file = tempDir.resolve("Bundle.java");
        Files.writeString(file, """
                package com.app;
                public class MainClass {
                    public void main() {}
                }
                class SecondClass {
                    public void secondary() {}
                }
                """);

        var parser = new HybridJavaParser();
        var classes = parser.parseClasses(file);
        assertEquals(2, classes.size(), "Should find both classes in one file");
    }

    private IndexedCodebase buildIndex(Path file) throws Exception {
        var files = List.of(file);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        return IndexedCodebase.tryLoad(tempDir, files);
    }
}
