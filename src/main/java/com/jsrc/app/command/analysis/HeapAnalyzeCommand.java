package com.jsrc.app.command.analysis;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.jfr.HeapDumpRunner;
import com.jsrc.app.jfr.HeapDumpRunner.HistogramDelta;
import com.jsrc.app.jfr.HeapDumpRunner.HistogramEntry;
import com.jsrc.app.jfr.JfrToolNotFoundException;
import com.jsrc.app.jfr.JfrExecutionException;
import com.jsrc.app.model.CommandHint;

import java.util.*;

/**
 * Live memory analysis of a running JVM via jcmd.
 * Supports heap info, class histogram, and leak detection via delta comparison.
 */
public class HeapAnalyzeCommand implements Command {

    private final Long pid;
    private final boolean heapInfo;
    private final boolean histogram;
    private final boolean compare;
    private final int topN;
    private final int intervalSeconds;

    public HeapAnalyzeCommand(Long pid, boolean heapInfo, boolean histogram,
                               boolean compare, int topN, int intervalSeconds) {
        this.pid = pid;
        this.heapInfo = heapInfo;
        this.histogram = histogram;
        this.compare = compare;
        this.topN = topN;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (pid == null) {
            System.err.println("Error: --pid is required for heap-analyze");
            return 0;
        }

        try {
            if (heapInfo) {
                return executeHeapInfo(ctx);
            }
            if (compare) {
                return executeCompare(ctx);
            }
            if (histogram) {
                return executeHistogram(ctx);
            }
            // Default: histogram
            return executeHistogram(ctx);
        } catch (JfrToolNotFoundException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        } catch (JfrExecutionException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        }
    }

    private int executeHeapInfo(CommandContext ctx) {
        List<String> raw = HeapDumpRunner.heapInfo(pid);
        Map<String, Object> info = HeapDumpRunner.parseHeapInfo(raw);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("type", "heap-info");
        result.putAll(info);

        var hints = List.of(
            new CommandHint("heap-analyze --pid " + pid + " --histogram", "Class histogram"),
            new CommandHint("heap-analyze --pid " + pid + " --compare", "Detect memory leaks"),
            new CommandHint("heap-dump --pid " + pid, "Generate heap dump")
        );
        ctx.formatter().printResultWithHints(result, hints);
        return 1;
    }

    private int executeHistogram(CommandContext ctx) {
        List<String> raw = HeapDumpRunner.classHistogram(pid, false);
        List<HistogramEntry> entries = HeapDumpRunner.parseHistogram(raw, topN);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("type", "class-histogram");
        result.put("topClasses", entries.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", e.className());
            m.put("instances", e.instances());
            m.put("bytes", e.bytes());
            return m;
        }).toList());
        result.put("total", entries.size());

        String topClass = entries.isEmpty() ? "CLASS" : entries.getFirst().className();
        var hints = List.of(
            new CommandHint("heap-analyze --pid " + pid + " --compare", "Detect growing classes (leak suspects)"),
            new CommandHint("heap-dump --pid " + pid, "Generate heap dump for deeper analysis"),
            new CommandHint("read " + simpleClassName(topClass), "Read the top class source")
        );
        ctx.formatter().printResultWithHints(result, hints);
        return entries.size();
    }

    private int executeCompare(CommandContext ctx) {
        System.err.printf("Taking first snapshot...%n");
        List<String> raw1 = HeapDumpRunner.classHistogram(pid, false);
        List<HistogramEntry> before = HeapDumpRunner.parseHistogram(raw1, 200);

        System.err.printf("Waiting %ds for second snapshot...%n", intervalSeconds);
        try {
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting");
            return 0;
        }

        System.err.printf("Taking second snapshot...%n");
        List<String> raw2 = HeapDumpRunner.classHistogram(pid, false);
        List<HistogramEntry> after = HeapDumpRunner.parseHistogram(raw2, 200);

        List<HistogramDelta> deltas = HeapDumpRunner.computeDelta(before, after, 10);
        List<HistogramDelta> topDeltas = deltas.size() > topN ? deltas.subList(0, topN) : deltas;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("type", "leak-suspects");
        result.put("intervalSeconds", intervalSeconds);
        result.put("leakSuspects", topDeltas.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", d.className());
            m.put("instanceGrowth", d.instanceGrowth());
            m.put("byteGrowth", d.byteGrowth());
            m.put("instancesBefore", d.instancesBefore());
            m.put("instancesAfter", d.instancesAfter());
            return m;
        }).toList());
        result.put("totalGrowing", deltas.size());

        String topSuspect = topDeltas.isEmpty() ? "CLASS" : topDeltas.getFirst().className();
        var hints = new ArrayList<CommandHint>();
        hints.add(new CommandHint("read " + simpleClassName(topSuspect), "Read the top leak suspect"));
        hints.add(new CommandHint("heap-dump --pid " + pid, "Generate heap dump for deeper analysis"));
        if (topDeltas.isEmpty()) {
            hints.add(new CommandHint("heap-analyze --pid " + pid + " --compare --interval 60",
                    "Try longer interval"));
        }
        ctx.formatter().printResultWithHints(result, hints);
        return deltas.size();
    }

    private static String simpleClassName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return "CLASS";
        // Handle array types like [B, [Ljava.lang.Object;
        if (fqcn.startsWith("[")) return fqcn;
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }
}
