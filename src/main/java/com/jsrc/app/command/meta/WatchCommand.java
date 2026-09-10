package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandFactory;
import com.jsrc.app.command.CommandContext;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.output.OutputFormatter;

/**
 * Daemon mode: watches filesystem for changes and serves queries via stdin.
 * Maintains index in memory for instant responses.
 * <p>
 * Protocol: one JSON command per line on stdin, one JSON result per line on stdout.
 * Send {"command":"quit"} to exit.
 * <p>
 * Uses injected output streams per sub-command to capture output
 * without redirecting System.out (thread-safe).
 */
public class WatchCommand implements Command {

    private IndexedCodebase cachedIndex = null;
    private IndexStamp lastStamp = null;

    @Override
    public int execute(CommandContext ctx) {
        System.err.println("jsrc watch mode started. Send JSON commands on stdin. {\"command\":\"quit\"} to exit.");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) JsonReader.parse(line);
                    if (input == null) continue;

                    String command = (String) input.getOrDefault("command", "");
                    if ("quit".equals(command) || "exit".equals(command)) {
                        System.err.println("jsrc watch mode exiting.");
                        break;
                    }

                    String arg = (String) input.getOrDefault("arg", "");

                    // Refresh index only if needed (session cache)
                    var freshIndexed = loadOrRefreshIndex(
                            Paths.get(ctx.rootPath()), ctx.javaFiles(), cachedIndex);
                    cachedIndex = freshIndexed;

                    // Capture output via injected stream — no System.setOut hack
                    var baos = new ByteArrayOutputStream();
                    var captureStream = new PrintStream(baos);
                    var captureFormatter = OutputFormatter.create(true, false, null, captureStream);
                    var freshCtx = new CommandContext(
                            ctx.javaFiles(), ctx.rootPath(), ctx.config(),
                            captureFormatter, freshIndexed, ctx.parser());

                    // Execute command
                    Command cmd = CommandFactory.create("--" + command, arg, false);
                    if (cmd == null && !command.startsWith("--")) {
                        cmd = CommandFactory.createMethodSearch(command);
                    }
                    if (cmd == null) {
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("error", "Unknown command: " + command);
                        System.out.println(JsonWriter.toJson(error));
                        System.out.flush();
                        continue;
                    }

                    cmd.execute(freshCtx);
                    captureStream.flush();
                    System.out.println(baos.toString().trim());
                    System.out.flush();

                } catch (Exception e) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", e.getMessage());
                    System.out.println(JsonWriter.toJson(error));
                    System.out.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Loads or refreshes the indexed codebase.
     * Checks a cheap stamp (index.bin mtime + source files count/mtime) before calling tryLoad.
     * Returns cached index if stamp hasn't changed.
     *
     * @param root project root
     * @param files current Java source files
     * @param cached previously cached IndexedCodebase, or null
     * @return fresh or cached IndexedCodebase, or null if no index exists
     */
    protected IndexedCodebase loadOrRefreshIndex(Path root, List<Path> files, IndexedCodebase cached) {
        IndexStamp currentStamp = computeStamp(root, files);

        if (lastStamp != null && lastStamp.equals(currentStamp)) {
            return cached;
        }

        lastStamp = currentStamp;
        return callTryLoad(root, files);
    }

    /**
     * Wrapper for IndexedCodebase.tryLoad to allow test instrumentation.
     */
    protected IndexedCodebase callTryLoad(Path root, List<Path> files) {
        return IndexedCodebase.tryLoad(root, files);
    }

    /**
     * Computes a cheap stamp representing the current state of the index and source files.
     */
    private IndexStamp computeStamp(Path root, List<Path> files) {
        Path indexBin = root.resolve(".jsrc/index.bin");
        long indexMtime = 0;
        try {
            if (Files.exists(indexBin)) {
                indexMtime = Files.getLastModifiedTime(indexBin).toMillis();
            }
        } catch (IOException e) {
            // Ignore
        }

        long maxSourceMtime = 0;
        int fileCount = files.size();
        for (Path file : files) {
            try {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime > maxSourceMtime) {
                    maxSourceMtime = mtime;
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        return new IndexStamp(indexMtime, maxSourceMtime, fileCount);
    }

    /**
     * Simple stamp record for detecting changes.
     */
    private record IndexStamp(long indexMtime, long maxSourceMtime, int fileCount) {}
}
