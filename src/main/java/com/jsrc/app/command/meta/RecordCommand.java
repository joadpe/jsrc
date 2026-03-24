package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.jfr.JfrRunner;
import com.jsrc.app.jfr.JfrToolNotFoundException;
import com.jsrc.app.jfr.JfrExecutionException;
import com.jsrc.app.output.JsonWriter;

import java.nio.file.Path;
import java.util.*;

/**
 * Records JFR data from a running JVM using jcmd.
 * Supports start, stop, and list operations.
 */
public class RecordCommand implements Command {

    private final Long pid;
    private final String duration;
    private final String output;
    private final String settings;
    private final boolean stop;
    private final boolean list;

    public RecordCommand(Long pid, String duration, String output, String settings,
                         boolean stop, boolean list) {
        this.pid = pid;
        this.duration = duration;
        this.output = output;
        this.settings = settings;
        this.stop = stop;
        this.list = list;
    }

    @Override
    public int execute(CommandContext ctx) {
        try {
            if (list) {
                return executeList(ctx);
            }
            if (pid == null) {
                System.err.println("Error: --pid is required for record/stop operations");
                return 0;
            }
            if (stop) {
                return executeStop(ctx);
            }
            return executeStart(ctx);
        } catch (JfrToolNotFoundException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        } catch (JfrExecutionException e) {
            System.err.println("Error: " + e.getMessage());
            return 0;
        }
    }

    private int executeList(CommandContext ctx) {
        List<String> jvms = JfrRunner.listJvms();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> processes = new ArrayList<>();

        for (String line : jvms) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                try {
                    long jvmPid = Long.parseLong(trimmed.substring(0, space));
                    String mainClass = trimmed.substring(space + 1).trim();
                    processes.add(Map.of("pid", jvmPid, "mainClass", mainClass));
                } catch (NumberFormatException ignored) {
                    // Skip non-PID lines
                }
            }
        }

        result.put("jvms", processes);
        result.put("count", processes.size());
        ctx.formatter().printResult(result);
        return processes.size();
    }

    private int executeStart(CommandContext ctx) {
        Path outputPath = Path.of(output != null ? output
                : Path.of(ctx.rootPath(), ".jsrc", "recording.jfr").toString());
        String dur = duration != null ? duration : "30s";
        String sett = settings != null ? settings : "profile";

        List<String> result = JfrRunner.startRecording(pid, dur, outputPath, sett);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", "start");
        response.put("pid", pid);
        response.put("duration", dur);
        response.put("output", outputPath.toAbsolutePath().toString());
        response.put("settings", sett);
        response.put("message", String.join("\n", result));
        ctx.formatter().printResult(response);
        return 1;
    }

    private int executeStop(CommandContext ctx) {
        List<String> result = JfrRunner.stopRecording(pid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", "stop");
        response.put("pid", pid);
        response.put("message", String.join("\n", result));
        ctx.formatter().printResult(response);
        return 1;
    }
}
