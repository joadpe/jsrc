package com.jsrc.app.accuracy;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class AccuracyEvaluator {

    private AccuracyEvaluator() {}

    static AccuracyMetrics evaluate(Set<String> expected, Set<String> observed) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(observed, "observed must not be null");

        Set<String> truePositives = intersection(expected, observed);
        Set<String> falsePositives = difference(observed, expected);
        Set<String> falseNegatives = difference(expected, observed);
        return new AccuracyMetrics(truePositives, falsePositives, falseNegatives);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }
}
