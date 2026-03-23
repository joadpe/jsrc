package com.jsrc.app.command.meta;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.jsrc.app.exception.JsrcIOException;
import com.jsrc.app.index.CodebaseIndex;

public class IndexCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Path root = Paths.get(ctx.rootPath());
        System.err.printf("Indexing %d Java files under '%s'...%n", ctx.javaFiles().size(), ctx.rootPath());

        var existing = CodebaseIndex.load(root);
        // Re-index entries that lack complete edge data (callerParamCount + argCount)
        existing = existing.stream()
                .filter(e -> !e.callEdges().isEmpty()
                        && e.callEdges().stream().allMatch(
                                edge -> edge.argCount() >= 0 && edge.callerParamCount() >= 0))
                .toList();
        var index = new CodebaseIndex();
        var invokers = (ctx.config() != null)
                ? ctx.config().architecture().invokers()
                : java.util.List.<com.jsrc.app.config.ArchitectureConfig.InvokerDef>of();
        int reindexed = index.build(ctx.parser(), ctx.javaFiles(), root, existing, invokers);

        try {
            // Build call graph and save V2 binary with pre-resolved graph
            var builder = new com.jsrc.app.analysis.CallGraphBuilder();
            builder.loadFromIndex(index.getEntries());
            var callGraph = builder.toCallGraph();
            // Pre-compute migration suggestions
            var migrateCmd = new com.jsrc.app.command.quality.MigrateCommand(null, 17, true);
            var migrationData = migrateCmd.computeAllForIndex(ctx);
            java.util.Map<String, java.util.List<com.jsrc.app.index.CachedMigration>> migrations = new java.util.LinkedHashMap<>();
            for (var entry : migrationData.entrySet()) {
                migrations.put(entry.getKey(), entry.getValue().stream()
                        .map(arr -> new com.jsrc.app.index.CachedMigration(arr[0], arr[1]))
                        .toList());
            }

            index.saveWithGraph(root, callGraph, migrations);
            System.err.printf("Done. Indexed %d files (%d re-indexed, %d cached).%n",
                    ctx.javaFiles().size(), reindexed, ctx.javaFiles().size() - reindexed);
        } catch (IOException ex) {
            throw new JsrcIOException("Error saving index: " + ex.getMessage(), ex);
        }
        return 0;
    }
}
