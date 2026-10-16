package com.jsrc.app.project;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.jsrc.app.config.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;

/** Validates selected files against their declared source language level. */
public final class SourceCompatibilityScanner {

    private static final java.util.regex.Pattern VAR_LAMBDA = java.util.regex.Pattern.compile(
            "\\(\\s*var\\s+[A-Za-z_$][\\w$]*\\s*\\)\\s*->");
    /** Files per parallel task; small enough to balance uneven file sizes across threads. */
    private static final int BATCH_SIZE = 32;

    public record Result(
            List<Path> files, List<SourceDiagnostic> diagnostics, int parsedFiles,
            Map<Path, String> acceptedHashes) {
        public Result(List<Path> files, List<SourceDiagnostic> diagnostics) {
            this(files, diagnostics, 0, Map.of());
        }

        public Result(List<Path> files, List<SourceDiagnostic> diagnostics,
                      int parsedFiles) {
            this(files, diagnostics, parsedFiles, Map.of());
        }

        public Result {
            files = List.copyOf(files);
            diagnostics = List.copyOf(diagnostics);
            acceptedHashes = Map.copyOf(acceptedHashes);
        }
    }

    public Result scan(List<Path> files, ProjectModel model, ProjectConfig config) {
        if (files.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        Map<Path, com.jsrc.app.index.IndexEntry> cached = cachedEntries(model);
        List<List<Path>> batches = new ArrayList<>();
        for (int start = 0; start < files.size(); start += BATCH_SIZE) {
            batches.add(files.subList(start, Math.min(files.size(), start + BATCH_SIZE)));
        }
        // Batches parse concurrently; the ordered merge keeps results in input order.
        List<Result> results = parallelRetryingOnCaller(
                batches, batch -> scanBatch(batch, model, config, cached));
        List<Path> accepted = new ArrayList<>();
        Map<Path, String> acceptedHashes = new HashMap<>();
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        int parsedFiles = 0;
        for (Result result : results) {
            accepted.addAll(result.files());
            acceptedHashes.putAll(result.acceptedHashes());
            diagnostics.addAll(result.diagnostics());
            parsedFiles += result.parsedFiles();
        }
        return new Result(accepted, diagnostics, parsedFiles, acceptedHashes);
    }

    /**
     * Applies {@code task}, which must not return null, to the items in parallel and returns
     * the results in input order. Worker threads have smaller stacks than the main thread of
     * a native image, so an item whose task overflows a worker's stack (deeply nested
     * sources) runs again on the calling thread instead of failing every item.
     */
    static <T, R> List<R> parallelRetryingOnCaller(
            List<T> items, java.util.function.Function<T, R> task) {
        List<Optional<R>> attempts = items.parallelStream()
                .map(item -> {
                    try {
                        return Optional.of(task.apply(item));
                    } catch (StackOverflowError deeplyNested) {
                        return Optional.<R>empty();
                    }
                })
                .toList();
        List<R> results = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            Optional<R> attempt = attempts.get(index);
            results.add(attempt.isPresent() ? attempt.get() : task.apply(items.get(index)));
        }
        return results;
    }

