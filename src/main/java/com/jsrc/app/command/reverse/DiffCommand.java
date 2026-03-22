package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.util.Hashing;

public class DiffCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Path root = Paths.get(ctx.rootPath());
        List<IndexEntry> existing = CodebaseIndex.load(root);
        if (existing.isEmpty()) {
            System.err.println("No index found. Run --index first.");
            return 0;
        }

        Map<String, IndexEntry> byPath = new HashMap<>();
        for (var entry : existing) byPath.put(entry.path(), entry);

        List<String> modified = new ArrayList<>();
        List<String> added = new ArrayList<>();
        Set<String> currentPaths = new HashSet<>();

        for (Path file : ctx.javaFiles()) {
            String relativePath = root.relativize(file).toString();
            currentPaths.add(relativePath);
            var prev = byPath.get(relativePath);
            if (prev == null) {
                added.add(relativePath);
            } else {
                try {
                    long currentModified = Files.getLastModifiedTime(file).toMillis();
                    if (currentModified > prev.lastModified()) {
                        byte[] content = Files.readAllBytes(file);
                        if (!Hashing.sha256(content).equals(prev.contentHash())) {
                            modified.add(relativePath);
                        }
                    }
                } catch (IOException e) {
                    modified.add(relativePath);
                }
            }
        }

        List<String> deleted = byPath.keySet().stream()
                .filter(p -> !currentPaths.contains(p))
                .sorted().toList();

        ctx.formatter().printDiff(modified, added, deleted);
        return modified.size() + added.size() + deleted.size();
    }
}
