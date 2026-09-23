package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AccuracyThresholdsTest {

    @Test
    void acceptsInclusiveRatioLimitsAndZeroCounts() {
        assertDoesNotThrow(() -> thresholds(0.0, 1.0, 0.0, 1.0, 0, 0, 0, 0));
    }

    @Test
    void rejectsRatiosOutsideTheUnitInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(-0.01, 1.0, 1.0, 1.0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.01, 1.0, 1.0, 0, 0, 0, 0));
    }

    @Test
    void rejectsNonFiniteRatios() {
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(Double.NaN, 1.0, 1.0, 1.0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, Double.POSITIVE_INFINITY, 1.0, 1.0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.0, Double.NEGATIVE_INFINITY, 1.0, 0, 0, 0, 0));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.0, 1.0, 1.0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.0, 1.0, 1.0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.0, 1.0, 1.0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> thresholds(1.0, 1.0, 1.0, 1.0, 0, 0, 0, -1));
    }

    private static AccuracyThresholds thresholds(
            double symbolPrecision,
            double symbolRecall,
            double edgePrecision,
            double edgeRecall,
            int maxSymbolFalsePositives,
            int maxSymbolFalseNegatives,
            int maxEdgeFalsePositives,
            int maxEdgeFalseNegatives) {
        return new AccuracyThresholds(
                symbolPrecision,
                symbolRecall,
                edgePrecision,
                edgeRecall,
                maxSymbolFalsePositives,
                maxSymbolFalseNegatives,
                maxEdgeFalsePositives,
                maxEdgeFalseNegatives);
    }
}
