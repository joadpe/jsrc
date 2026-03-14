package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HierarchyFormatterTest {

    @Test
    @DisplayName("JsonFormatter should output hierarchy as JSON object")
    void jsonShouldOutputHierarchy() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            HierarchyResult result = new HierarchyResult(
                    "OrderService", "BaseService",
                    List.of("Serializable", "Comparable"),
                    List.of("PremiumOrderService", "BulkOrderService"),
                    List.of());
            fmt.printHierarchy(result);
        });
        assertTrue(out.startsWith("{"));
        assertTrue(out.contains("\"target\":\"OrderService\""));
        assertTrue(out.contains("\"superClass\":\"BaseService\""));
        assertTrue(out.contains("\"Serializable\""));
        assertTrue(out.contains("\"PremiumOrderService\""));
    }

    @Test
    @DisplayName("JsonFormatter should handle class with no hierarchy")
    void jsonShouldHandleOrphan() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            HierarchyResult result = new HierarchyResult(
                    "Utils", "", List.of(), List.of(), List.of());
            fmt.printHierarchy(result);
        });
        assertTrue(out.contains("\"target\":\"Utils\""));
        assertTrue(out.contains("\"interfaces\":[]"));
        assertTrue(out.contains("\"subClasses\":[]"));
    }

    @Test
    @DisplayName("TextFormatter should output readable hierarchy")
    void textShouldOutputReadable() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            HierarchyResult result = new HierarchyResult(
                    "OrderService", "BaseService",
                    List.of("Serializable"),
                    List.of("PremiumOrderService"),
                    List.of("OrderProcessor"));
            fmt.printHierarchy(result);
        });
        assertTrue(out.contains("OrderService"));
        assertTrue(out.contains("BaseService"));
        assertTrue(out.contains("Serializable"));
        assertTrue(out.contains("PremiumOrderService"));
    }

    private String captureOutput(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return baos.toString().trim();
    }
}
