package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.model.DependencyResult;

class DependencyAnalyzerTest {

    @TempDir
    Path tempDir;

    private DependencyAnalyzer analyzer = new DependencyAnalyzer();

    @Test
    @DisplayName("Detects import dependencies")
    void detectImports() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                import com.app.repo.OrderRepository;
                import java.util.List;
                public class OrderService {
                    public void process() {}
                }
                """);

        DependencyResult result = analyzer.analyze(List.of(file), "OrderService");
        assertNotNull(result, "Should find OrderService");
        assertFalse(result.imports().isEmpty(), "Should have imports");
    }

    @Test
    @DisplayName("Detects field dependencies")
    void detectFields() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                public class OrderService {
                    private OrderRepository repo;
                    private String name;
                    public void process() {}
                }
                """);

        DependencyResult result = analyzer.analyze(List.of(file), "OrderService");
        assertNotNull(result);
        assertFalse(result.fieldDependencies().isEmpty(), "Should detect field dependencies");
        assertTrue(result.fieldDependencies().stream()
                .anyMatch(f -> f.type().equals("OrderRepository")),
                "Should detect OrderRepository field");
    }

    @Test
    @DisplayName("Detects constructor parameter dependencies")
    void detectConstructorParams() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                public class OrderService {
                    private final OrderRepository repo;
                    public OrderService(OrderRepository repo) {
                        this.repo = repo;
                    }
                }
                """);

        DependencyResult result = analyzer.analyze(List.of(file), "OrderService");
        assertNotNull(result);
        assertFalse(result.constructorDependencies().isEmpty(), "Should detect constructor params");
    }

    @Test
    @DisplayName("Returns null for class not found")
    void classNotFound() throws Exception {
        Path file = tempDir.resolve("Other.java");
        Files.writeString(file, "public class Other {}");

        DependencyResult result = analyzer.analyze(List.of(file), "NonExistent");
        assertNull(result, "Should return null for class not found");
    }
}
