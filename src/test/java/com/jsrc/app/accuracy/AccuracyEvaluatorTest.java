package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccuracyEvaluatorTest {

    @Test
    void calculatesPrecisionRecallAndClassificationSets() {
        AccuracyMetrics metrics = AccuracyEvaluator.evaluate(
                Set.of("expected-and-observed", "missing"),
                Set.of("expected-and-observed", "unexpected"));

        assertEquals(Set.of("expected-and-observed"), metrics.truePositives());
        assertEquals(Set.of("unexpected"), metrics.falsePositives());
        assertEquals(Set.of("missing"), metrics.falseNegatives());
        assertEquals(0.5, metrics.precision());
        assertEquals(0.5, metrics.recall());
    }

    @Test
    void treatsEmptyExpectedAndObservedSetsAsPerfect() {
        AccuracyMetrics metrics = AccuracyEvaluator.evaluate(Set.of(), Set.of());

        assertEquals(1.0, metrics.precision());
        assertEquals(1.0, metrics.recall());
    }

    @Test
    void reportsZeroRecallWhenAllExpectedItemsAreMissing() {
        AccuracyMetrics metrics = AccuracyEvaluator.evaluate(Set.of("missing"), Set.of());

        assertEquals(1.0, metrics.precision());
        assertEquals(0.0, metrics.recall());
        assertEquals(Set.of("missing"), metrics.falseNegatives());
    }
}
