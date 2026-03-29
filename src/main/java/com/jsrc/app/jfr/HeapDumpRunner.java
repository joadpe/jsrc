package com.jsrc.app.jfr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Generates heap dumps and runs live heap analysis via jcmd.
 * Requires JDK with jcmd available (JDK 8+).
 */
public final class HeapDumpRunner {

    private static final int PROCESS_TIMEOUT_SECONDS = 300; // 5 min for large heaps

    private HeapDumpRunner() {}

    /**
     * Generates a heap dump for a JVM process.
     *
     * @param pid    target JVM PID
     * @param output output .hprof file path
     * @param live   if true, only dump live objects (triggers GC first)
     * @return command output lines
     */
    public static List<String> dumpHeap(long pid, Path output, boolean live) {
        Objects.requireNonNull(output, "output path must not be null");
        String jcmd = JfrRunner.findTool("jcmd");

        List<String> cmd = new ArrayList<>();
        cmd.add(jcmd);
        cmd.add(String.valueOf(pid));
        cmd.add("GC.heap_dump");
        if (!live) cmd.add("-all");
        cmd.add(output.toAbsolutePath().toString());

        return runCommand(cmd, "generating heap dump");
    }

    /**
     * Gets heap info (size, used, free) from a running JVM.
     *
     * @param pid target JVM PID
     * @return raw output lines from jcmd GC.heap_info
     */
    public static List<String> heapInfo(long pid) {
        String jcmd = JfrRunner.findTool("jcmd");
        return runCommand(List.of(jcmd, String.valueOf(pid), "GC.heap_info"),
                "getting heap info");
    }

    /**
     * Gets class histogram from a running JVM.
     *
     * @param pid target JVM PID
     * @param all if true, include unreachable objects (no GC first)
     * @return raw output lines from jcmd GC.class_histogram
     */
    public static List<String> classHistogram(long pid, boolean all) {
        String jcmd = JfrRunner.findTool("jcmd");
        List<String> cmd = new ArrayList<>();
        cmd.add(jcmd);
        cmd.add(String.valueOf(pid));
        cmd.add("GC.class_histogram");
        if (all) cmd.add("-all");
        return runCommand(cmd, "getting class histogram");
    }

    /**
     * Parses jcmd GC.class_histogram output into structured data.
     * Format: "  num     #instances     #bytes  class name"
     *
     * @param lines raw histogram output
     * @param topN  max classes to return
     * @return list of histogram entries
     */
    public static List<HistogramEntry> parseHistogram(List<String> lines, int topN) {
        List<HistogramEntry> entries = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip header, total, empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("num") || trimmed.startsWith("Total")
                    || trimmed.startsWith("-")) continue;

            // Format: "   1:    450000   180000000  [B"  or  "   1:    450000   180000000  byte[]"
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 4) {
                try {
                    // parts[0] = "1:" (rank), parts[1] = instances, parts[2] = bytes, parts[3+] = class
                    long instances = Long.parseLong(parts[1]);
                    long bytes = Long.parseLong(parts[2]);
                    String className = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
                    entries.add(new HistogramEntry(className, instances, bytes));
                    if (entries.size() >= topN) break;
                } catch (NumberFormatException e) {
                    // Skip unparseable lines
                }
            }
        }
        return entries;
    }

    /**
     * Parses jcmd GC.heap_info output into structured data.
     *
     * @param lines raw heap info output
     * @return map of parsed values
     */
    public static Map<String, Object> parseHeapInfo(List<String> lines) {
        Map<String, Object> info = new LinkedHashMap<>();
        long totalUsed = 0, totalCapacity = 0, totalMax = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            // Look for lines like: " eden space 42M, 35% used [..."
            // or "Heap" summary lines
            if (trimmed.contains("used") && trimmed.contains("capacity")) {
                // Parse ZGC/G1/Shenandoah format
                info.put("raw", String.join("\n", lines));
                break;
            }
            // Parse key-value style output
            if (trimmed.contains("MaxHeapSize") || trimmed.contains("max_heap")) {
                info.put("raw", String.join("\n", lines));
            }
        }

        // Always include raw output for debugging
        if (!info.containsKey("raw")) {
            info.put("raw", String.join("\n", lines));
        }

        return info;
    }

    /**
     * Computes the delta between two histograms to detect growing classes (leak suspects).
     *
     * @param before histogram before
     * @param after  histogram after
     * @param minGrowthInstances minimum instance growth to report
     * @return list of growing entries sorted by byte growth descending
     */
    public static List<HistogramDelta> computeDelta(List<HistogramEntry> before,
                                                      List<HistogramEntry> after,
                                                      long minGrowthInstances) {
        // Index before by class name
        Map<String, HistogramEntry> beforeMap = new LinkedHashMap<>();
        for (var e : before) beforeMap.put(e.className(), e);

        List<HistogramDelta> deltas = new ArrayList<>();
        for (var a : after) {
            var b = beforeMap.get(a.className());
            long instancesBefore = b != null ? b.instances() : 0;
            long bytesBefore = b != null ? b.bytes() : 0;
            long instanceGrowth = a.instances() - instancesBefore;
            long byteGrowth = a.bytes() - bytesBefore;

            if (instanceGrowth >= minGrowthInstances) {
                deltas.add(new HistogramDelta(a.className(),
                        instancesBefore, a.instances(), instanceGrowth,
                        bytesBefore, a.bytes(), byteGrowth));
            }
        }

        // Sort by byte growth descending
        deltas.sort((x, y) -> Long.compare(y.byteGrowth(), x.byteGrowth()));
        return deltas;
    }

    /**
     * A single entry from the class histogram.
     */
    public record HistogramEntry(String className, long instances, long bytes) {}

    /**
     * Delta between two histogram snapshots for a single class.
     */
    public record HistogramDelta(
            String className,
            long instancesBefore, long instancesAfter, long instanceGrowth,
            long bytesBefore, long bytesAfter, long byteGrowth
    ) {}

    private static List<String> runCommand(List<String> command, String description) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            List<String> output = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 var errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.add(line);
                while ((line = errReader.readLine()) != null) errors.add(line);

                if (!p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("Timed out after " + PROCESS_TIMEOUT_SECONDS + "s while " + description);
                }
                if (p.exitValue() != 0) {
                    String errorMsg = errors.isEmpty() ? "exit code " + p.exitValue() : String.join("\n", errors);
                    throw new IOException("Failed " + description + ": " + errorMsg);
                }
            } finally {
                p.destroyForcibly();
            }
            return output;
        } catch (IOException e) {
            throw new JfrExecutionException(description, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JfrExecutionException(description, e);
        }
    }
}
