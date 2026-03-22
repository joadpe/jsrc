package com.jsrc.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.ExitCode;
import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;

/**
 * Root command for jsrc CLI. Picocli entry point.
 * Subcommands are registered here. Global options are available
 * to all subcommands via the {@link GlobalOptions} mixin.
 */
@Command(
    name = "jsrc",
    description = "Java source code navigator for AI agents",
    version = "jsrc 2.1.0",
    mixinStandardHelpOptions = true,
    subcommands = {
        CommandLine.HelpCommand.class,
        // Navigation
        com.jsrc.app.cli.adapters.OverviewAdapter.class,
        com.jsrc.app.cli.adapters.ClassesAdapter.class,
        com.jsrc.app.cli.adapters.SummaryAdapter.class,
        com.jsrc.app.cli.adapters.MiniAdapter.class,
        com.jsrc.app.cli.adapters.ReadAdapter.class,
        com.jsrc.app.cli.adapters.HierarchyAdapter.class,
        com.jsrc.app.cli.adapters.ImplementsAdapter.class,
        com.jsrc.app.cli.adapters.DepsAdapter.class,
        com.jsrc.app.cli.adapters.AnnotationsAdapter.class,
        com.jsrc.app.cli.adapters.RelatedAdapter.class,
        // Call graph
        com.jsrc.app.cli.adapters.CallersAdapter.class,
        com.jsrc.app.cli.adapters.CalleesAdapter.class,
        com.jsrc.app.cli.adapters.CallChainAdapter.class,
        com.jsrc.app.cli.adapters.ImpactAdapter.class,
        com.jsrc.app.cli.adapters.TestForAdapter.class,
        // Search
        com.jsrc.app.cli.adapters.SearchAdapter.class,
        com.jsrc.app.cli.adapters.FindAdapter.class,
        com.jsrc.app.cli.adapters.ScopeAdapter.class,
        com.jsrc.app.cli.adapters.UnusedAdapter.class,
        // Analysis
        com.jsrc.app.cli.adapters.SmellsAdapter.class,
        com.jsrc.app.cli.adapters.ComplexityAdapter.class,
        com.jsrc.app.cli.adapters.LintAdapter.class,
        com.jsrc.app.cli.adapters.HotspotsAdapter.class,
        com.jsrc.app.cli.adapters.PackagesAdapter.class,
        com.jsrc.app.cli.adapters.StyleAdapter.class,
        com.jsrc.app.cli.adapters.PatternsAdapter.class,
        com.jsrc.app.cli.adapters.SnippetAdapter.class,
        // Architecture
        com.jsrc.app.cli.adapters.CheckAdapter.class,
        com.jsrc.app.cli.adapters.EndpointsAdapter.class,
        com.jsrc.app.cli.adapters.EntryPointsAdapter.class,
        com.jsrc.app.cli.adapters.ValidateAdapter.class,
        com.jsrc.app.cli.adapters.ImportsAdapter.class,
        com.jsrc.app.cli.adapters.LayerAdapter.class,
        // Reverse engineering
        com.jsrc.app.cli.adapters.ContextAdapter.class,
        com.jsrc.app.cli.adapters.ContextForAdapter.class,
        com.jsrc.app.cli.adapters.ContractAdapter.class,
        com.jsrc.app.cli.adapters.VerifyAdapter.class,
        com.jsrc.app.cli.adapters.DriftAdapter.class,
        com.jsrc.app.cli.adapters.DiffAdapter.class,
        com.jsrc.app.cli.adapters.ChangedAdapter.class,
        // Meta
        com.jsrc.app.cli.adapters.IndexAdapter.class,
        com.jsrc.app.cli.adapters.MapAdapter.class,
        com.jsrc.app.cli.adapters.BatchAdapter.class,
        com.jsrc.app.cli.adapters.WatchAdapter.class,
        com.jsrc.app.cli.adapters.ExplainAdapter.class,
        com.jsrc.app.cli.adapters.SimilarAdapter.class,
        com.jsrc.app.cli.adapters.ResolveAdapter.class,
        com.jsrc.app.cli.adapters.HistoryAdapter.class,
        com.jsrc.app.cli.adapters.StatsAdapter.class,
        com.jsrc.app.cli.adapters.ChecklistAdapter.class,
        com.jsrc.app.cli.adapters.TypeCheckAdapter.class,
        com.jsrc.app.cli.adapters.BreakingChangesAdapter.class,
        com.jsrc.app.cli.adapters.DiffImpactAdapter.class,
        com.jsrc.app.cli.adapters.DumpAdapter.class,
        com.jsrc.app.cli.adapters.PerfAdapter.class,
        com.jsrc.app.cli.adapters.SecurityAdapter.class,
        com.jsrc.app.cli.adapters.TodoAdapter.class,
        com.jsrc.app.cli.adapters.FlowAdapter.class,
        com.jsrc.app.cli.adapters.DebtAdapter.class,
        com.jsrc.app.cli.adapters.MigrateAdapter.class,
        com.jsrc.app.cli.adapters.ApiAdapter.class,
        com.jsrc.app.cli.adapters.CompatAdapter.class,
        com.jsrc.app.cli.adapters.TourAdapter.class,
        com.jsrc.app.cli.adapters.DocAdapter.class,
        com.jsrc.app.cli.adapters.ScaffoldAdapter.class
    }
)
public class JsrcCommand implements Runnable {

