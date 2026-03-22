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
        com.jsrc.app.cli.adapters.OverviewSubcommand.class,
        com.jsrc.app.cli.adapters.ClassesSubcommand.class,
        com.jsrc.app.cli.adapters.SummarySubcommand.class,
        com.jsrc.app.cli.adapters.MiniSubcommand.class,
        com.jsrc.app.cli.adapters.ReadSubcommand.class,
        com.jsrc.app.cli.adapters.HierarchySubcommand.class,
        com.jsrc.app.cli.adapters.ImplementsSubcommand.class,
        com.jsrc.app.cli.adapters.DepsSubcommand.class,
        com.jsrc.app.cli.adapters.AnnotationsSubcommand.class,
        com.jsrc.app.cli.adapters.RelatedSubcommand.class,
        // Call graph
        com.jsrc.app.cli.adapters.CallersSubcommand.class,
        com.jsrc.app.cli.adapters.CalleesSubcommand.class,
        com.jsrc.app.cli.adapters.CallChainSubcommand.class,
        com.jsrc.app.cli.adapters.ImpactSubcommand.class,
        com.jsrc.app.cli.adapters.TestForSubcommand.class,
        // Search
        com.jsrc.app.cli.adapters.SearchSubcommand.class,
        com.jsrc.app.cli.adapters.FindSubcommand.class,
        com.jsrc.app.cli.adapters.ScopeSubcommand.class,
        com.jsrc.app.cli.adapters.UnusedSubcommand.class,
        // Analysis
        com.jsrc.app.cli.adapters.SmellsSubcommand.class,
        com.jsrc.app.cli.adapters.ComplexitySubcommand.class,
        com.jsrc.app.cli.adapters.LintSubcommand.class,
        com.jsrc.app.cli.adapters.HotspotsSubcommand.class,
        com.jsrc.app.cli.adapters.PackagesSubcommand.class,
        com.jsrc.app.cli.adapters.StyleSubcommand.class,
        com.jsrc.app.cli.adapters.PatternsSubcommand.class,
        com.jsrc.app.cli.adapters.SnippetSubcommand.class,
        // Architecture
        com.jsrc.app.cli.adapters.CheckSubcommand.class,
        com.jsrc.app.cli.adapters.EndpointsSubcommand.class,
        com.jsrc.app.cli.adapters.EntryPointsSubcommand.class,
        com.jsrc.app.cli.adapters.ValidateSubcommand.class,
        com.jsrc.app.cli.adapters.ImportsSubcommand.class,
        com.jsrc.app.cli.adapters.LayerSubcommand.class,
        // Reverse engineering
        com.jsrc.app.cli.adapters.ContextSubcommand.class,
        com.jsrc.app.cli.adapters.ContextForSubcommand.class,
        com.jsrc.app.cli.adapters.ContractSubcommand.class,
        com.jsrc.app.cli.adapters.VerifySubcommand.class,
        com.jsrc.app.cli.adapters.DriftSubcommand.class,
        com.jsrc.app.cli.adapters.DiffSubcommand.class,
        com.jsrc.app.cli.adapters.ChangedSubcommand.class,
        // Meta
        com.jsrc.app.cli.adapters.IndexSubcommand.class,
        com.jsrc.app.cli.adapters.MapSubcommand.class,
        com.jsrc.app.cli.adapters.BatchSubcommand.class,
        com.jsrc.app.cli.adapters.WatchSubcommand.class,
        com.jsrc.app.cli.adapters.ExplainSubcommand.class,
        com.jsrc.app.cli.adapters.SimilarSubcommand.class,
        com.jsrc.app.cli.adapters.ResolveSubcommand.class,
        com.jsrc.app.cli.adapters.HistorySubcommand.class,
        com.jsrc.app.cli.adapters.StatsSubcommand.class,
        com.jsrc.app.cli.adapters.ChecklistSubcommand.class,
        com.jsrc.app.cli.adapters.TypeCheckSubcommand.class,
        com.jsrc.app.cli.adapters.BreakingChangesSubcommand.class,
        com.jsrc.app.cli.adapters.DiffImpactSubcommand.class,
        com.jsrc.app.cli.adapters.DumpSubcommand.class,
        com.jsrc.app.cli.adapters.PerfSubcommand.class,
        com.jsrc.app.cli.adapters.SecuritySubcommand.class,
        com.jsrc.app.cli.adapters.TodoSubcommand.class,
        com.jsrc.app.cli.adapters.FlowSubcommand.class,
        com.jsrc.app.cli.adapters.DebtSubcommand.class,
        com.jsrc.app.cli.adapters.MigrateSubcommand.class,
        com.jsrc.app.cli.adapters.ApiSubcommand.class,
        com.jsrc.app.cli.adapters.CompatSubcommand.class,
        com.jsrc.app.cli.adapters.TourSubcommand.class,
        com.jsrc.app.cli.adapters.DocSubcommand.class,
        com.jsrc.app.cli.adapters.ScaffoldSubcommand.class
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
