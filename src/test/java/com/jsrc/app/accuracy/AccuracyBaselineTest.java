package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccuracyBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsVersionedThresholdsPerCapability() throws Exception {
        Path baselineFile = tempDir.resolve("baseline.properties");
        Files.writeString(baselineFile, """
                schemaVersion=1
                SYMBOL_RESOLUTION.symbolPrecision=1.0
                SYMBOL_RESOLUTION.symbolRecall=0.8
                SYMBOL_RESOLUTION.edgePrecision=0.9
                SYMBOL_RESOLUTION.edgeRecall=0.6
                SYMBOL_RESOLUTION.maxSymbolFalsePositives=0
                SYMBOL_RESOLUTION.maxSymbolFalseNegatives=1
                SYMBOL_RESOLUTION.maxEdgeFalsePositives=2
                SYMBOL_RESOLUTION.maxEdgeFalseNegatives=3
                """);

        AccuracyBaseline baseline = AccuracyBaseline.load(baselineFile);

        assertEquals(1, baseline.schemaVersion());
        assertEquals(new AccuracyThresholds(1.0, 0.8, 0.9, 0.6, 0, 1, 2, 3),
                baseline.thresholdsFor(SemanticCapability.SYMBOL_RESOLUTION));
    }

    @Test
    void rejectsCapabilityWithoutCompleteThresholds() throws Exception {
        Path baselineFile = tempDir.resolve("baseline.properties");
        Files.writeString(baselineFile, "schemaVersion=1\n");
        AccuracyBaseline baseline = AccuracyBaseline.load(baselineFile);

        assertThrows(IllegalArgumentException.class,
                () -> baseline.thresholdsFor(SemanticCapability.CALL_GRAPH));
    }
}
