package com.jsrc.app.jfr;

import com.jsrc.app.jfr.JfrCorrelator.CorrelatedMethod;
import com.jsrc.app.jfr.JfrCorrelator.CorrelationResult;
import com.jsrc.app.jfr.JfrProfile.HotMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JfrCorrelator — correlates JFR data with jsrc index.
 */
class JfrCorrelatorTest {

    @Test
    void correlateWithNullIndexReturnsUnenrichedMethods() {
        var profile = createProfile(List.of(
                new HotMethod("com.example.OrderService", "processOrder", 100, 50.0)
        ));

        CorrelationResult result = JfrCorrelator.correlate(profile, null, ".");

        assertEquals(1, result.methods().size());
        CorrelatedMethod m = result.methods().get(0);
        assertEquals("com.example.OrderService", m.className());
        assertEquals("processOrder", m.methodName());
        assertFalse(m.inIndex());
        assertNull(m.file());
        assertTrue(result.correlations().isEmpty());
    }

    @Test
    void throwsOnNullProfile() {
        assertThrows(NullPointerException.class, () ->
                JfrCorrelator.correlate(null, null, "."));
    }

    @Test
    void simpleClassNameExtractsCorrectly() {
        assertEquals("OrderService", JfrCorrelator.simpleClassName("com.example.OrderService"));
        assertEquals("OrderService", JfrCorrelator.simpleClassName("OrderService"));
        assertEquals("", JfrCorrelator.simpleClassName(null));
    }

    @Test
    void preservesMethodOrder() {
        var profile = createProfile(List.of(
                new HotMethod("com.example.A", "first", 100, 50.0),
                new HotMethod("com.example.B", "second", 50, 25.0),
                new HotMethod("com.example.C", "third", 25, 12.5)
        ));

        CorrelationResult result = JfrCorrelator.correlate(profile, null, ".");

        assertEquals(3, result.methods().size());
        assertEquals("first", result.methods().get(0).methodName());
        assertEquals("second", result.methods().get(1).methodName());
        assertEquals("third", result.methods().get(2).methodName());
    }

    @Test
    void emptyProfileReturnsEmptyResult() {
        var profile = createProfile(List.of());

        CorrelationResult result = JfrCorrelator.correlate(profile, null, ".");

        assertTrue(result.methods().isEmpty());
        assertTrue(result.correlations().isEmpty());
    }

    private JfrProfile createProfile(List<HotMethod> methods) {
        long total = methods.stream().mapToLong(HotMethod::samples).sum();
        return new JfrProfile("test.jfr", "30s", total, methods,
                null, null, List.of(), List.of(), List.of());
    }
}
