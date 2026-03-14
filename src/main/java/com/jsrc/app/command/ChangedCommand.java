package com.jsrc.app.command;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.output.JsonWriter;

public class ChangedCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", "HEAD");
            pb.directory(new File(ctx.rootPath()));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            List<String> changedFiles = output.isEmpty() ? List.of()
                    : List.of(output.split("\n")).stream()
                            .filter(f -> f.endsWith(".java"))
                            .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changedFiles", changedFiles);
            result.put("totalChanged", changedFiles.size());
            System.out.println(JsonWriter.toJson(result));
            return changedFiles.size();
        } catch (Exception e) {
            System.err.printf("Error running git diff: %s%n", e.getMessage());
            return 0;
        }
    }
}
