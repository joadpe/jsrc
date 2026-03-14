package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;

class ClassSummaryFormatterTest {

    @Test
    @DisplayName("JsonFormatter should output class summary with method signatures")
    void jsonShouldOutputSummary() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = buildSampleClass();
            fmt.printClassSummary(ci, Path.of("OrderService.java"));
        });
        assertTrue(out.startsWith("{"));
        assertTrue(out.contains("\"name\":\"OrderService\""));
        assertTrue(out.contains("\"packageName\":\"com.app.service\""));
        assertTrue(out.contains("\"superClass\":\"BaseService\""));
        assertTrue(out.contains("\"interfaces\":[\"Serializable\"]"));
        assertTrue(out.contains("\"methods\""));
        assertTrue(out.contains("\"signature\":"));
        assertFalse(out.contains("\"content\":"), "Summary should not include method bodies");
    }

    @Test
    @DisplayName("JsonFormatter summary methods should have signature but not content")
    void jsonMethodsShouldBeCompact() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = buildSampleClass();
            fmt.printClassSummary(ci, Path.of("OrderService.java"));
        });
        // Should have method entries with signatures
        assertTrue(out.contains("\"name\":\"create\""));
        assertTrue(out.contains("\"name\":\"validate\""));
        // Should NOT have full content
        assertFalse(out.contains("return null"), "Should not include method body");
    }

    @Test
    @DisplayName("TextFormatter should output readable class summary")
    void textShouldOutputReadable() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            ClassInfo ci = buildSampleClass();
            fmt.printClassSummary(ci, Path.of("OrderService.java"));
        });
        assertTrue(out.contains("OrderService"));
        assertTrue(out.contains("com.app.service"));
        assertTrue(out.contains("create"));
        assertTrue(out.contains("validate"));
    }

    @Test
    @DisplayName("JsonFormatter summary should include annotations on class")
    void jsonShouldIncludeClassAnnotations() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            ClassInfo ci = buildSampleClass();
            fmt.printClassSummary(ci, Path.of("OrderService.java"));
        });
        assertTrue(out.contains("\"annotations\""));
        assertTrue(out.contains("\"Service\""));
    }

    private ClassInfo buildSampleClass() {
        MethodInfo create = new MethodInfo("create", "OrderService", 15, 30,
                "Order", List.of("public"),
                List.of(new ParameterInfo("String", "name"), new ParameterInfo("int", "qty")),
                "public Order create(String name, int qty) { return null; }",
                List.of(), List.of(), List.of(), "/** Creates an order */");
        MethodInfo validate = MethodInfo.basic("validate", "OrderService", 32, 40,
                "boolean", List.of("private"),
                List.of(new ParameterInfo("Order", "order")),
                "private boolean validate(Order order) { return true; }");

        return new ClassInfo("OrderService", "com.app.service", 10, 50,
                List.of("public"), List.of(create, validate), "BaseService",
                List.of("Serializable"), List.of(AnnotationInfo.marker("Service")), false);
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
