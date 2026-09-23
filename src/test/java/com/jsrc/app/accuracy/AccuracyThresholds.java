package com.jsrc.app.accuracy;

record AccuracyThresholds(
        double symbolPrecision,
        double symbolRecall,
        double edgePrecision,
        double edgeRecall,
        int maxSymbolFalsePositives,
        int maxSymbolFalseNegatives,
        int maxEdgeFalsePositives,
        int maxEdgeFalseNegatives) {

    AccuracyThresholds {
        validate(symbolPrecision, "symbolPrecision");
        validate(symbolRecall, "symbolRecall");
        validate(edgePrecision, "edgePrecision");
        validate(edgeRecall, "edgeRecall");
        validateCount(maxSymbolFalsePositives, "maxSymbolFalsePositives");
        validateCount(maxSymbolFalseNegatives, "maxSymbolFalseNegatives");
        validateCount(maxEdgeFalsePositives, "maxEdgeFalsePositives");
        validateCount(maxEdgeFalseNegatives, "maxEdgeFalseNegatives");
    }

    private static void validate(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and between 0.0 and 1.0");
        }
    }

    private static void validateCount(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
