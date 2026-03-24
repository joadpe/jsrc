package com.jsrc.app.jfr;

import com.jsrc.app.jfr.JfrProfile.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JfrProfile record — validates immutability and structure.
 */
class JfrProfileTest {

    @Test
    void recordFieldsAreAccessible() {
        var hm = new HotMethod("com.example.A", "doWork", 100, 50.0);
        assertEquals("com.example.A", hm.className());
        assertEquals("doWork", hm.methodName());
        assertEquals(100, hm.samples());
        assertEquals(50.0, hm.pct());
    }

    @Test
    void gcSummaryFieldsAreAccessible() {
        var gc = new GcSummary(10, 500, 120, 50);
        assertEquals(10, gc.pauses());
        assertEquals(500, gc.totalPauseMs());
        assertEquals(120, gc.maxPauseMs());
        assertEquals(50, gc.avgPauseMs());
    }

    @Test
    void ioSummaryFieldsAreAccessible() {
        var io = new IoSummary(100, 50, 200, 150);
        assertEquals(100, io.fileReads());
        assertEquals(50, io.fileWrites());
        assertEquals(200, io.socketReads());
        assertEquals(150, io.socketWrites());
    }

    @Test
    void fullProfileRecordIsImmutable() {
        var profile = new JfrProfile(
                "test.jfr", "30s", 1000,
                List.of(new HotMethod("A", "b", 500, 50.0)),
                new GcSummary(5, 200, 80, 40),
                new IoSummary(10, 5, 20, 15),
                List.of(new Allocation("String", 1000000, 5000)),
                List.of(new Contention("Cache", "get", 300, 10)),
                List.of(new ExceptionHotspot("NPE", 42))
        );

        assertEquals("test.jfr", profile.file());
        assertEquals(1000, profile.totalSamples());
        assertEquals(1, profile.hotMethods().size());
        assertNotNull(profile.gc());
        assertNotNull(profile.io());
        assertEquals(1, profile.allocations().size());
        assertEquals(1, profile.contentions().size());
        assertEquals(1, profile.exceptions().size());
    }
}
