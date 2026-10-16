package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceReaderTest {

    private SourceReader reader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reader = new SourceReader(new HybridJavaParser());
    }

    @Test
    @DisplayName("Should read a specific method by Class.method")
    void shouldReadMethod() throws IOException {
        Path file = writeFile("OrderService.java", """
                package com.app;
                public class OrderService {
                    public void create(String name) {
                        System.out.println(name);
                    }
                    public void delete(Long id) {
                        // delete
                    }
                }
                """);

        var result = reader.readMethod(List.of(file), "OrderService", "create");
        assertTrue(result.isPresent());
        assertEquals("create", result.get().methodName());
        assertEquals("OrderService", result.get().className());
        assertTrue(result.get().content().contains("System.out.println"));
        assertFalse(result.get().content().contains("delete"));
    }

    @Test
    @DisplayName("Should read entire class by name")
    void shouldReadClass() throws IOException {
        Path file = writeFile("Service.java", """
                package com.app;
                public class Service {
                    public void run() {}
                }
                """);

        var result = reader.readClass(List.of(file), "Service");
        assertTrue(result.isPresent());
        assertEquals("Service", result.get().className());
        assertTrue(result.get().content().contains("public class Service"));
        assertTrue(result.get().content().contains("public void run()"));
    }

    @Test
    @DisplayName("Should return null for nonexistent class")
    void shouldReturnNullForMissingClass() throws IOException {
        Path file = writeFile("Other.java", "public class Other {}");
        assertTrue(reader.readClass(List.of(file), "Missing").isEmpty());
    }

    @Test
    @DisplayName("Should find a class after files that cannot declare it")
    void shouldReadClassAfterUnrelatedFiles() throws IOException {
        Path unrelated = writeFile("Unrelated.java", "public class Unrelated {}");
        Path mention = writeFile("Mention.java", """
                package com.app;
                // Mentions Target in a comment but declares nothing named so.
                public class Mention { Target target; }
                """);
        Path declaring = writeFile("Holder.java", """
                package com.app;
                public class Holder {
                    public static class Target { void run() {} }
                }
                """);

        var result = reader.readClass(List.of(unrelated, mention, declaring), "Target");
        assertTrue(result.isPresent());
        assertEquals(declaring, result.get().file());
        assertEquals("Holder$Target", result.get().className());
        assertTrue(reader.readClass(List.of(declaring), "com.app.Holder.Target").isPresent());
        assertTrue(reader.readClass(List.of(unrelated, mention), "Target").isEmpty());
    }

    @Test
    @DisplayName("Should read local classes by binary name")
    void shouldReadLocalClassByBinaryName() throws IOException {
        Path file = writeFile("Outer.java", """
                public class Outer {
                    void run() {
                        class Local { int value; }
                    }
                }
                """);

        var result = reader.readClass(List.of(file), "Outer$run$1$Local$1");
        assertTrue(result.isPresent());
        assertTrue(result.get().content().contains("class Local"));
    }

    @Test
    @DisplayName("Should read classes whose name is spelled with unicode escapes as the parser reports them")
    void shouldReadClassSpelledWithUnicodeEscape() throws IOException {
        Path file = writeFile("Escaped.java", "public class \\u0045scaped { }\n");
        HybridJavaParser parser = new HybridJavaParser();

        boolean parsed = parser.parseClasses(file).stream()
                .anyMatch(info -> info.name().equals("Escaped"));
        assertEquals(parsed, reader.readClass(List.of(file), "Escaped").isPresent());
    }

    @Test
    @DisplayName("Should only derive a declared name from plain identifiers")
    void shouldDeriveDeclaredSimpleName() {
        assertEquals("Target", SourceReader.declaredSimpleName("Target"));
        assertEquals("Target", SourceReader.declaredSimpleName("com.app.Holder$Target"));
        assertEquals("_Old2", SourceReader.declaredSimpleName("pkg._Old2"));
        assertNull(SourceReader.declaredSimpleName("Outer$run$1$Local$1"));
        assertNull(SourceReader.declaredSimpleName("Map.Entry<K,V>"));
        assertNull(SourceReader.declaredSimpleName("Señal"));
        assertNull(SourceReader.declaredSimpleName("pkg."));
    }

    @Test
    @DisplayName("Should return null for nonexistent method")
    void shouldReturnNullForMissingMethod() throws IOException {
        Path file = writeFile("Svc.java", """
                public class Svc { public void exists() {} }
                """);
        assertTrue(reader.readMethod(List.of(file), "Svc", "missing").isEmpty());
    }

    @Test
    @DisplayName("Should include file path and line numbers in result")
    void shouldIncludeMetadata() throws IOException {
        Path file = writeFile("Meta.java", """
                package com.app;
                public class Meta {
                    public int calc(int x) {
                        return x * 2;
                    }
                }
                """);

        var result = reader.readMethod(List.of(file), "Meta", "calc");
        assertTrue(result.isPresent());
        assertNotNull(result.get().file());
        assertTrue(result.get().startLine() > 0);
        assertTrue(result.get().endLine() >= result.get().startLine());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
