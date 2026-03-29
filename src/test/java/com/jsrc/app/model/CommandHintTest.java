package com.jsrc.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandHintTest {

    @Test
    @DisplayName("CommandHint record holds command and description")
    void basicConstruction() {
        var hint = new CommandHint("callers findMethods", "Who calls this method?");
        assertEquals("callers findMethods", hint.command());
        assertEquals("Who calls this method?", hint.description());
    }

    @Test
    @DisplayName("CommandHint equality by value")
    void equality() {
        var a = new CommandHint("read Foo", "Read class");
        var b = new CommandHint("read Foo", "Read class");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("HintContext resolves template with className and methodName")
    void resolveClassAndMethod() {
        var ctx = HintContext.forMethod("HybridJavaParser", "findMethods",
                List.of("isNearAnyTargetLine", "toRichMethodInfo"));
        var hint = CommandHint.resolve("callers {method}", "Who calls {method}?", ctx);
        assertEquals("callers findMethods", hint.command());
        assertEquals("Who calls findMethods?", hint.description());
    }

    @Test
    @DisplayName("HintContext resolves template with class only")
    void resolveClassOnly() {
        var ctx = HintContext.forClass("HybridJavaParser",
                List.of("findMethods", "isNearAnyTargetLine"));
        var hint = CommandHint.resolve("smells {class}", "Check smells in {class}", ctx);
        assertEquals("smells HybridJavaParser", hint.command());
        assertEquals("Check smells in HybridJavaParser", hint.description());
    }

    @Test
    @DisplayName("HintContext forOverview resolves topClass")
    void resolveTopClass() {
        var ctx = HintContext.forOverview(List.of("IndexedCodebase", "CallGraphBuilder"),
                List.of("com.jsrc.app.parser", "com.jsrc.app.analysis"));
        var hint = CommandHint.resolve("read {topClass}", "Read the top class", ctx);
        assertEquals("read IndexedCodebase", hint.command());
    }

    @Test
    @DisplayName("Unresolved placeholders pass through as-is")
    void unresolvedPlaceholders() {
        var ctx = HintContext.forClass("Foo", List.of());
        var hint = CommandHint.resolve("test-for {class}.{method}", "Find tests", ctx);
        // method not in context → stays as placeholder
        assertEquals("test-for Foo.{method}", hint.command());
    }

    @Test
    @DisplayName("Pattern hint with METHOD placeholder stays unresolved")
    void patternHint() {
        var ctx = HintContext.forClass("HybridJavaParser",
                List.of("findMethods", "isNearAnyTargetLine"));
        var hint = CommandHint.resolve("read {class}.METHOD",
                "Read a method (see methods list)", ctx);
        assertEquals("read HybridJavaParser.METHOD", hint.command());
    }

    @Test
    @DisplayName("HintContext forCallers resolves caller data")
    void resolveCallerData() {
        var ctx = HintContext.forCallers("findMethods", "HybridJavaParser",
                "SourceReader", "readMethod");
        var hint = CommandHint.resolve("read {callerClass}.{callerMethod}",
                "Read the caller", ctx);
        assertEquals("read SourceReader.readMethod", hint.command());
    }

    @Test
    @DisplayName("HintContext forSearch resolves firstMatch")
    void resolveFirstMatch() {
        var ctx = HintContext.forSearch("parser",
                List.of("HybridJavaParser", "TreeSitterParser"));
        var hint = CommandHint.resolve("read {firstMatch}", "Read the match", ctx);
        assertEquals("read HybridJavaParser", hint.command());
    }
}
