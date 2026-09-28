package com.jsrc.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in, process-local timings for the offline index benchmark. */
public final class IndexPhaseMetrics {
    private static final Logger logger = LoggerFactory.getLogger(IndexPhaseMetrics.class);
    private static final IndexPhaseMetrics GLOBAL = new IndexPhaseMetrics();
    private static final String TRACE_PATH = System.getenv("JSRC_PERF_TRACE") != null
            ? System.getenv("JSRC_PERF_TRACE") : System.getProperty("jsrc.perf.trace");

    static {
        if (TRACE_PATH != null && !TRACE_PATH.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    GLOBAL.write(Path.of(TRACE_PATH));
                } catch (IOException ex) {
                    logger.warn("Could not write index phase metrics: {}", ex.getMessage());
                }
            }, "jsrc-index-phase-metrics"));
        }
    }

    private final Map<String, LongAdder> durations = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    public static boolean enabled() {
        return TRACE_PATH != null && !TRACE_PATH.isBlank();
    }

    public static void recordPhase(String name, long startedNanos) {
        if (enabled()) {
            GLOBAL.record(name, System.nanoTime() - startedNanos);
        }
    }

    public static void countPhase(String name, long count) {
        if (enabled()) {
            GLOBAL.count(name, count);
        }
    }

    public void record(String name, long durationNanos) {
        if (!name.matches("[a-z0-9_.-]+") || durationNanos < 0) {
            throw new IllegalArgumentException("Invalid phase or duration");
        }
        durations.computeIfAbsent(name, ignored -> new LongAdder()).add(durationNanos);
    }

    public void count(String name, long count) {
        if (!name.matches("[a-z0-9_.-]+") || count < 0) {
            throw new IllegalArgumentException("Invalid count name or value");
        }
        counts.computeIfAbsent(name, ignored -> new LongAdder()).add(count);
    }

    public void write(Path path) throws IOException {
        Map<String, Long> values = new TreeMap<>();
        durations.forEach((name, value) -> values.put(name, value.sum()));
        StringBuilder json = new StringBuilder("{\"schema\":1,\"unit\":\"ns\",\"durations\":{");
        appendValues(json, values);
        json.append("},\"counts\":{");
        values.clear();
        counts.forEach((name, value) -> values.put(name, value.sum()));
        appendValues(json, values);
        json.append("}}\n");
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static void appendValues(StringBuilder json, Map<String, Long> values) {
        boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
    }
}