    /** Scans files sequentially with parsers owned by this batch; parsers are not thread-safe. */
    private static Result scanBatch(List<Path> files, ProjectModel model, ProjectConfig config,
                                    Map<Path, com.jsrc.app.index.IndexEntry> cached) {
        List<Path> accepted = new ArrayList<>();
        Map<Path, String> acceptedHashes = new HashMap<>();
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        Map<Integer, JavaParser> parsers = new HashMap<>();
        int parsedFiles = 0;
        try (var treeParser = new io.github.treesitter.jtreesitter.Parser(
                com.jsrc.app.parser.TreeSitterLanguageFactory.getLanguage("java"))) {
            for (Path file : files) {
                Optional<SourceLevel> level = SourceLevel.resolve(file, model, config);
                if (level.isEmpty()) {
                    diagnostics.add(new SourceDiagnostic(
                            "SOURCE_LEVEL_UNKNOWN", file,
                            "Java source level is not declared; compatibility is provisional"));
                }
                int version = level.map(SourceLevel::version).orElse(21);
                if (version < 8 || version > 22) {
                    diagnostics.add(new SourceDiagnostic(
                            "SOURCE_LEVEL_UNSUPPORTED", file,
                            "Java source level " + version + " is outside supported range 8..22"));
                    continue;
                }
                try {
                    byte[] content = Files.readAllBytes(file);
                    String hash = com.jsrc.app.util.Hashing.sha256(content);
                    var entry = cached.get(file.toAbsolutePath().normalize());
                    int sourceVersion = level.map(SourceLevel::version).orElse(0);
                    if (entry != null && entry.sourceVersion() == sourceVersion
                            && entry.contentHash().equals(hash)) {
                        accepted.add(file);
                        acceptedHashes.put(file, hash);
                        continue;
                    }
                    String source = com.jsrc.app.util.SourceText.decode(content);
                    parsedFiles++;
                    JavaParser parser = parsers.computeIfAbsent(
                            version, SourceCompatibilityScanner::parser);
                    var result = parser.parse(source);
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        boolean parserLimitation = isVarLambdaParserLimitation(
                                version, source, result);
                        diagnostics.add(new SourceDiagnostic(
                                parserLimitation ? "SOURCE_PARSER_LIMITATION"
                                        : "SOURCE_SYNTAX_UNSUPPORTED",
                                file,
                                parserLimitation
                                        ? "JavaParser cannot parse var lambda parameters in Java "
                                                + version + "; semantic data quarantined"
                                        : "Source cannot be parsed as Java " + version));
                        continue;
                    }
                    try (var tree = treeParser.parse(source).orElseThrow()) {
                        if (hasSyntaxErrors(tree)) {
                            diagnostics.add(new SourceDiagnostic(
                                    "SOURCE_SYNTAX_UNSUPPORTED", file,
                                    "Tree-sitter found syntax errors or missing tokens"));
                            continue;
                        }
                    }
                    var unit = result.getResult().orElseThrow();
                    boolean declaresVarType = unit.getTypes().stream()
                            .anyMatch(type -> "var".equals(type.getNameAsString()));
                    if (version < 10 && !declaresVarType && unit
                            .findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                            .stream()
                            .anyMatch(variable -> "var".equals(variable.getTypeAsString())
                                    && variable.findAncestor(
                                            com.github.javaparser.ast.expr.VariableDeclarationExpr.class)
                                            .isPresent())) {
                        diagnostics.add(new SourceDiagnostic(
                                "SOURCE_SYNTAX_UNCERTAIN", file,
                                "Local type named var may be incompatible with Java " + version));
                        continue;
                    }
                    if (version < 11 && unit
                            .findAll(com.github.javaparser.ast.expr.LambdaExpr.class)
                            .stream()
                            .flatMap(lambda -> lambda.getParameters().stream())
                            .anyMatch(parameter -> "var".equals(parameter.getTypeAsString()))) {
                        diagnostics.add(new SourceDiagnostic(
                                "SOURCE_SYNTAX_UNCERTAIN", file,
                                "Lambda parameter type named var may be incompatible with Java "
                                        + version));
                        continue;
                    }
                    accepted.add(file);
                    acceptedHashes.put(file, hash);
                } catch (IOException exception) {
                    diagnostics.add(new SourceDiagnostic(
                            "PARSE_PARTIAL", file,
                            "Could not read source: " + exception.getMessage()));
                }
            }
        }
        return new Result(accepted, diagnostics, parsedFiles, acceptedHashes);
    }

    private static Map<Path, com.jsrc.app.index.IndexEntry> cachedEntries(ProjectModel model) {
        if (model == null) {
            return Map.of();
        }
        try {
            var entries = com.jsrc.app.index.CodebaseIndex.loadPublished(model.root());
            Map<Path, com.jsrc.app.index.IndexEntry> byPath = new HashMap<>();
            for (var entry : entries) {
                byPath.put(model.root().resolve(entry.path()).toAbsolutePath().normalize(),
                        entry);
            }
            return byPath;
        } catch (IOException exception) {
            return Map.of();
        }
    }

    private static boolean isVarLambdaParserLimitation(
            int version, String source, com.github.javaparser.ParseResult<?> result) {
        return version >= 11
                && VAR_LAMBDA.matcher(source).find()
                && result.getProblems().stream().anyMatch(problem ->
                        problem.getMessage().contains("\"var\" is not allowed here"));
    }

    /**
     * Tree-sitter propagates error costs upward, so the root reports ERROR and MISSING
     * nodes anywhere in the tree without walking it node by node.
     */
    static boolean hasSyntaxErrors(io.github.treesitter.jtreesitter.Tree tree) {
        return tree.getRootNode().hasError();
    }

    private static JavaParser parser(int version) {
        var languageLevel = ParserConfiguration.LanguageLevel.valueOf(
                "JAVA_" + (version == 22 ? 21 : version));
        return new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel));
    }
}
