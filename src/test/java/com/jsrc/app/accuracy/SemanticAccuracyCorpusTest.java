package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("accuracy")
class SemanticAccuracyCorpusTest {

    private static final Path CORPUS_ROOT = Path.of("src/test/resources/semantic-corpus");

    @TempDir
    Path tempDir;

    @Test
    void meetsVersionedAccuracyBaseline() throws Exception {
        List<CorpusCase> cases = CorpusRepository.loadAll(CORPUS_ROOT);
        AccuracyBaseline baseline = AccuracyBaseline.load(
                CORPUS_ROOT.resolve("baseline.properties"));

        assertFalse(cases.isEmpty(), "Semantic corpus must contain cases");
        assertTrue(cases.stream().map(CorpusCase::sourceVersion).collect(Collectors.toSet())
                        .containsAll(Set.of(8, 11, 17, 21)),
                "Semantic corpus must cover Java 8, 11, 17 and 21");

        for (CorpusCase corpusCase : cases) {
            verifyCase(corpusCase, baseline.thresholdsFor(corpusCase.capability()));
        }
    }

    private void verifyCase(CorpusCase corpusCase, AccuracyThresholds thresholds)
            throws Exception {
        Path destination = tempDir.resolve(corpusCase.id());
        List<Path> javaFiles = CorpusMaterializer.materialize(corpusCase.root(), destination);
        SemanticObservation observed = IndexedSemanticObserver.observe(destination, javaFiles);
        AccuracyMetrics symbolMetrics = AccuracyEvaluator.evaluate(
                corpusCase.expectedSymbols(), observed.symbols());
        AccuracyMetrics edgeMetrics = AccuracyEvaluator.evaluate(
                corpusCase.expectedEdges(), observed.edges());

        assertTrue(Collections.disjoint(
                        corpusCase.expectedEdges(), corpusCase.forbiddenEdges()),
                () -> corpusCase.id() + " declares the same edge as expected and forbidden");
        assertNoForbiddenEdges(
                corpusCase.id(), observed.edges(), corpusCase.forbiddenEdges());
        assertAtLeast(corpusCase.id(), "symbol precision",
                symbolMetrics.precision(), thresholds.symbolPrecision());
        assertAtLeast(corpusCase.id(), "symbol recall",
                symbolMetrics.recall(), thresholds.symbolRecall());
        assertAtLeast(corpusCase.id(), "edge precision",
                edgeMetrics.precision(), thresholds.edgePrecision());
        assertAtLeast(corpusCase.id(), "edge recall",
                edgeMetrics.recall(), thresholds.edgeRecall());
        assertAtMost(corpusCase.id(), "symbol false positives",
                symbolMetrics.falsePositives().size(), thresholds.maxSymbolFalsePositives());
        assertAtMost(corpusCase.id(), "symbol false negatives",
                symbolMetrics.falseNegatives().size(), thresholds.maxSymbolFalseNegatives());
        assertAtMost(corpusCase.id(), "edge false positives",
                edgeMetrics.falsePositives().size(), thresholds.maxEdgeFalsePositives());
        assertAtMost(corpusCase.id(), "edge false negatives",
                edgeMetrics.falseNegatives().size(), thresholds.maxEdgeFalseNegatives());
    }

    private static void assertAtLeast(
            String caseId, String metric, double actual, double minimum) {
        assertTrue(actual >= minimum,
                () -> caseId + " " + metric + " expected >= " + minimum + " but was " + actual);
    }

    private static void assertAtMost(
            String caseId, String metric, int actual, int maximum) {
        assertTrue(actual <= maximum,
                () -> caseId + " " + metric + " expected <= " + maximum + " but was " + actual);
    }

    static void assertNoForbiddenEdges(
            String caseId, Set<String> observedEdges, Set<String> forbiddenEdges) {
        Set<String> observedForbiddenEdges = new java.util.HashSet<>(observedEdges);
        observedForbiddenEdges.retainAll(forbiddenEdges);
        assertTrue(observedForbiddenEdges.isEmpty(),
                () -> caseId + " observed forbidden edges " + observedForbiddenEdges);
    }
}
