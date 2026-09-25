package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.analysis.DependencyAnalyzer;
import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.SourceReader;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Shared context for all commands. Created once, passed to each command.
 * Provides lazy-loaded shared services (call graph, etc.) to avoid
 * redundant expensive operations across commands.
 */
public final class CommandContext {

    private final List<Path> javaFiles;
    private final String rootPath;
    private final ProjectConfig config;
    private final OutputFormatter formatter;
    private final IndexedCodebase indexed;
    private final CodeParser parser;
    private final boolean mdOutput;
    private final String outDir;
    private final boolean fullOutput;
    private final boolean noTest;
    private final com.jsrc.app.cli.BudgetContext budgetContext;
    private final boolean frozenIndex;
    private final com.jsrc.app.project.ProjectModel projectModel;
    private final java.util.Set<com.jsrc.app.project.SourceSet> sourceSets;
    private final java.util.Map<Path, com.jsrc.app.project.SourceSet> fileSourceSets;

    private CallGraph callGraphCache;
    private DependencyAnalyzer dependencyAnalyzerCache;
    private SourceReader sourceReaderCache;

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, false, null, false, false, null, false);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir, false, false, null, false);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir, fullOutput, false, null, false);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir, fullOutput, noTest, null, false);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest,
                          com.jsrc.app.cli.BudgetContext budgetContext) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir, fullOutput, noTest, budgetContext, false);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest,
                          com.jsrc.app.cli.BudgetContext budgetContext, boolean frozenIndex) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir,
                fullOutput, noTest, budgetContext, frozenIndex, null);
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest,
                          com.jsrc.app.cli.BudgetContext budgetContext, boolean frozenIndex,
                          com.jsrc.app.project.ProjectModel projectModel) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir,
                fullOutput, noTest, budgetContext, frozenIndex, projectModel, java.util.Set.of());
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest,
                          com.jsrc.app.cli.BudgetContext budgetContext, boolean frozenIndex,
                          com.jsrc.app.project.ProjectModel projectModel,
                          java.util.Set<com.jsrc.app.project.SourceSet> sourceSets) {
        this(javaFiles, rootPath, config, formatter, indexed, parser, mdOutput, outDir,
                fullOutput, noTest, budgetContext, frozenIndex, projectModel, sourceSets,
                classify(javaFiles, projectModel));
    }

    public CommandContext(List<Path> javaFiles, String rootPath, ProjectConfig config,
                          OutputFormatter formatter, IndexedCodebase indexed, CodeParser parser,
                          boolean mdOutput, String outDir, boolean fullOutput, boolean noTest,
                          com.jsrc.app.cli.BudgetContext budgetContext, boolean frozenIndex,
                          com.jsrc.app.project.ProjectModel projectModel,
                          java.util.Set<com.jsrc.app.project.SourceSet> sourceSets,
                          java.util.Map<Path, com.jsrc.app.project.SourceSet> fileSourceSets) {
        this.javaFiles = javaFiles;
        this.rootPath = rootPath;
        this.config = config;
        this.formatter = formatter;
        this.indexed = indexed;
        this.parser = parser;
        this.mdOutput = mdOutput;
        this.outDir = outDir;
        this.fullOutput = fullOutput;
        this.noTest = noTest;
        this.budgetContext = budgetContext;
        this.frozenIndex = frozenIndex;
        this.projectModel = projectModel;
        this.sourceSets = java.util.Set.copyOf(sourceSets);
        this.fileSourceSets = java.util.Map.copyOf(fileSourceSets);
    }

    public List<Path> javaFiles() { return javaFiles; }
    public String rootPath() { return rootPath; }
    public ProjectConfig config() { return config; }
    public OutputFormatter formatter() { return formatter; }
    public IndexedCodebase indexed() { return indexed; }
    public CodeParser parser() { return parser; }
    public boolean mdOutput() { return mdOutput; }
    public String outDir() { return outDir; }
    public boolean fullOutput() { return fullOutput; }
    public boolean noTest() { return noTest; }
    public com.jsrc.app.cli.BudgetContext budgetContext() { return budgetContext; }
    public boolean frozenIndex() { return frozenIndex; }
    public com.jsrc.app.project.ProjectModel projectModel() { return projectModel; }
    public java.util.Set<com.jsrc.app.project.SourceSet> sourceSets() { return sourceSets; }
    public java.util.Map<Path, com.jsrc.app.project.SourceSet> fileSourceSets() {
        return fileSourceSets;
    }

    public com.jsrc.app.project.SourceSet sourceSet(Path file) {
        return fileSourceSets.getOrDefault(
                file.normalize(),
                projectModel == null
                        ? com.jsrc.app.project.SourceSet.UNKNOWN
                        : projectModel.sourceSet(file));
    }

    /**
     * Creates a context for a refreshed execution while preserving command options and project metadata.
     */
    public CommandContext withRuntimeState(
            List<Path> files, OutputFormatter outputFormatter, IndexedCodebase codebase) {
        return withRuntimeState(files, outputFormatter, codebase, projectModel);
    }

    public CommandContext withRuntimeState(
            List<Path> files,
            OutputFormatter outputFormatter,
            IndexedCodebase codebase,
            com.jsrc.app.project.ProjectModel refreshedProjectModel) {
        return new CommandContext(
                files, rootPath, config, outputFormatter, codebase, parser,
                mdOutput, outDir, fullOutput, noTest, budgetContext, frozenIndex,
                refreshedProjectModel, sourceSets, classify(files, refreshedProjectModel));
    }

    public CommandContext withRuntimeState(
            List<Path> files,
            OutputFormatter outputFormatter,
            IndexedCodebase codebase,
            com.jsrc.app.project.ProjectModel refreshedProjectModel,
            java.util.Map<Path, com.jsrc.app.project.SourceSet> refreshedSourceSets) {
        return new CommandContext(
                files, rootPath, config, outputFormatter, codebase, parser,
                mdOutput, outDir, fullOutput, noTest, budgetContext, frozenIndex,
                refreshedProjectModel, sourceSets, refreshedSourceSets);
    }

    private static java.util.Map<Path, com.jsrc.app.project.SourceSet> classify(
            List<Path> files, com.jsrc.app.project.ProjectModel model) {
        if (model == null) {
            return java.util.Map.of();
        }
        return files.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Path::normalize, model::sourceSet));
    }

    private java.util.Map<String, String> qualifiedNameCache;

    /**
     * Resolves a simple class name to its fully qualified name.
     * Returns the input unchanged if not found in the index.
     */
    public String qualify(String simpleName) {
        if (simpleName == null || simpleName.contains(".")) return simpleName;
        if (qualifiedNameCache == null) {
            qualifiedNameCache = new java.util.HashMap<>();
            java.util.Map<String, java.util.Set<String>> candidates = new java.util.HashMap<>();
            for (var ci : getAllClasses()) {
                candidates.computeIfAbsent(ci.name(), ignored -> new java.util.HashSet<>())
                        .add(ci.qualifiedName());
                var typeId = com.jsrc.app.model.TypeId.from(ci.packageName(), ci.name());
                candidates.computeIfAbsent(typeId.simpleName(),
                                ignored -> new java.util.HashSet<>())
                        .add(typeId.canonicalName());
            }
            for (var entry : candidates.entrySet()) {
                if (entry.getValue().size() == 1) {
                    qualifiedNameCache.put(
                            entry.getKey(), entry.getValue().iterator().next());
                }
            }
        }
        return qualifiedNameCache.getOrDefault(simpleName, simpleName);
    }

    /**
     * Returns all classes, using index if available, parsing on-the-fly otherwise.
     * When noTest is true, filters out classes from test paths.
     */
    public List<ClassInfo> getAllClasses() {
        List<ClassInfo> all;
        if (indexed != null) {
            all = indexed.getAllClasses();
        } else {
            all = new ArrayList<>();
            for (Path file : javaFiles) all.addAll(parser.parseClasses(file));
        }
        if (noTest) {
            all = all.stream().filter(ci -> !isTestClass(ci)).toList();
        }
        return all;
    }

    /**
     * Checks if a class is from a test path based on its qualified name or source file.
     */
    private boolean isTestClass(ClassInfo ci) {
        // Check by file path in index
        if (indexed != null) {
            var sourceSet = indexed.findSourceSetForClass(ci.name());
            if (sourceSet.isPresent()
                    && sourceSet.get() != com.jsrc.app.project.SourceSet.UNKNOWN) {
                return sourceSet.get().isTest();
            }
            var filePath = indexed.findFileForClass(ci.name());
            if (filePath.isPresent()) {
                return isTestPath(filePath.get());
            }
        }
        // Fallback: check by file in javaFiles
        for (Path f : javaFiles) {
            if (f.getFileName().toString().equals(ci.name() + ".java")) {
                return isTestPath(f.toString());
            }
        }
        return false;
    }

    /**
     * Returns true if the file path indicates a test source.
     */
    public static boolean isTestPath(String path) {
        return path.contains("/test/") || path.contains("/testFixtures/");
    }

    /**
     * Returns a shared DependencyAnalyzer instance.
     */
    public DependencyAnalyzer dependencyAnalyzer() {
        if (dependencyAnalyzerCache == null) {
            dependencyAnalyzerCache = new DependencyAnalyzer();
        }
        return dependencyAnalyzerCache;
    }

    /**
     * Returns a shared SourceReader instance.
     */
    public SourceReader sourceReader() {
        if (sourceReaderCache == null) {
            sourceReaderCache = new SourceReader(parser);
        }
        return sourceReaderCache;
    }

    /**
     * Returns a shared, lazily-built call graph.
     * Uses the index if available (fast), falls back to full file parsing.
     * The same instance is returned on subsequent calls.
     */
    public CallGraph callGraph() {
        if (callGraphCache != null) return callGraphCache;

        // Use pre-built call graph from V2 binary index (instant)
        if (indexed != null && indexed.preBuiltCallGraph() != null) {
            callGraphCache = indexed.preBuiltCallGraph();
            return callGraphCache;
        }

        // Fallback: build from edges or parse files
        var builder = new CallGraphBuilder();
        if (indexed != null && indexed.hasCallEdges()) {
            builder.loadFromIndex(indexed.getEntries());
        } else {
            builder.build(javaFiles);
        }
        callGraphCache = builder.toCallGraph();
        return callGraphCache;
    }
}
