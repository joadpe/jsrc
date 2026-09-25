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
        if (globalOptions.configPath() != null && !globalOptions.configPath().isBlank()) {
            return ProjectConfig.loadFrom(Path.of(globalOptions.configPath())).orElse(null);
        }
        return ProjectConfig.load(Path.of(resolvedRoot())).orElse(null);
    }

    public BudgetProfile resolveBudgetProfile() {
        return OutputModeResolver.resolveBudgetProfile(
                globalOptions.budget(), globalOptions.configPath());
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

        var projectSources = new com.jsrc.app.project.ProjectSourceDiscovery()
                .discover(
                        Path.of(rootPath),
                        config,
                        globalOptions.sourceSets(),
                        globalOptions.noTest());
        var projectModel = projectSources.model();
        var javaFiles = new ArrayList<>(projectSources.files());

        var parser = new HybridJavaParser();
        OutputFormatter formatter = OutputFormatter.create(
                effectiveJson,
                globalOptions.signatureOnly(),
                globalOptions.fields(),
                System.out,
                budgetContext,
                globalOptions.jsonProtocol(),
                commandName);
        var sourceSets = projectSources.sourceSets();
        IndexedCodebase indexed = skipIndex != null
                ? null
                : IndexedCodebase.tryLoad(
                        projectModel.root(), javaFiles, globalOptions.frozenIndex(), sourceSets);

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
                sourceSets);
    }
}
