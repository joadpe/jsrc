package com.jsrc.app.output;

import static org.junit.jupiter.api.Assertions.*;

import com.jsrc.app.model.AnnotationMatch;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

class AnnotationSearchFormatterTest {

    @Test
    @DisplayName("JsonFormatter should output annotation matches as JSON array")
    void jsonShouldOutputArray() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            AnnotationMatch methodMatch = new AnnotationMatch(
                    "method", "process", "OrderService",
                    Path.of("OrderService.java"), 15,
                    new AnnotationInfo("Transactional", Map.of("readOnly", "true")));
            AnnotationMatch classMatch = new AnnotationMatch(
                    "class", "OrderService", "OrderService",
                    Path.of("OrderService.java"), 5,
                    AnnotationInfo.marker("Service"));
            fmt.printAnnotationMatches(List.of(methodMatch, classMatch));
        });
        assertTrue(out.startsWith("["));
        assertTrue(out.contains("\"type\":\"method\""));
        assertTrue(out.contains("\"type\":\"class\""));
        assertTrue(out.contains("\"name\":\"process\""));
        assertTrue(out.contains("\"Transactional\""));
        assertTrue(out.contains("\"readOnly\""));
    }

    @Test
    @DisplayName("JsonFormatter should output empty array for no matches")
    void jsonShouldOutputEmptyArray() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new JsonFormatter();
            fmt.printAnnotationMatches(List.of());
        });
        assertEquals("[]", out.trim());
    }

    @Test
    @DisplayName("TextFormatter should output readable annotation matches")
    void textShouldOutputReadable() {
        String out = captureOutput(() -> {
            OutputFormatter fmt = new TextFormatter();
            AnnotationMatch match = new AnnotationMatch(
                    "method", "save", "Repository",
                    Path.of("Repository.java"), 20,
                    AnnotationInfo.marker("Override"));
            fmt.printAnnotationMatches(List.of(match));
        });
        assertTrue(out.contains("save"));
        assertTrue(out.contains("Repository"));
        assertTrue(out.contains("@Override"));
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
