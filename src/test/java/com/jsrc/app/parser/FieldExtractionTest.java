package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Tests that HybridJavaParser extracts field declarations into ClassInfo.fields().
 */
class FieldExtractionTest {

    @TempDir
    Path tempDir;

    private final HybridJavaParser parser = new HybridJavaParser();

    @Test
    void classWithFields_extractsNameAndType() throws Exception {
        Path file = tempDir.resolve("Svc.java");
        Files.writeString(file, """
                public class Svc {
                    private String name;
                    private int count;
                    public void run() {}
                }
                """);

        var classes = parser.parseClasses(file);
        assertEquals(1, classes.size());
        ClassInfo ci = classes.getFirst();
        assertEquals(2, ci.fields().size());
        assertEquals("name", ci.fields().get(0).name());
        assertEquals("String", ci.fields().get(0).type());
        assertEquals("count", ci.fields().get(1).name());
        assertEquals("int", ci.fields().get(1).type());
    }

    @Test
    void classWithGenericField_stripsGenerics() throws Exception {
        Path file = tempDir.resolve("Container.java");
        Files.writeString(file, """
                import java.util.List;
                public class Container {
                    private List<String> items;
                }
                """);

        var classes = parser.parseClasses(file);
        ClassInfo ci = classes.getFirst();
        assertEquals(1, ci.fields().size());
        assertEquals("items", ci.fields().getFirst().name());
        assertEquals("List", ci.fields().getFirst().type());
    }

    @Test
    void classWithNoFields_emptyList() throws Exception {
        Path file = tempDir.resolve("Empty.java");
        Files.writeString(file, """
                public class Empty {
                    public void doWork() {}
                }
                """);

        var classes = parser.parseClasses(file);
        ClassInfo ci = classes.getFirst();
        assertTrue(ci.fields().isEmpty());
    }

    @Test
    void multipleVarsPerDeclaration() throws Exception {
        Path file = tempDir.resolve("Multi.java");
        Files.writeString(file, """
                public class Multi {
                    private int x, y, z;
                }
                """);

        var classes = parser.parseClasses(file);
        ClassInfo ci = classes.getFirst();
        assertEquals(3, ci.fields().size());
        assertEquals("x", ci.fields().get(0).name());
        assertEquals("y", ci.fields().get(1).name());
        assertEquals("z", ci.fields().get(2).name());
        // All should be int
        assertTrue(ci.fields().stream().allMatch(f -> "int".equals(f.type())));
    }
}
