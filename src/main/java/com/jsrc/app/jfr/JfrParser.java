package com.jsrc.app.jfr;

import com.jsrc.app.jfr.JfrProfile.*;
import com.jsrc.app.output.JsonReader;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses JSON output from {@code jfr print --json} into a {@link JfrProfile}.
 *
 * <p>The JFR JSON format wraps events under recording → events → array.
 * Each event has a "type" field and "values" map.</p>
 */
public final class JfrParser {

    private JfrParser() {}

    /**
     * Event type constants for JFR event filtering.
     */
    public static final String EVENT_EXECUTION_SAMPLE = "jdk.ExecutionSample";
    public static final String EVENT_GC_PAUSE = "jdk.GCPhasePause";
    public static final String EVENT_GARBAGE_COLLECTION = "jdk.GarbageCollection";
    public static final String EVENT_FILE_READ = "jdk.FileRead";
    public static final String EVENT_FILE_WRITE = "jdk.FileWrite";
    public static final String EVENT_SOCKET_READ = "jdk.SocketRead";
    public static final String EVENT_SOCKET_WRITE = "jdk.SocketWrite";
    public static final String EVENT_OBJECT_ALLOC = "jdk.ObjectAllocationInNewTLAB";
    public static final String EVENT_MONITOR_WAIT = "jdk.JavaMonitorWait";
    public static final String EVENT_EXCEPTION_THROW = "jdk.JavaExceptionThrow";

    /**
     * Returns the event types to request from jfr based on flags.
     */
    public static List<String> eventTypesFor(boolean gc, boolean io, boolean allocations,
                                              boolean contention, boolean exceptions) {
        List<String> events = new ArrayList<>();
        events.add(EVENT_EXECUTION_SAMPLE); // always needed for CPU profiling
        if (gc) {
            events.add(EVENT_GC_PAUSE);
            events.add(EVENT_GARBAGE_COLLECTION);
        }
        if (io) {
            events.add(EVENT_FILE_READ);
            events.add(EVENT_FILE_WRITE);
            events.add(EVENT_SOCKET_READ);
            events.add(EVENT_SOCKET_WRITE);
        }
        if (allocations) events.add(EVENT_OBJECT_ALLOC);
        if (contention) events.add(EVENT_MONITOR_WAIT);
        if (exceptions) events.add(EVENT_EXCEPTION_THROW);
        return events;
    }

    /**
     * Parses the raw JSON output from {@code jfr print --json}.
     *
     * @param json     raw JSON string
     * @param file     source file path for the profile
     * @param topN     max number of hot methods to return
     * @param parseGc  whether to parse GC events
     * @param parseIo  whether to parse I/O events
     * @param parseAlloc whether to parse allocation events
     * @param parseContention whether to parse contention events
     * @param parseExceptions whether to parse exception events
     * @return parsed JfrProfile
     */
    @SuppressWarnings("unchecked")
    public static JfrProfile parse(String json, String file, int topN,
                                    boolean parseGc, boolean parseIo, boolean parseAlloc,
                                    boolean parseContention, boolean parseExceptions) {
        Objects.requireNonNull(json, "json must not be null");

        Object parsed = JsonReader.parse(json);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Invalid JFR JSON: expected object at root");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;
        List<Map<String, Object>> events = extractEvents(root);

        // CPU profiling — always parsed
        var cpuResult = parseCpuSamples(events, topN);

        // Optional sections
        GcSummary gc = parseGc ? parseGcEvents(events) : null;
        IoSummary io = parseIo ? parseIoEvents(events) : null;
        List<Allocation> allocs = parseAlloc ? parseAllocations(events) : List.of();
        List<Contention> contentions = parseContention ? parseContentions(events) : List.of();
        List<ExceptionHotspot> exceptions = parseExceptions ? parseExceptions(events) : List.of();

        return new JfrProfile(file, "", cpuResult.totalSamples, cpuResult.hotMethods,
                gc, io, allocs, contentions, exceptions);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEvents(Map<String, Object> root) {
        // JFR JSON structure: { "recording": { "events": [ ... ] } }
        Object recording = root.get("recording");
        if (recording instanceof Map<?, ?> recMap) {
            Object events = ((Map<String, Object>) recMap).get("events");
            if (events instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        }
        return List.of();
    }

    private record CpuResult(long totalSamples, List<HotMethod> hotMethods) {}

    @SuppressWarnings("unchecked")
    private static CpuResult parseCpuSamples(List<Map<String, Object>> events, int topN) {
        Map<String, Long> methodCounts = new LinkedHashMap<>();
        long total = 0;

        for (var event : events) {
            if (!EVENT_EXECUTION_SAMPLE.equals(event.get("type"))) continue;
            total++;

            Map<String, Object> values = (Map<String, Object>) event.get("values");
            if (values == null) continue;

            Object stackTrace = values.get("stackTrace");
            if (stackTrace instanceof Map<?, ?> st) {
                Object frames = ((Map<String, Object>) st).get("frames");
                if (frames instanceof List<?> frameList && !frameList.isEmpty()) {
                    Map<String, Object> topFrame = (Map<String, Object>) frameList.get(0);
                    Object method = topFrame.get("method");
                    if (method instanceof Map<?, ?> m) {
                        String className = extractString((Map<String, Object>) m, "type");
                        String methodName = extractString((Map<String, Object>) m, "name");
                        if (className != null && methodName != null) {
                            String key = className + "." + methodName;
                            methodCounts.merge(key, 1L, Long::sum);
                        }
                    }
                }
            }
        }

        long finalTotal = total;
        List<HotMethod> hotMethods = methodCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    int dot = e.getKey().lastIndexOf('.');
                    String cls = dot > 0 ? e.getKey().substring(0, dot) : "";
                    String meth = dot > 0 ? e.getKey().substring(dot + 1) : e.getKey();
                    double pct = finalTotal > 0 ? (e.getValue() * 100.0 / finalTotal) : 0;
                    return new HotMethod(cls, meth, e.getValue(), Math.round(pct * 10) / 10.0);
                })
                .toList();

        return new CpuResult(total, hotMethods);
    }