    @Mixin
    GlobalOptions globalOptions = new GlobalOptions();

    @Option(names = {"-d", "--dir"}, paramLabel = "<source-root>",
            description = "Source root directory (defaults to current directory)",
            defaultValue = ".", scope = CommandLine.ScopeType.INHERIT)
    String sourceRoot;

    @Override
    public void run() {
        // When invoked without subcommand, print help
        CommandLine.usage(this, System.out);
    }

    /**
     * Returns the global options mixin.
     */
    public GlobalOptions globalOptions() {
        return globalOptions;
    }



    /**
     * Resolves the effective source root path.
     * If --dir is set explicitly, use it. Otherwise use .jsrc.yaml config or ".".
     */
    public String resolvedRoot() {
        if (sourceRoot != null && !".".equals(sourceRoot)) {
            return sourceRoot;
        }
        // Default to current directory (config sourceRoots handled in buildContext)
        return ".";
    }

    /**
     * Loads project configuration from .jsrc.yaml.
     */
    public ProjectConfig loadConfig() {
        if (globalOptions.configPath() != null) {
            return ProjectConfig.loadFrom(Path.of(globalOptions.configPath())).orElse(null);
        }
        return ProjectConfig.load(Path.of(".")).orElse(null);
    }

    /**
     * Builds a CommandContext from the current global options.
     * This is the bridge between picocli-parsed options and the existing
     * Command infrastructure.
     */
    public CommandContext buildContext() {
        return buildContext(null);
    }

    /**
     * Builds a CommandContext, optionally skipping index load for specific commands.
     *
     * @param skipIndex command name that doesn't need index (e.g., "--diff")
     */
    public CommandContext buildContext(String skipIndex) {
        String rootPath = resolvedRoot();
        ProjectConfig config = loadConfig();

        var loader = new CodeBaseLoader();
        var javaFiles = new ArrayList<Path>();
        if (config != null && config.sourceRoots().size() > 1) {
            for (String root : config.sourceRoots()) {
                Path rootDir = Path.of(root);
                if (!rootDir.isAbsolute()) rootDir = Path.of(rootPath).resolve(root);
                if (Files.isDirectory(rootDir)) {
                    javaFiles.addAll(loader.loadFilesFrom(rootDir.toString(), "java"));
                }
            }
        } else {
            var project = new JavaCodeBase(rootPath, loader);
            javaFiles.addAll(project.getFiles());
        }

        // Apply excludes
        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = new ArrayList<>(filterExcludes(javaFiles, config.excludes()));
        }

        var parser = new HybridJavaParser();
        OutputFormatter formatter = OutputFormatter.create(
                globalOptions.jsonOutput(), globalOptions.signatureOnly(), globalOptions.fields());

        IndexedCodebase indexed = skipIndex != null ? null
                : IndexedCodebase.tryLoad(Paths.get(rootPath), javaFiles);

        return new CommandContext(javaFiles, rootPath, config, formatter, indexed, parser,
                globalOptions.mdOutput(), globalOptions.outDir(),
                globalOptions.fullOutput(), globalOptions.noTest());
    }

    private static List<Path> filterExcludes(List<Path> files, List<String> excludes) {
        return files.stream()
                .filter(f -> excludes.stream().noneMatch(ex -> {
                    String pattern = ex.replace("**", ".*").replace("*", "[^/]*");
                    return f.toString().matches(".*" + pattern + ".*");
                }))
                .toList();
    }
}
