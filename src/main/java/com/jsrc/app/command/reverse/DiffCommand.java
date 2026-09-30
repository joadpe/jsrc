package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.exception.JsrcIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.Hashing;

public class DiffCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Path root = Paths.get(ctx.rootPath()).toAbsolutePath().normalize();
        Path manifest = root.resolve(".jsrc/current");
        List<IndexEntry> existing;
        if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            existing = CodebaseIndex.load(root);
        } else {
            if (!Files.isRegularFile(manifest)) {
                throw new JsrcIOException("Invalid published index manifest: " + manifest);
            }
            try {
                existing = CodebaseIndex.loadPublished(root);
            } catch (IOException ex) {
                throw new JsrcIOException("Cannot read published index: " + ex.getMessage(), ex);
            }
        }
        if (existing.isEmpty()) {
            System.err.println("No index found. Run --index first.");
            return 0;
        }

        Map<String, IndexEntry> byPath = new HashMap<>();
        for (IndexEntry entry : existing) {
            SourceSet sourceSet = ctx.sourceSet(root.resolve(entry.path()));
            if (sourceSet == SourceSet.UNKNOWN) {
                sourceSet = entry.sourceSet();
            }
            if (ctx.noTest() && sourceSet.isTest()) {
                continue;
            }
            if (!ctx.sourceSets().isEmpty() && !ctx.sourceSets().contains(sourceSet)) {
                continue;
            }
            byPath.put(entry.path(), entry);
        }

        List<String> modified = new ArrayList<>();
        List<String> added = new ArrayList<>();
        Set<String> currentPaths = new HashSet<>();

        for (Path file : ctx.javaFiles()) {
            String relativePath = root.relativize(file.toAbsolutePath().normalize()).toString();
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
