package com.jsrc.app.command.analysis;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.jfr.*;
import com.jsrc.app.jfr.JfrCorrelator.CorrelatedMethod;
import com.jsrc.app.jfr.JfrCorrelator.Correlation;
import com.jsrc.app.jfr.JfrCorrelator.CorrelationResult;
import com.jsrc.app.jfr.JfrProfile.*;
import com.jsrc.app.model.CommandHint;

import java.nio.file.Path;
import java.util.*;

/**
 * Profiles a JFR recording file and outputs structured analysis.
 * Optionally correlates with the jsrc index for enrichment.
 */
public class ProfileCommand implements Command {

    private final String jfrFile;
    private final int topN;
    private final boolean correlate;
    private final boolean gc;
    private final boolean io;
    private final boolean allocations;
    private final boolean contention;
    private final boolean exceptions;

    public ProfileCommand(String jfrFile, int topN, boolean correlate,
                           boolean gc, boolean io, boolean allocations,
                           boolean contention, boolean exceptions) {
        this.jfrFile = jfrFile;
        this.topN = topN;
        this.correlate = correlate;
        this.gc = gc;
        this.io = io;
        this.allocations = allocations;
        this.contention = contention;
        this.exceptions = exceptions;
    }

    @Override
    public int execute(CommandContext ctx) {
        try {
            // Determine events to request
            List<String> eventTypes = JfrParser.eventTypesFor(gc, io, allocations, contention, exceptions);

            // Read and parse JFR file
            String json = JfrRunner.readJfr(Path.of(jfrFile), eventTypes);
            JfrProfile profile = JfrParser.parse(json, jfrFile, topN,
                    gc, io, allocations, contention, exceptions);

            // Build output
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file", profile.file());
            result.put("totalSamples", profile.totalSamples());

            // Hot methods — with or without correlation
            if (correlate && ctx.indexed() != null) {
                CorrelationResult corr = JfrCorrelator.correlate(profile, ctx.indexed(), ctx.rootPath());
                result.put("topMethods", formatCorrelatedMethods(corr.methods()));
                if (!corr.correlations().isEmpty()) {
                    result.put("correlations", formatCorrelations(corr.correlations()));
                }
            } else {
                result.put("topMethods", formatHotMethods(profile.hotMethods()));
            }

            // Optional sections
            if (profile.gc() != null) {
                result.put("gc", formatGc(profile.gc()));
            }
            if (profile.io() != null) {
                result.put("io", formatIo(profile.io()));
            }
            if (!profile.allocations().isEmpty()) {
                result.put("allocations", formatAllocations(profile.allocations()));
            }
            if (!profile.contentions().isEmpty()) {
                result.put("contention", formatContentions(profile.contentions()));
            }
            if (!profile.exceptions().isEmpty()) {
                result.put("exceptions", formatExceptions(profile.exceptions()));
            }

            var hints = java.util.List.of(
                new CommandHint("read CLASS.METHOD", "Read the hot method"),
                new CommandHint("perf CLASS", "Static performance analysis")
            );

            ctx.formatter().printResultWithHints(result, hints);
            return profile.hotMethods().size();

        } catch (JfrToolNotFoundException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        } catch (JfrExecutionException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        }
    }

    private List<Map<String, Object>> formatHotMethods(List<HotMethod> methods) {
        return methods.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", m.className());
            map.put("method", m.methodName());
            map.put("samples", m.samples());
            map.put("pct", m.pct());
            return map;
        }).toList();
    }

    private List<Map<String, Object>> formatCorrelatedMethods(List<CorrelatedMethod> methods) {
        return methods.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", m.className());
            map.put("method", m.methodName());
            map.put("samples", m.samples());
            map.put("pct", m.pct());
            map.put("inIndex", m.inIndex());
            if (m.file() != null) map.put("file", m.file());
            if (m.smells() > 0) map.put("smells", m.smells());
            return map;
        }).toList();
    }

    private List<Map<String, Object>> formatCorrelations(List<Correlation> correlations) {
        return correlations.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", c.method());
            map.put("jfrEvidence", c.jfrEvidence());
            map.put("jsrcFinding", c.jsrcFinding());
            map.put("confirmed", c.confirmed());
            map.put("recommendation", c.recommendation());
            return map;
        }).toList();
    }

    private Map<String, Object> formatGc(GcSummary gc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pauses", gc.pauses());
        map.put("totalPauseMs", gc.totalPauseMs());
        map.put("maxPauseMs", gc.maxPauseMs());
        map.put("avgPauseMs", gc.avgPauseMs());
        return map;
    }

    private Map<String, Object> formatIo(IoSummary io) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileReads", io.fileReads());
        map.put("fileWrites", io.fileWrites());
        map.put("socketReads", io.socketReads());
        map.put("socketWrites", io.socketWrites());
        return map;
    }

    private List<Map<String, Object>> formatAllocations(List<Allocation> allocations) {
        return allocations.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", a.className());
            map.put("bytes", a.bytes());
            map.put("count", a.count());
            return map;
        }).toList();
    }

    private List<Map<String, Object>> formatContentions(List<Contention> contentions) {
        return contentions.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", c.className());
            map.put("method", c.methodName());
            map.put("waitMs", c.waitMs());
            map.put("count", c.count());
            return map;
        }).toList();
    }

    private List<Map<String, Object>> formatExceptions(List<ExceptionHotspot> exceptions) {
        return exceptions.stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("class", e.className());
            map.put("count", e.count());
            return map;
        }).toList();
    }
}