    @SuppressWarnings("unchecked")
    private static GcSummary parseGcEvents(List<Map<String, Object>> events) {
        int pauses = 0;
        long totalMs = 0;
        long maxMs = 0;

        for (var event : events) {
            String type = (String) event.get("type");
            if (!EVENT_GC_PAUSE.equals(type) && !EVENT_GARBAGE_COLLECTION.equals(type)) continue;
            pauses++;

            Map<String, Object> values = (Map<String, Object>) event.get("values");
            if (values != null) {
                long durationMs = extractLong(values, "duration", 0) / 1_000_000; // ns to ms
                totalMs += durationMs;
                maxMs = Math.max(maxMs, durationMs);
            }
        }

        if (pauses == 0) return new GcSummary(0, 0, 0, 0);
        return new GcSummary(pauses, totalMs, maxMs, totalMs / pauses);
    }

    @SuppressWarnings("unchecked")
    private static IoSummary parseIoEvents(List<Map<String, Object>> events) {
        long fileReads = 0, fileWrites = 0, socketReads = 0, socketWrites = 0;

        for (var event : events) {
            switch ((String) event.get("type")) {
                case EVENT_FILE_READ -> fileReads++;
                case EVENT_FILE_WRITE -> fileWrites++;
                case EVENT_SOCKET_READ -> socketReads++;
                case EVENT_SOCKET_WRITE -> socketWrites++;
                default -> { /* skip */ }
            }
        }

        return new IoSummary(fileReads, fileWrites, socketReads, socketWrites);
    }

    @SuppressWarnings("unchecked")
    private static List<Allocation> parseAllocations(List<Map<String, Object>> events) {
        Map<String, long[]> allocs = new LinkedHashMap<>(); // className -> [bytes, count]

        for (var event : events) {
            if (!EVENT_OBJECT_ALLOC.equals(event.get("type"))) continue;

            Map<String, Object> values = (Map<String, Object>) event.get("values");
            if (values == null) continue;

            Object objectClass = values.get("objectClass");
            String className = objectClass instanceof Map<?, ?> oc
                    ? extractString((Map<String, Object>) oc, "name")
                    : String.valueOf(objectClass);
            if (className == null) continue;

            long bytes = extractLong(values, "tlabSize", 0);
            allocs.computeIfAbsent(className, k -> new long[2]);
            allocs.get(className)[0] += bytes;
            allocs.get(className)[1]++;
        }

        return allocs.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> new Allocation(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Contention> parseContentions(List<Map<String, Object>> events) {
        Map<String, long[]> contentions = new LinkedHashMap<>(); // class.method -> [waitMs, count]

        for (var event : events) {
            if (!EVENT_MONITOR_WAIT.equals(event.get("type"))) continue;

            Map<String, Object> values = (Map<String, Object>) event.get("values");
            if (values == null) continue;

            Object monitorClass = values.get("monitorClass");
            String className = monitorClass instanceof Map<?, ?> mc
                    ? extractString((Map<String, Object>) mc, "name")
                    : String.valueOf(monitorClass);
            if (className == null) className = "unknown";

            // Extract method from stack trace top frame
            String method = "unknown";
            Object stackTrace = values.get("stackTrace");
            if (stackTrace instanceof Map<?, ?> st) {
                Object frames = ((Map<String, Object>) st).get("frames");
                if (frames instanceof List<?> frameList && !frameList.isEmpty()) {
                    Map<String, Object> topFrame = (Map<String, Object>) frameList.get(0);
                    Object m = topFrame.get("method");
                    if (m instanceof Map<?, ?> mm) {
                        String mName = extractString((Map<String, Object>) mm, "name");
                        if (mName != null) method = mName;
                    }
                }
            }

            long waitMs = extractLong(values, "duration", 0) / 1_000_000; // ns to ms
            String key = className + "." + method;
            contentions.computeIfAbsent(key, k -> new long[2]);
            contentions.get(key)[0] += waitMs;
            contentions.get(key)[1]++;
        }

        return contentions.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> {
                    int dot = e.getKey().lastIndexOf('.');
                    String cls = dot > 0 ? e.getKey().substring(0, dot) : e.getKey();
                    String meth = dot > 0 ? e.getKey().substring(dot + 1) : "unknown";
                    return new Contention(cls, meth, e.getValue()[0], e.getValue()[1]);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<ExceptionHotspot> parseExceptions(List<Map<String, Object>> events) {
        Map<String, Long> counts = new LinkedHashMap<>();

        for (var event : events) {
            if (!EVENT_EXCEPTION_THROW.equals(event.get("type"))) continue;

            Map<String, Object> values = (Map<String, Object>) event.get("values");
            if (values == null) continue;

            Object thrownClass = values.get("thrownClass");
            String className = thrownClass instanceof Map<?, ?> tc
                    ? extractString((Map<String, Object>) tc, "name")
                    : String.valueOf(thrownClass);
            if (className != null) {
                counts.merge(className, 1L, Long::sum);
            }
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new ExceptionHotspot(e.getKey(), e.getValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static String extractString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static long extractLong(Map<String, Object> map, String key, long defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }
}
