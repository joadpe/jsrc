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
        long executeStarted = System.nanoTime();
        Path root = Paths.get(ctx.rootPath());
        System.err.printf("Indexing %d Java files under '%s'...%n", ctx.javaFiles().size(), ctx.rootPath());

        long loadStarted = System.nanoTime();
        java.util.List<com.jsrc.app.index.IndexEntry> existing;
        try {
            existing = CodebaseIndex.loadPublished(root);
        } catch (IOException ex) {
            System.err.printf("Invalid published index; rebuilding: %s%n", ex.getMessage());
            existing = java.util.List.of();
        }
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.load_existing", loadStarted);
        var index = new CodebaseIndex();
        var invokers = (ctx.config() != null)
                ? ctx.config().architecture().invokers()
                : java.util.List.<com.jsrc.app.config.ArchitectureConfig.InvokerDef>of();
        var sourceSets = ctx.javaFiles().stream().collect(java.util.stream.Collectors.toMap(
                java.util.function.Function.identity(),
                ctx::sourceSet));
        long buildStarted = System.nanoTime();
        int reindexed = index.build(
                ctx.parser(), ctx.javaFiles(), root, existing, invokers, sourceSets,
                com.jsrc.app.project.SourceLevel.resolveFiles(
                        ctx.javaFiles(), ctx.projectModel(), ctx.config()));
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.build", buildStarted);
        if (index.getEntries().size() != ctx.javaFiles().size()) {
            throw new JsrcIOException("Source set changed while indexing; retry 'jsrc index'.");
        }

        try {
            // Build call graph and save V2 binary with pre-resolved graph
            long graphStarted = System.nanoTime();
            var builder = new com.jsrc.app.analysis.CallGraphBuilder();
            builder.loadFromIndex(index.getEntries());
            var callGraph = builder.toCallGraph();
            com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.call_graph", graphStarted);
            long migrationStarted = System.nanoTime();
            // Pre-compute migration suggestions
            var migrateCmd = new com.jsrc.app.command.quality.MigrateCommand(null, 17, true);
            var migrationData = migrateCmd.computeAllForIndex(ctx);
            java.util.Map<String, java.util.List<com.jsrc.app.index.CachedMigration>> migrations = new java.util.LinkedHashMap<>();
            for (var entry : migrationData.entrySet()) {
                migrations.put(entry.getKey(), entry.getValue().stream()
                        .map(arr -> new com.jsrc.app.index.CachedMigration(arr[0], arr[1]))
                        .toList());
            }

            com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.migrations", migrationStarted);
            long publishStarted = System.nanoTime();
            index.saveWithGraph(root, callGraph, migrations, true, ctx.sourceSnapshot());
            com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.publish", publishStarted);
            System.err.printf("Done. Indexed %d files (%d re-indexed, %d cached).%n",
                    ctx.javaFiles().size(), reindexed, ctx.javaFiles().size() - reindexed);
        } catch (IOException ex) {
            throw new JsrcIOException("Error saving index: " + ex.getMessage(), ex);
        }
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.total", executeStarted);
        return Math.max(1, ctx.javaFiles().size());
    }
}
