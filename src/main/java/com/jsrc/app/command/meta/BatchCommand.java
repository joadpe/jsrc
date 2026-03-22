package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandFactory;
import com.jsrc.app.command.CommandContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonReader;
import com.jsrc.app.output.OutputFormatter;

/**
 * Executes multiple queries in a single JVM invocation.
 * Reads JSON array of command strings from stdin.
 * Returns JSON array of results.
 * <p>
 * Uses injected output streams per sub-command to capture output
 * without redirecting System.out (thread-safe).
 */
public class BatchCommand implements Command {

    @Override
    @SuppressWarnings("unchecked")
    public int execute(CommandContext ctx) {
        try {
            String input = new String(System.in.readAllBytes()).trim();
            Object parsed = JsonReader.parse(input);
            if (!(parsed instanceof List<?> commands)) {
                System.err.println("Error: --batch expects JSON array on stdin");
                return 0;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Object cmdObj : commands) {
                String cmdStr = cmdObj.toString();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("command", cmdStr);

                String[] parts = cmdStr.trim().split("\\s+");
                String command = parts[0];
                String arg = parts.length > 1 ? parts[1] : null;
                Command cmd = CommandFactory.create(command, arg, false);
                if (cmd == null && !command.startsWith("--")) {
                    cmd = CommandFactory.createMethodSearch(command);
                }
                if (cmd != null) {
                    // Capture output via injected stream — no System.setOut hack
                    var baos = new ByteArrayOutputStream();
                    var captureStream = new PrintStream(baos);
                    var captureFormatter = OutputFormatter.create(true, false, null, captureStream);
                    var captureCtx = new CommandContext(
                            ctx.javaFiles(), ctx.rootPath(), ctx.config(),
                            captureFormatter, ctx.indexed(), ctx.parser());

                    int resultCount = cmd.execute(captureCtx);
                    captureStream.flush();
                    String captured = baos.toString().trim();

                    entry.put("resultCount", resultCount);
                    try {
                        entry.put("result", JsonReader.parse(captured));
                    } catch (Exception e) {
                        entry.put("result", captured);
                    }
                } else {
                    entry.put("error", "Unknown command");
                }
                results.add(entry);
            }

            ctx.formatter().printResult(results);
            return results.size();
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
            return 0;
        }
    }
}
