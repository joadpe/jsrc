package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that HybridJavaParser correctly handles Java 21+ language features.
 * These features caused JavaParser 3.25.4 to fail, falling back to TreeSitter
 * (which loses semantic depth for --deps, --smells).
 */
class Java21FeaturesTest {

    @TempDir
    Path tempDir;

    private final HybridJavaParser parser = new HybridJavaParser();

    @Test
    void sealedClass_parsesCorrectly() throws Exception {
        Path file = tempDir.resolve("Shape.java");
        Files.writeString(file, """
                package com.test;
                public sealed class Shape permits Circle, Square {
                    public String name() { return "shape"; }
                }
                """);

        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty(), "Sealed class should parse");
        assertEquals("Shape", classes.getFirst().name());
        assertFalse(classes.getFirst().methods().isEmpty(), "Methods should be extracted");
    }

    @Test
    void recordPattern_parsesCorrectly() throws Exception {
        Path file = tempDir.resolve("Matcher.java");
        Files.writeString(file, """
                package com.test;
                public class Matcher {
                    record Point(int x, int y) {}
                    public String describe(Object obj) {
                        if (obj instanceof Point(int x, int y)) {
                            return "point at " + x + "," + y;
                        }
                        return "unknown";
                    }
                }
                """);

        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty(), "Class with record pattern should parse");
        var matcher = classes.stream().filter(c -> c.name().equals("Matcher")).findFirst();
        assertTrue(matcher.isPresent());
        assertFalse(matcher.get().methods().isEmpty(), "Methods should be extracted");
    }

    @Test
    void switchExpressions_parsesCorrectly() throws Exception {
        Path file = tempDir.resolve("Formatter.java");
        Files.writeString(file, """
                package com.test;
                public class Formatter {
                    public String format(Object obj) {
                        return switch (obj) {
                            case Integer i -> "int: " + i;
                            case String s -> "str: " + s;
                            default -> "other";
                        };
                    }
                }
                """);

        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty(), "Class with switch expressions should parse");
        assertEquals("Formatter", classes.getFirst().name());
    }

    @Test
    void sealedClass_detectsSmells() throws Exception {
        Path file = tempDir.resolve("Processor.java");
        Files.writeString(file, """
                package com.test;
                public sealed class Processor permits FastProcessor {
                    public void process(int a, int b, int c, int d, int e, int f) {
                        try {
                            // work
                        } catch (Exception e2) {
                            // empty catch — should be a smell
                        }
                    }
                }
                """);

        var smells = parser.detectSmells(file);
        assertFalse(smells.isEmpty(),
                "Should detect smells in sealed class (empty catch, too many params)");
    }

    @Test
    void standardRecord_parsesCorrectly() throws Exception {
        Path file = tempDir.resolve("Point.java");
        Files.writeString(file, """
                package com.test;
                public record Point(int x, int y) {
                    public int sum() { return x + y; }
                }
                """);

        var classes = parser.parseClasses(file);
        assertFalse(classes.isEmpty(), "Standard record should parse");
        assertEquals("Point", classes.getFirst().name());
        assertTrue(classes.getFirst().methods().stream()
                .anyMatch(m -> m.name().equals("sum")), "Should extract methods from record");
    }
}
