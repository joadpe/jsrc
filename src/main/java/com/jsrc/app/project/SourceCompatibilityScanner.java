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

    public record Result(
            List<Path> files, List<SourceDiagnostic> diagnostics, int parsedFiles) {
        public Result(List<Path> files, List<SourceDiagnostic> diagnostics) {
            this(files, diagnostics, 0);
        }

        public Result {
            files = List.copyOf(files);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public Result scan(List<Path> files, ProjectModel model, ProjectConfig config) {
        if (files.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        List<Path> accepted = new ArrayList<>();
        List<SourceDiagnostic> diagnostics = new ArrayList<>();
        Map<Integer, JavaParser> parsers = new HashMap<>();
        Map<Path, com.jsrc.app.index.IndexEntry> cached = cachedEntries(model);
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
                if (version < 8 || version > 21) {
                    diagnostics.add(new SourceDiagnostic(
                            "SOURCE_LEVEL_UNSUPPORTED", file,
                            "Java source level " + version + " is outside supported range 8..21"));
                    continue;
                }
                try {
                    String source = Files.readString(file);
                    var entry = cached.get(file.toAbsolutePath().normalize());
                    int sourceVersion = level.map(SourceLevel::version).orElse(0);
                    if (entry != null && entry.sourceVersion() == sourceVersion
                            && entry.contentHash().equals(com.jsrc.app.util.Hashing.sha256(
                                    source.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
                        accepted.add(file);
                        continue;
                    }
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
                        if (hasTreeError(tree.getRootNode())) {
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
                } catch (IOException exception) {
                    diagnostics.add(new SourceDiagnostic(
                            "PARSE_PARTIAL", file,
                            "Could not read source: " + exception.getMessage()));
                }
            }
        }
        return new Result(accepted, diagnostics, parsedFiles);
    }

    private static Map<Path, com.jsrc.app.index.IndexEntry> cachedEntries(ProjectModel model) {
        if (model == null) {
            return Map.of();
        }
        Path indexFile = model.root().resolve(".jsrc/index.bin");
        if (!Files.isRegularFile(indexFile)) {
            return Map.of();
        }
        try {
            var entries = com.jsrc.app.index.BinaryIndexV2Reader.readLazy(indexFile)
                    .getData().entries();
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

    private static boolean hasTreeError(io.github.treesitter.jtreesitter.Node node) {
        if (node.isError() || node.isMissing() || node.hasError()) {
            return true;
        }
        for (var child : node.getChildren()) {
            if (hasTreeError(child)) {
                return true;
            }
        }
        return false;
    }

    private static JavaParser parser(int version) {
        var languageLevel = ParserConfiguration.LanguageLevel.valueOf(
                "JAVA_" + version);
        return new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel));
    }
}
