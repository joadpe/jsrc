package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticAccuracyGateTest {

    @Test
    void rejectsAnObservedForbiddenEdge() {
        assertThrows(AssertionError.class, () -> SemanticAccuracyCorpusTest.assertNoForbiddenEdges(
                "forbidden-edge", Set.of("a.A#run()->b.B#call()"),
                Set.of("a.A#run()->b.B#call()")));
    }

    @Test
    void acceptsObservationsWithoutForbiddenEdges() {
        assertDoesNotThrow(() -> SemanticAccuracyCorpusTest.assertNoForbiddenEdges(
                "allowed-edge", Set.of("a.A#run()->b.B#call()"),
                Set.of("a.A#run()->b.B#other()")));
    }
}
