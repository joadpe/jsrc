package com.jsrc.app.accuracy;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

record SemanticObservation(Set<String> symbols, Set<String> edges) {

    SemanticObservation {
        symbols = immutableSorted(symbols);
        edges = immutableSorted(edges);
    }

    private static Set<String> immutableSorted(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }
}
