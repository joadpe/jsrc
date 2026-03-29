package com.jsrc.app.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeapDumpRunnerTest {

    // ---- parseHistogram ----

    @Test
    @DisplayName("Parse standard jcmd class histogram output")
    void parseHistogramStandard() {
        var lines = List.of(
                " num     #instances         #bytes  class name (module)",
                "-------------------------------------------------------",
                "   1:        450000      180000000  [B (java.base@22)",
                "   2:        320000       25600000  java.lang.String (java.base@22)",
                "   3:        150000       12000000  com.app.OrderEntity",
                "   4:         50000        4000000  java.util.HashMap$Node (java.base@22)",
                "Total       1500000      250000000"
        );

        var entries = HeapDumpRunner.parseHistogram(lines, 10);
        assertEquals(4, entries.size());

        assertTrue(entries.get(0).className().startsWith("[B"));
        assertEquals(450000, entries.get(0).instances());
        assertEquals(180000000, entries.get(0).bytes());

        assertTrue(entries.get(1).className().startsWith("java.lang.String"));
        assertEquals("com.app.OrderEntity", entries.get(2).className());
    }

    @Test
    @DisplayName("Parse histogram respects topN limit")
    void parseHistogramTopN() {
        var lines = List.of(
                " num     #instances         #bytes  class name",
                "   1:        450000      180000000  [B",
                "   2:        320000       25600000  java.lang.String",
                "   3:        150000       12000000  com.app.OrderEntity",
                "   4:         50000        4000000  java.util.HashMap$Node"
        );

        var entries = HeapDumpRunner.parseHistogram(lines, 2);
        assertEquals(2, entries.size());
        assertEquals("[B", entries.get(0).className());
        assertEquals("java.lang.String", entries.get(1).className());
    }

    @Test
    @DisplayName("Parse histogram handles empty input")
    void parseHistogramEmpty() {
        var entries = HeapDumpRunner.parseHistogram(List.of(), 10);
        assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("Parse histogram skips header and total lines")
    void parseHistogramSkipsNonData() {
        var lines = List.of(
                " num     #instances         #bytes  class name",
                "-------------------------------------------------------",
                "   1:        100           8000  java.lang.Object",
                "Total          100           8000"
        );

        var entries = HeapDumpRunner.parseHistogram(lines, 10);
        assertEquals(1, entries.size());
        assertEquals("java.lang.Object", entries.getFirst().className());
    }

    @Test
    @DisplayName("Parse histogram handles class names with spaces (inner classes)")
    void parseHistogramModuleInfo() {
        var lines = List.of(
                "   1:        100           8000  java.util.HashMap$Node (java.base@22)"
        );

        var entries = HeapDumpRunner.parseHistogram(lines, 10);
        assertEquals(1, entries.size());
        // Class name includes module info as part of the string
        assertTrue(entries.getFirst().className().startsWith("java.util.HashMap$Node"));
    }

    // ---- computeDelta ----

    @Test
    @DisplayName("Compute delta identifies growing classes")
    void computeDeltaGrowth() {
        var before = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100000, 8000000),
                new HeapDumpRunner.HistogramEntry("com.app.Order", 50000, 4000000),
                new HeapDumpRunner.HistogramEntry("[B", 200000, 16000000)
        );
        var after = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100500, 8040000),
                new HeapDumpRunner.HistogramEntry("com.app.Order", 80000, 6400000),
                new HeapDumpRunner.HistogramEntry("[B", 200100, 16008000)
        );

        var deltas = HeapDumpRunner.computeDelta(before, after, 100);
        // Only Order grew by >= 100 instances (30000 growth)
        // String grew by 500, [B by 100
        assertEquals(3, deltas.size());

        // Sorted by byte growth descending
        assertEquals("com.app.Order", deltas.get(0).className());
        assertEquals(30000, deltas.get(0).instanceGrowth());
        assertEquals(2400000, deltas.get(0).byteGrowth());
    }

    @Test
    @DisplayName("Compute delta filters by minimum growth")
    void computeDeltaMinGrowth() {
        var before = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100000, 8000000),
                new HeapDumpRunner.HistogramEntry("com.app.Order", 50000, 4000000)
        );
        var after = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100010, 8000800),
                new HeapDumpRunner.HistogramEntry("com.app.Order", 80000, 6400000)
        );

        var deltas = HeapDumpRunner.computeDelta(before, after, 1000);
        // Only Order grew by >= 1000
        assertEquals(1, deltas.size());
        assertEquals("com.app.Order", deltas.getFirst().className());
    }

    @Test
    @DisplayName("Compute delta handles new classes in after snapshot")
    void computeDeltaNewClass() {
        var before = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100000, 8000000)
        );
        var after = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100000, 8000000),
                new HeapDumpRunner.HistogramEntry("com.app.LeakyCache", 5000, 400000)
        );

        var deltas = HeapDumpRunner.computeDelta(before, after, 100);
        assertEquals(1, deltas.size());
        assertEquals("com.app.LeakyCache", deltas.getFirst().className());
        assertEquals(0, deltas.getFirst().instancesBefore());
        assertEquals(5000, deltas.getFirst().instancesAfter());
    }

    @Test
    @DisplayName("Compute delta returns empty when no growth")
    void computeDeltaNoGrowth() {
        var snapshot = List.of(
                new HeapDumpRunner.HistogramEntry("java.lang.String", 100000, 8000000)
        );

        var deltas = HeapDumpRunner.computeDelta(snapshot, snapshot, 1);
        assertTrue(deltas.isEmpty());
    }

    // ---- parseHeapInfo ----

    @Test
    @DisplayName("Parse heap info preserves raw output")
    void parseHeapInfoRaw() {
        var lines = List.of(
                "garbage-first heap   total 262144K, used 45000K",
                " region size 1024K, 30 young, 5 survivors",
                "Metaspace used 35000K, committed 36000K"
        );

        var info = HeapDumpRunner.parseHeapInfo(lines);
        assertNotNull(info.get("raw"));
        assertTrue(info.get("raw").toString().contains("garbage-first"));
    }
}
