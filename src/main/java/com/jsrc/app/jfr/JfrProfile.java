package com.jsrc.app.jfr;

import java.util.List;

/**
 * Parsed JFR recording data. Immutable container for profiling results.
 *
 * @param file         source JFR file path
 * @param duration     recording duration as string (e.g. "30.0s")
 * @param totalSamples total CPU execution samples
 * @param hotMethods   methods ranked by sample count (descending)
 * @param gc           GC pause summary (null if not requested)
 * @param io           I/O activity summary (null if not requested)
 * @param allocations  top allocating classes (empty if not requested)
 * @param contentions  thread contention hotspots (empty if not requested)
 * @param exceptions   exception throw hotspots (empty if not requested)
 */
public record JfrProfile(
        String file,
        String duration,
        long totalSamples,
        List<HotMethod> hotMethods,
        GcSummary gc,
        IoSummary io,
        List<Allocation> allocations,
        List<Contention> contentions,
        List<ExceptionHotspot> exceptions
) {

    /**
     * A method that appeared in CPU execution samples.
     */
    public record HotMethod(
            String className,
            String methodName,
            long samples,
            double pct
    ) {}

    /**
     * GC pause summary from jdk.GCPhasePause / jdk.GarbageCollection events.
     */
    public record GcSummary(
            int pauses,
            long totalPauseMs,
            long maxPauseMs,
            long avgPauseMs
    ) {}

    /**
     * I/O activity summary from jdk.FileRead/Write and jdk.SocketRead/Write events.
     */
    public record IoSummary(
            long fileReads,
            long fileWrites,
            long socketReads,
            long socketWrites
    ) {}

    /**
     * Object allocation hotspot from jdk.ObjectAllocationInNewTLAB events.
     */
    public record Allocation(
            String className,
            long bytes,
            long count
    ) {}

    /**
     * Thread contention hotspot from jdk.JavaMonitorWait events.
     */
    public record Contention(
            String className,
            String methodName,
            long waitMs,
            long count
    ) {}

    /**
     * Exception throw hotspot from jdk.JavaExceptionThrow events.
     */
    public record ExceptionHotspot(
            String className,
            long count
    ) {}
}
