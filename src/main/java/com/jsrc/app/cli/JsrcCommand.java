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
        com.jsrc.app.cli.commands.OverviewSubcommand.class,
        com.jsrc.app.cli.commands.ClassesSubcommand.class,
        com.jsrc.app.cli.commands.SummarySubcommand.class,
        com.jsrc.app.cli.commands.MiniSubcommand.class,
        com.jsrc.app.cli.commands.ReadSubcommand.class,
        com.jsrc.app.cli.commands.HierarchySubcommand.class,
        com.jsrc.app.cli.commands.ImplementsSubcommand.class,
        com.jsrc.app.cli.commands.DepsSubcommand.class,
        com.jsrc.app.cli.commands.AnnotationsSubcommand.class,
        com.jsrc.app.cli.commands.RelatedSubcommand.class,
        com.jsrc.app.cli.commands.CallersSubcommand.class,
        com.jsrc.app.cli.commands.CalleesSubcommand.class,
        com.jsrc.app.cli.commands.CallChainSubcommand.class,
        com.jsrc.app.cli.commands.ImpactSubcommand.class,
        com.jsrc.app.cli.commands.TestForSubcommand.class
    }
)
public class JsrcCommand implements Runnable {

    @Mixin
    GlobalOptions globalOptions = new GlobalOptions();

    @Option(names = {"-d", "--dir"}, paramLabel = "<source-root>",
            description = "Source root directory (defaults to current directory)",
            defaultValue = ".")
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
     */
    public String resolvedRoot() {
        if (sourceRoot != null && !".".equals(sourceRoot)) {
            return sourceRoot;
        }
        // Try .jsrc.yaml for sourceRoot config
        var config = loadConfig();
        if (config != null && !config.sourceRoots().isEmpty()) {
            return ".";
        }
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
