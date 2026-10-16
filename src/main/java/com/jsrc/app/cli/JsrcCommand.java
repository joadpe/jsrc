package com.jsrc.app.cli;

import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(
        name = "jsrc",
        description = "Java source code navigator for AI agents",
        versionProvider = JsrcVersionProvider.class,
        mixinStandardHelpOptions = true)
public class JsrcCommand implements Runnable {

    private final CommandCatalog commandCatalog;

    @Mixin
    GlobalOptions globalOptions = new GlobalOptions();

    @Option(
            names = {"-d", "--dir"},
            paramLabel = "<source-root>",
            description = "Source root directory (defaults to current directory)",
            defaultValue = ".",
            scope = CommandLine.ScopeType.INHERIT)
    String sourceRoot;

    public JsrcCommand() {
        this(DefaultCommandRegistry.create());
    }

    public JsrcCommand(CommandCatalog commandCatalog) {
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
    }

    public CommandCatalog commandCatalog() {
        return commandCatalog;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public GlobalOptions globalOptions() {
        return globalOptions;
    }

    public boolean versionedJsonEnabled() {
        BudgetProfile profile = resolveBudgetProfile();
        boolean effectiveJson = OutputModeResolver.effectiveJson(
                globalOptions.jsonOutput(), globalOptions.mdOutput(), profile);
        return effectiveJson
                && globalOptions.jsonProtocol() == com.jsrc.app.output.JsonProtocol.V1;
    }

    public String resolvedRoot() {
        if (sourceRoot != null && !".".equals(sourceRoot)) {
            return sourceRoot;
        }
        return ".";
    }

    public ProjectConfig loadConfig() {
        return loadConfig(globalOptions.configPath(), resolvedRoot());
    }

    /** Loads the {@code --config} file when given, else {@code .jsrc.yaml} in the source root. */
    static ProjectConfig loadConfig(String configPath, String sourceRoot) {
        if (configPath != null && !configPath.isBlank()) {
            return ProjectConfig.loadFrom(Path.of(configPath)).orElse(null);
        }
        return ProjectConfig.load(Path.of(sourceRoot)).orElse(null);
    }

    public BudgetProfile resolveBudgetProfile() {
        return OutputModeResolver.resolveBudgetProfile(globalOptions.budget(), this::loadConfig);
    }

    public BudgetContext buildBudgetContext() {
        BudgetProfile profile = resolveBudgetProfile();
        return new BudgetContext(
                profile,
                globalOptions.limit(),
                globalOptions.maxBytes(),
                globalOptions.noBudgetMeta(),
                globalOptions.noNextCommands(),
                globalOptions.fields());
    }

    public CommandContext buildContext() {
        return buildContext(null, "unknown");
    }

    public CommandContext buildContext(String skipIndex) {
        return buildContext(skipIndex, "unknown");
    }

    public CommandContext buildContext(String skipIndex, String commandName) {
        String rootPath = resolvedRoot();
        ProjectConfig config = loadConfig();
        BudgetContext budgetContext = buildBudgetContext();
        BudgetProfile profile = budgetContext.profile();
        boolean effectiveJson = OutputModeResolver.effectiveJson(
                globalOptions.jsonOutput(), globalOptions.mdOutput(), profile);

        long discoveryStarted = System.nanoTime();
        var projectSources = new com.jsrc.app.project.ProjectSourceDiscovery()
                .discover(
                        Path.of(rootPath),
                        config,
                        globalOptions.sourceSets(),
                        globalOptions.noTest());
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("context.discovery", discoveryStarted);
        var projectModel = projectSources.model();
        long compatibilityStarted = System.nanoTime();
        boolean sourceIndependent = java.util.Set.of(
                "skill", "describe", "version", "help").contains(commandName);
        var compatibility = sourceIndependent
                ? new com.jsrc.app.project.SourceCompatibilityScanner.Result(
                        projectSources.allFiles(), java.util.List.of())
                : new com.jsrc.app.project.SourceCompatibilityScanner()
                        .scan(projectSources.allFiles(), projectModel, config);
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("context.compatibility", compatibilityStarted);
        var selectedPaths = new java.util.HashSet<>(projectSources.files());
        boolean indexCommand = "index".equals(commandName);
        var javaFiles = new ArrayList<>(compatibility.files().stream()
                .filter(file -> indexCommand || selectedPaths.contains(file))
                .toList());
        var diagnostics = compatibility.diagnostics().stream()
                .filter(diagnostic -> indexCommand || selectedPaths.contains(diagnostic.file()))
                .toList();
        printDiagnostics(diagnostics);

        var sourceLevels = com.jsrc.app.project.SourceLevel.resolveFiles(
                projectSources.allFiles(), projectModel, config);
        com.jsrc.app.index.SourceSnapshot sourceSnapshot;
        long snapshotStarted = System.nanoTime();
        try {
            Path configPath = globalOptions.configPath() == null
                    || globalOptions.configPath().isBlank()
                    ? null : Path.of(globalOptions.configPath());
            sourceSnapshot = sourceIndependent ? null
                    : com.jsrc.app.index.SourceSnapshot.capture(
                            projectModel.root(), configPath, config,
                            projectSources, compatibility);
        } catch (java.io.IOException ex) {
            throw new com.jsrc.app.exception.JsrcIOException(
                    "Cannot capture source snapshot: " + ex.getMessage(), ex);
        }
        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("context.source_snapshot", snapshotStarted);
        var parser = new HybridJavaParser(sourceLevels);
        OutputFormatter formatter = OutputFormatter.create(
                effectiveJson,
                globalOptions.signatureOnly(),
                globalOptions.fields(),
                System.out,
                budgetContext,
                globalOptions.jsonProtocol(),
                commandName,
                diagnostics);
        var sourceSets = projectSources.allSourceSets();
        long loadStarted = System.nanoTime();
        // Commands that never read sources have no source snapshot, so they must not
        // refresh or publish the index (a refresh without it would use no invokers).
        IndexedCodebase indexed = skipIndex != null || sourceIndependent
                ? null
                : IndexedCodebase.tryLoad(
                        projectModel.root(), javaFiles, globalOptions.frozenIndex(), sourceSets,
                        sourceLevels, projectSources.allFiles(), compatibility.files(),
                        sourceSnapshot);

        com.jsrc.app.index.IndexPhaseMetrics.recordPhase("context.index_load", loadStarted);
        return new CommandContext(
                javaFiles,
                rootPath,
                config,
                formatter,
                indexed,
                parser,
                globalOptions.mdOutput(),
                globalOptions.outDir(),
                globalOptions.fullOutput(),
                globalOptions.noTest(),
                budgetContext,
                globalOptions.frozenIndex(),
                projectModel,
                globalOptions.sourceSets(),
                sourceSets,
                diagnostics,
                sourceSnapshot);
    }

    /**
     * Prints source diagnostics to stderr. An undeclared source level usually applies to
     * the whole project, so it is reported once with a count instead of once per file.
     */
    static void printDiagnostics(List<com.jsrc.app.project.SourceDiagnostic> diagnostics) {
        var unknownLevels = diagnostics.stream()
                .filter(diagnostic -> "SOURCE_LEVEL_UNKNOWN".equals(diagnostic.code()))
                .toList();
        if (!unknownLevels.isEmpty()) {
            System.err.printf("SOURCE_LEVEL_UNKNOWN: %d file(s): %s"
                            + " (set javaVersion in .jsrc.yaml: 8..22, \"8\" for older sources)%n",
                    unknownLevels.size(), unknownLevels.getFirst().message());
        }
        diagnostics.stream()
                .filter(diagnostic -> !"SOURCE_LEVEL_UNKNOWN".equals(diagnostic.code()))
                .forEach(diagnostic -> System.err.printf(
                        "%s: %s: %s%n",
                        diagnostic.code(), diagnostic.file(), diagnostic.message()));
    }
}
