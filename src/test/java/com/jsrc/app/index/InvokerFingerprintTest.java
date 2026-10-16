package com.jsrc.app.index;

import com.jsrc.app.config.ArchitectureConfig.InvokerDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The fingerprint decides whether unchanged entries keep their reflective edges. */
class InvokerFingerprintTest {
    private static final InvokerDef BASE =
            new InvokerDef("invoke", 0, "handler", List.of("View", "Form"));

    @Test
    void noInvokersGiveAnEmptyFingerprint() {
        assertEquals("", CodebaseIndex.invokerFingerprint(List.of()));
        assertNotEquals("", CodebaseIndex.invokerFingerprint(List.of(BASE)));
    }

    @Test
    void everyInvokerComponentChangesTheFingerprint() {
        // A new InvokerDef component must join the fingerprint and get a case below.
        assertEquals(4, InvokerDef.class.getRecordComponents().length);
        Map<String, InvokerDef> variants = new LinkedHashMap<>();
        variants.put("method", new InvokerDef("call", 0, "handler", List.of("View", "Form")));
        variants.put("targetArg", new InvokerDef("invoke", 1, "handler", List.of("View", "Form")));
        variants.put("resolveClass",
                new InvokerDef("invoke", 0, "service", List.of("View", "Form")));
        variants.put("callerSuffixes", new InvokerDef("invoke", 0, "handler", List.of("View")));

        String base = CodebaseIndex.invokerFingerprint(List.of(BASE));
        variants.forEach((component, variant) -> assertNotEquals(
                base, CodebaseIndex.invokerFingerprint(List.of(variant)), component));
    }

    @Test
    void invokerOrderChangesTheFingerprint() {
        // A later invoker for the same method replaces an earlier one.
        var other = new InvokerDef("invoke", 0, "service", List.of("View"));

        assertNotEquals(CodebaseIndex.invokerFingerprint(List.of(BASE, other)),
                CodebaseIndex.invokerFingerprint(List.of(other, BASE)));
    }

    @Test
    void suffixOrderChangesTheFingerprint() {
        // The first suffix that matches the caller picks the target.
        assertNotEquals(
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("View", "TableView")))),
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("TableView", "View")))));
    }

    @Test
    void missingSuffixesFingerprintLikeAnEmptyList() {
        assertEquals(
                CodebaseIndex.invokerFingerprint(List.of(new InvokerDef("invoke", 0, "handler", null))),
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of()))));
    }

    @Test
    void fieldBoundariesStayDistinct() {
        assertNotEquals(
                CodebaseIndex.invokerFingerprint(List.of(new InvokerDef("ab", 0, "c", List.of()))),
                CodebaseIndex.invokerFingerprint(List.of(new InvokerDef("a", 0, "bc", List.of()))));
        // The target argument between them does not delimit the fields on its own.
        assertNotEquals(
                CodebaseIndex.invokerFingerprint(List.of(new InvokerDef("a", 0, "0", List.of()))),
                CodebaseIndex.invokerFingerprint(List.of(new InvokerDef("a0", 0, "", List.of()))));
        assertNotEquals(
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("A,B")))),
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("A", "B")))));
        // Trailing suffixes are not read as the fields of a next invoker.
        assertNotEquals(
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("View", "run", "1", "service")))),
                CodebaseIndex.invokerFingerprint(List.of(
                        new InvokerDef("invoke", 0, "handler", List.of("View")),
                        new InvokerDef("run", 1, "service", List.of()))));
    }
}
