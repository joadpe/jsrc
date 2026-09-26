package com.jsrc.app.command.meta;

import com.jsrc.app.cli.ExitCodeMapper;
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
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.ProjectSourceDiscovery;

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
    private java.util.Map<Path, com.jsrc.app.project.SourceSet> pendingSourceSets = java.util.Map.of();
    private java.util.Map<Path, com.jsrc.app.project.SourceLevel> pendingSourceLevels = java.util.Map.of();
    private List<Path> pendingDiscoveredFiles = List.of();
    private List<com.jsrc.app.project.SourceDiagnostic> currentDiagnostics = List.of();
    private List<Path> cachedAcceptedFiles = List.of();
    private List<com.jsrc.app.project.SourceDiagnostic> cachedDiagnostics = List.of();

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

                    // Parse optional budget field
                    com.jsrc.app.cli.BudgetContext budgetContext = null;
                    Object budgetValue = input.get("budget");
                    if (budgetValue != null) {
                        if (!(budgetValue instanceof String)) {
                            // Invalid type (e.g. numeric, null typed wrong)
                            Map<String, Object> envelope = new LinkedHashMap<>();
                            envelope.put("exit", 2);
                            Map<String, Object> error = new LinkedHashMap<>();
                            error.put("error", "Invalid budget type. Expected string, got: " + budgetValue.getClass().getSimpleName() + ". Valid values: tiny, small, standard");
                            envelope.put("result", error);
                            emit(ctx, envelope);
                            continue;
                        }
                        String budgetStr = (String) budgetValue;
                        try {
                            com.jsrc.app.cli.BudgetProfile profile = com.jsrc.app.cli.BudgetProfile.fromString(budgetStr);
                            budgetContext = new com.jsrc.app.cli.BudgetContext(profile, null, null, false, false, null);
                        } catch (IllegalArgumentException e) {
                            // Invalid budget value
                            Map<String, Object> envelope = new LinkedHashMap<>();
                            envelope.put("exit", 2);
                            Map<String, Object> error = new LinkedHashMap<>();
                            error.put("error", e.getMessage());
                            envelope.put("result", error);
                            emit(ctx, envelope);
                            continue;
                        }
                    }

                    // Refresh index only if needed (session cache)
                    // If frozenIndex is set, never refresh (skip stamp-driven rebuild)
                    boolean sourceIndependent = java.util.Set.of(
                            "skill", "describe", "version", "help").contains(command);
                    if (ctx.frozenIndex()) {
                        currentDiagnostics = ctx.sourceDiagnostics();
                    }
                    var refreshResult = sourceIndependent
                            ? new RefreshResult(ctx.indexed(), ctx.javaFiles(),
                                    ctx.projectModel(), ctx.fileSourceSets())
                            : loadOrRefreshIndex(
                                    Paths.get(ctx.rootPath()), ctx.javaFiles(), cachedIndex,
                                    ctx.frozenIndex(), ctx.config(), ctx.projectModel(),
                                    ctx.sourceSets(), ctx.noTest(), ctx.fileSourceSets());
                    if (sourceIndependent) {
                        currentDiagnostics = List.of();
                    }
                    cachedIndex = refreshResult.index();
                    List<Path> freshFiles = refreshResult.files();

                    // Capture output via injected stream — no System.setOut hack
                    var baos = new ByteArrayOutputStream();
                    var captureStream = new PrintStream(baos);
                    var captureFormatter = OutputFormatter.create(true, false, null, captureStream, budgetContext);
                    var freshCtx = ctx.withRuntimeState(
                            freshFiles,
                            captureFormatter,
                            cachedIndex,
                            refreshResult.projectModel(),
                            refreshResult.sourceSets());

                    // Execute command
                    // Extract budget profile from budgetContext if available
                    com.jsrc.app.cli.BudgetProfile commandProfile = budgetContext != null ? budgetContext.profile() : com.jsrc.app.cli.BudgetProfile.STANDARD;
                    Command cmd = CommandFactory.create("--" + command, arg, false, commandProfile);
                    if (cmd == null && !command.startsWith("--")) {
                        cmd = CommandFactory.createMethodSearch(command);
                    }
                    if (cmd == null) {
                        Map<String, Object> envelope = new LinkedHashMap<>();
                        envelope.put("exit", 1);
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("error", "Unknown command: " + command);
                        envelope.put("result", error);
                        emit(ctx, envelope);
                        continue;
                    }

                    int rawResult = cmd.execute(freshCtx);
                    captureStream.flush();
                    
                    // Parse the captured output as JSON, or wrap as raw string if not parseable
                    Object resultBody;
                    String output = baos.toString().trim();
                    try {
                        resultBody = JsonReader.parse(output);
                        if (resultBody == null) {
                            resultBody = output;
                        }
                    } catch (Exception parseEx) {
                        resultBody = output;
                    }
                    
                    // Map raw result through shared mapper
                    int exitCode = ExitCodeMapper.mapToExitCode(rawResult);
                    boolean incomplete = currentDiagnostics.stream().anyMatch(diagnostic ->
                            !"SOURCE_LEVEL_UNKNOWN".equals(diagnostic.code()));
                    if (incomplete && exitCode == com.jsrc.app.ExitCode.OK) {
                        exitCode = com.jsrc.app.ExitCode.IO_ERROR;
                    }
                    
                    // Emit envelope: {"exit": <mapped>, "result": <parsed>}
                    Map<String, Object> envelope = new LinkedHashMap<>();
                    envelope.put("exit", exitCode);
                    envelope.put("result", resultBody);
                    if (!currentDiagnostics.isEmpty()) {
                        envelope.put("status", "partial");
                        envelope.put("diagnostics", currentDiagnostics.stream()
                                .map(diagnostic -> Map.of(
                                        "code", diagnostic.code(),
                                        "file", diagnostic.file().toString(),
                                        "message", diagnostic.message()))
                                .toList());
                    }
                    emit(ctx, envelope);

                } catch (Exception e) {
                    Map<String, Object> envelope = new LinkedHashMap<>();
                    envelope.put("exit", 3);
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", e.getMessage());
                    envelope.put("result", error);
                    emit(ctx, envelope);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading stdin: " + e.getMessage());
        }
        return 0;
    }

    private static void emit(CommandContext context, Map<String, Object> response) {
        context.formatter().printResult(response);
        context.formatter().flush();
    }

    /**
     * Loads or refreshes the indexed codebase.
     * Checks a cheap stamp (index.bin mtime + source files count/mtime) before calling tryLoad.
     * Returns cached index if stamp hasn't changed.
     * When frozenIndex is true, skips stamp computation and never refreshes.
     * 
     * V2: Rediscovers Java files on each stamp check (non-frozen) to detect create/delete/rename.
     * V3: Returns RefreshResult containing both index and discovered file list.
     *
     * @param root project root
     * @param files current Java source files (used for frozen path; rediscovered for normal path)
     * @param cached previously cached IndexedCodebase, or null
     * @param frozenIndex if true, skip stamp-driven refresh (load once and never refresh)
     * @return RefreshResult with fresh or cached IndexedCodebase and file list, or null index if no index exists
     */
    protected RefreshResult loadOrRefreshIndex(Path root, List<Path> files, IndexedCodebase cached, boolean frozenIndex) {
        return loadOrRefreshIndex(
                root, files, cached, frozenIndex, null, null, java.util.Set.of(), false,
                java.util.Map.of());
    }

    private RefreshResult loadOrRefreshIndex(
            Path root,
            List<Path> files,
            IndexedCodebase cached,
            boolean frozenIndex,
            com.jsrc.app.config.ProjectConfig config,
            ProjectModel existingModel,
            java.util.Set<com.jsrc.app.project.SourceSet> sourceSets,
            boolean excludeTests,
            java.util.Map<Path, com.jsrc.app.project.SourceSet> existingSourceSets) {
        // Frozen mode: never refresh, load once and cache forever
        if (frozenIndex) {
            if (cached != null) {
                return new RefreshResult(cached, files, existingModel, existingSourceSets);
            }
            return new RefreshResult(
                    callTryLoadWithSourceLevels(root, files, files, frozenIndex,
                            existingSourceSets, existingModel, config),
                    files,
                    existingModel,
                    existingSourceSets);
        }
        
        // Normal mode: rediscover files on each stamp check (detect create/delete/rename)
        var projectSources = discoverJavaFiles(root, config, sourceSets, excludeTests);
        IndexStamp currentStamp = computeStamp(root, projectSources.files(),
                projectSources.model(), config);

        if (lastStamp != null && lastStamp.equals(currentStamp)) {
            currentDiagnostics = cachedDiagnostics;
            return new RefreshResult(
                    cached,
                    cachedAcceptedFiles,
                    projectSources.model(),
                    projectSources.sourceSets());
        }

        var compatibility = scanSources(
                projectSources.files(), projectSources.model(), config);
        currentDiagnostics = compatibility.diagnostics();
        cachedDiagnostics = currentDiagnostics;
        List<Path> freshFiles = compatibility.files();
        cachedAcceptedFiles = freshFiles;
        var fileSourceSets = projectSources.sourceSets();
        IndexedCodebase refreshed = callTryLoadWithSourceLevels(
                root, freshFiles, projectSources.files(), frozenIndex,
                fileSourceSets, projectSources.model(), config);
        lastStamp = new IndexStamp(
                indexMtime(root), currentStamp.files(), currentStamp.sourceVersions());
        return new RefreshResult(
                refreshed,
                freshFiles,
                projectSources.model(),
                fileSourceSets);
    }

    protected com.jsrc.app.project.SourceCompatibilityScanner.Result scanSources(
            List<Path> files, ProjectModel model,
            com.jsrc.app.config.ProjectConfig config) {
        return new com.jsrc.app.project.SourceCompatibilityScanner()
                .scan(files, model, config);
    }
    
    /**
     * Discover all .java files under root (rediscovery for watch refresh).
     */
    private ProjectSourceDiscovery.Result discoverJavaFiles(
            Path root,
            com.jsrc.app.config.ProjectConfig config,
            java.util.Set<com.jsrc.app.project.SourceSet> sourceSets,
            boolean excludeTests) {
        return new ProjectSourceDiscovery().discover(root, config, sourceSets, excludeTests);
    }

    /**
     * Wrapper for IndexedCodebase.tryLoad to allow test instrumentation.
     */
    protected IndexedCodebase callTryLoad(Path root, List<Path> files, boolean frozenIndex) {
        return IndexedCodebase.tryLoad(root, files, frozenIndex, pendingSourceSets,
                pendingSourceLevels, pendingDiscoveredFiles.isEmpty()
                        ? files : pendingDiscoveredFiles);
    }

    private IndexedCodebase callTryLoadWithSourceLevels(
            Path root, List<Path> files, List<Path> discoveredFiles, boolean frozenIndex,
            java.util.Map<Path, com.jsrc.app.project.SourceSet> sourceSets,
            ProjectModel model, com.jsrc.app.config.ProjectConfig config) {
        pendingSourceLevels = com.jsrc.app.project.SourceLevel.resolveFiles(
                discoveredFiles, model, config);
        pendingDiscoveredFiles = discoveredFiles;
        try {
            return callTryLoad(root, files, frozenIndex, sourceSets);
        } finally {
            pendingSourceLevels = java.util.Map.of();
            pendingDiscoveredFiles = List.of();
        }
    }

    protected IndexedCodebase callTryLoad(
            Path root,
            List<Path> files,
            boolean frozenIndex,
            java.util.Map<Path, com.jsrc.app.project.SourceSet> sourceSets) {
        pendingSourceSets = java.util.Map.copyOf(sourceSets);
        try {
            return callTryLoad(root, files, frozenIndex);
        } finally {
            pendingSourceSets = java.util.Map.of();
        }
    }

    /**
     * Computes a cheap stamp representing the current state of the index and source files.
     */
    private IndexStamp computeStamp(Path root, List<Path> files, ProjectModel model,
                                    com.jsrc.app.config.ProjectConfig config) {
        long indexMtime = indexMtime(root);
        java.util.Map<Path, FileStamp> fileStamps = new java.util.LinkedHashMap<>();
        for (Path file : files) {
            try {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                byte[] content = Files.readAllBytes(file);
                fileStamps.put(file, new FileStamp(
                        mtime, content.length,
                        com.jsrc.app.util.Hashing.sha256(content)));
            } catch (IOException e) {
                fileStamps.put(file, new FileStamp(-1, -1, "unreadable"));
            }
        }

        java.util.Map<Path, Integer> sourceVersions = new java.util.LinkedHashMap<>();
        for (Path file : files) {
            sourceVersions.put(file, com.jsrc.app.project.SourceLevel.resolve(file, model, config)
                    .map(com.jsrc.app.project.SourceLevel::version).orElse(0));
        }
        return new IndexStamp(indexMtime, fileStamps, sourceVersions);
    }

    private static long indexMtime(Path root) {
        Path indexBin = root.resolve(".jsrc/index.bin");
        try {
            if (Files.exists(indexBin)) {
                return Files.getLastModifiedTime(indexBin).toMillis();
            }
        } catch (IOException e) {
            // Ignore
        }
        return 0;
    }

    /**
     * Simple stamp record for detecting changes.
     */
    private record FileStamp(long modified, long size, String contentHash) {}

    private record IndexStamp(long indexMtime, java.util.Map<Path, FileStamp> files,
                              java.util.Map<Path, Integer> sourceVersions) {}

    /**
     * Result of index refresh containing both index and discovered files.
     */
    protected record RefreshResult(
            IndexedCodebase index,
            List<Path> files,
            ProjectModel projectModel,
            java.util.Map<Path, com.jsrc.app.project.SourceSet> sourceSets) {
        protected RefreshResult(
                IndexedCodebase index, List<Path> files, ProjectModel projectModel) {
            this(index, files, projectModel, classify(files, projectModel));
        }
    }

    private static java.util.Map<Path, com.jsrc.app.project.SourceSet> classify(
            List<Path> files, ProjectModel model) {
        if (model == null) {
            return java.util.Map.of();
        }
        return files.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Path::normalize, model::sourceSet));
    }
}
