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
