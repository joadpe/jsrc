package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.jsrc.app.output.DependencyResult;
import com.jsrc.app.output.DependencyResult.FieldDep;

/**
 * Analyzes class dependencies using JavaParser.
 * Extracts imports, field types, and constructor parameter types.
 */
public class DependencyAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(DependencyAnalyzer.class);

    private final JavaParser javaParser;

    public DependencyAnalyzer() {
        this.javaParser = new JavaParser();
    }

    /**
     * Analyzes dependencies for a specific class across the given files.
     *
     * @param files     Java source files to search
     * @param className simple or qualified class name
     * @return dependency result, or null if class not found
     */
    public DependencyResult analyze(List<Path> files, String className) {
        for (Path file : files) {
            DependencyResult result = analyzeFile(file, className);
            if (result != null) return result;
        }
        return null;
    }

    private DependencyResult analyzeFile(Path file, String className) {
        try {
            String source = Files.readString(file);
            var parseResult = javaParser.parse(source);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) return null;
            CompilationUnit cu = parseResult.getResult().get();

            for (ClassOrInterfaceDeclaration cid : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cid.getNameAsString().equals(className)) continue;

                List<String> imports = cu.getImports().stream()
                        .map(imp -> imp.getNameAsString())
                        .toList();

                List<FieldDep> fieldDeps = cid.getFields().stream()
                        .flatMap(f -> f.getVariables().stream()
                                .map(v -> new FieldDep(f.getCommonType().asString(), v.getNameAsString())))
                        .toList();

                List<FieldDep> ctorDeps = cid.getConstructors().stream()
                        .flatMap(c -> c.getParameters().stream()
                                .map(p -> new FieldDep(p.getTypeAsString(), p.getNameAsString())))
                        .toList();

                String qualifiedName = cu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString() + "." + className)
                        .orElse(className);

                return new DependencyResult(qualifiedName, imports, fieldDeps, ctorDeps);
            }
        } catch (IOException e) {
            logger.error("Error reading {}: {}", file, e.getMessage());
        }
        return null;
    }
}
