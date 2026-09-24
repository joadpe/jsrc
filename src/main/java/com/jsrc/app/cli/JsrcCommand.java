package com.jsrc.app.cli;

import com.jsrc.app.codebase.CodeBaseLoader;
import com.jsrc.app.codebase.JavaCodeBase;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;
import java.nio.file.Files;
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

    public String resolvedRoot() {
        if (sourceRoot != null && !".".equals(sourceRoot)) {
            return sourceRoot;
        }
        return ".";
    }

    public ProjectConfig loadConfig() {
        if (globalOptions.configPath() != null) {
            return ProjectConfig.loadFrom(Path.of(globalOptions.configPath())).orElse(null);
        }
        return ProjectConfig.load(Path.of(".")).orElse(null);
    }

    public BudgetProfile resolveBudgetProfile() {
        if (globalOptions.budget() != null) {
            return BudgetProfile.fromString(globalOptions.budget());
        }
        String envBudget = System.getenv("JSRC_BUDGET");
        if (envBudget != null && !envBudget.isBlank()) {
            return BudgetProfile.fromString(envBudget);
        }
        ProjectConfig config = loadConfig();
        if (config != null && config.budget() != null) {
            return BudgetProfile.fromString(config.budget());
        }
        return BudgetProfile.STANDARD;
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
        return buildContext(null);
    }

    public CommandContext buildContext(String skipIndex) {
        String rootPath = resolvedRoot();
        ProjectConfig config = loadConfig();
        BudgetContext budgetContext = buildBudgetContext();
        BudgetProfile profile = budgetContext.profile();
        boolean effectiveJson = globalOptions.jsonOutput()
                || (profile.forceJson() && !globalOptions.mdOutput());

        var loader = new CodeBaseLoader();
        var javaFiles = new ArrayList<Path>();
        if (config != null && config.sourceRoots().size() > 1) {
            for (String root : config.sourceRoots()) {
                Path rootDir = Path.of(root);
                if (!rootDir.isAbsolute()) {
                    rootDir = Path.of(rootPath).resolve(root);
                }
                if (Files.isDirectory(rootDir)) {
                    javaFiles.addAll(loader.loadFilesFrom(rootDir.toString(), "java"));
                }
            }
        } else {
            var project = new JavaCodeBase(rootPath, loader);
            javaFiles.addAll(project.getFiles());
        }

        if (config != null && !config.excludes().isEmpty()) {
            javaFiles = new ArrayList<>(filterExcludes(javaFiles, config.excludes()));
        }

        var parser = new HybridJavaParser();
        OutputFormatter formatter = OutputFormatter.create(
                effectiveJson,
                globalOptions.signatureOnly(),
                globalOptions.fields(),
                System.out,
                budgetContext);
        IndexedCodebase indexed = skipIndex != null
                ? null
                : IndexedCodebase.tryLoad(
                        Paths.get(rootPath), javaFiles, globalOptions.frozenIndex());

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
                globalOptions.frozenIndex());
    }

    private static List<Path> filterExcludes(List<Path> files, List<String> excludes) {
        return files.stream()
                .filter(file -> excludes.stream().noneMatch(exclude -> {
                    String pattern = exclude.replace("**", ".*").replace("*", "[^/]*");
                    return file.toString().matches(".*" + pattern + ".*");
                }))
                .toList();
    }
}
