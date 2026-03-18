package com.jsrc.app.command;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.LinkedHashMap;
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

                    // Refresh index if needed
                    var freshIndexed = IndexedCodebase.tryLoad(
                            Paths.get(ctx.rootPath()), ctx.javaFiles());

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
}
