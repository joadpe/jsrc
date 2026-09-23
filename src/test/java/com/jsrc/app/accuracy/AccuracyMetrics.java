package com.jsrc.app.accuracy;

import java.util.Set;

record AccuracyMetrics(
        Set<String> truePositives,
        Set<String> falsePositives,
        Set<String> falseNegatives) {

    AccuracyMetrics {
        truePositives = Set.copyOf(truePositives);
        falsePositives = Set.copyOf(falsePositives);
        falseNegatives = Set.copyOf(falseNegatives);
    }

    double precision() {
        int observed = truePositives.size() + falsePositives.size();
        return observed == 0 ? 1.0 : (double) truePositives.size() / observed;
    }

    double recall() {
        int expected = truePositives.size() + falseNegatives.size();
        return expected == 0 ? 1.0 : (double) truePositives.size() / expected;
    }
}
