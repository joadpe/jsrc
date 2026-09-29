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
        com.jsrc.app.index.BinaryIndexV2Reader.IndexData published = null;
        com.jsrc.app.index.BinaryIndexV2Reader.LazyIndexData publishedSnapshot = null;
        try {
            try {
                if (!java.nio.file.Files.isRegularFile(root.resolve(".jsrc/current"))) {
                    throw new IOException("No published index manifest");
                }
                publishedSnapshot = com.jsrc.app.index.BinaryIndexV2Reader.readLazy(
                        CodebaseIndex.currentBinary(root));
                published = publishedSnapshot.getData();
                existing = published.entries();
            } catch (IOException | RuntimeException ex) {
                publishedSnapshot = null;
                existing = CodebaseIndex.loadPublished(root);
            }
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
            com.jsrc.app.analysis.CallGraph callGraph =
                    com.jsrc.app.index.PublishedGraphReuse.tryReuse(
                            publishedSnapshot, existing, index.getEntries());
            if (callGraph == null) {
                var builder = new com.jsrc.app.analysis.CallGraphBuilder();
                builder.loadFromIndex(index.getEntries());
                callGraph = builder.toCallGraph();
            }
            com.jsrc.app.index.IndexPhaseMetrics.recordPhase("index.call_graph", graphStarted);
            long migrationStarted = System.nanoTime();
            java.util.Map<String, java.util.List<com.jsrc.app.index.CachedMigration>> migrations;
            boolean compatibleCache = published != null
                    && published.migrationCacheVersion()
                    == com.jsrc.app.index.CachedMigration.ALGORITHM_VERSION;
            if (compatibleCache && !existing.isEmpty() && reindexed == 0
                    && existing.size() == index.getEntries().size()) {
                migrations = published.migrations();
                com.jsrc.app.index.IndexPhaseMetrics.countPhase("index.migrations.reused", 1);
            } else {
                var migrateCmd = new com.jsrc.app.command.quality.MigrateCommand(null, 17, true);
                migrations = migrateCmd.computeForIndex(root, index.getEntries(),
                        compatibleCache ? published.migrations() : java.util.Map.of(),
                        compatibleCache ? existing : java.util.List.of());
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
