package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexPhaseMetricsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsNamedDurationsAndCountsWithoutSourcePaths() throws Exception {
        Path trace = temporaryDirectory.resolve("phases.json");
        IndexPhaseMetrics metrics = new IndexPhaseMetrics();

        metrics.record("build.hash", 100);
        metrics.record("build.hash", 200);
        metrics.record("build.parse_extract", 500);
        metrics.count("build.reindexed", 2);
        metrics.count("build.reindexed", 1);
        metrics.write(trace);

        String json = Files.readString(trace);
        assertTrue(json.contains("\"build.hash\":300"));
        assertTrue(json.contains("\"build.parse_extract\":500"));
        assertTrue(json.contains("\"build.reindexed\":3"));
        assertFalse(json.contains(temporaryDirectory.toString()));
    }
}
