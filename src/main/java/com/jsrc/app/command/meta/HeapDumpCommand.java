package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.jfr.HeapDumpRunner;
import com.jsrc.app.jfr.JfrRunner;
import com.jsrc.app.jfr.JfrToolNotFoundException;
import com.jsrc.app.jfr.JfrExecutionException;
import com.jsrc.app.model.CommandHint;

import java.nio.file.Path;
import java.util.*;

/**
 * Generates heap dumps from running JVMs via jcmd.
 */
public class HeapDumpCommand implements Command {

    private final Long pid;
    private final String output;
    private final boolean live;
    private final boolean list;

    public HeapDumpCommand(Long pid, String output, boolean live, boolean list) {
        this.pid = pid;
        this.output = output;
        this.live = live;
        this.list = list;
    }

    @Override
    public int execute(CommandContext ctx) {
        try {
            if (list) {
                return executeList(ctx);
            }
            if (pid == null) {
                System.err.println("Error: --pid is required for heap-dump");
                return 0;
            }
            return executeDump(ctx);
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
                } catch (NumberFormatException ignored) {}
            }
        }

        result.put("jvms", processes);
        result.put("count", processes.size());

        var hints = List.of(
            new CommandHint("heap-dump --pid PID", "Generate heap dump for a JVM"),
            new CommandHint("heap-analyze --pid PID --histogram", "Live class histogram")
        );
        ctx.formatter().printResultWithHints(result, hints);
        return processes.size();
    }

    private int executeDump(CommandContext ctx) {
        Path outputPath = Path.of(output != null ? output
                : Path.of(ctx.rootPath(), ".jsrc", "heap-" + pid + ".hprof").toString());

        List<String> result = HeapDumpRunner.dumpHeap(pid, outputPath, live);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", "heap-dump");
        response.put("pid", pid);
        response.put("output", outputPath.toAbsolutePath().toString());
        response.put("live", live);
        response.put("message", String.join("\n", result));

        var hints = List.of(
            new CommandHint("heap-analyze " + outputPath.toAbsolutePath(), "Analyze this heap dump"),
            new CommandHint("heap-analyze --pid " + pid + " --histogram", "Live class histogram")
        );
        ctx.formatter().printResultWithHints(response, hints);
        return 1;
    }
}
