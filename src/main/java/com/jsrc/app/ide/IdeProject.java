package com.jsrc.app.ide;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.CallGraphBuilder;
import com.jsrc.app.engine.CallersResult;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.project.ProjectFileDiscovery;
import com.jsrc.app.project.ProjectModelDetector;
import com.jsrc.app.project.SourceLevel;
import com.jsrc.app.util.SignatureUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Local, read-only project snapshot for an in-process IDE prototype.
 * Uses the same engine ports and result models as the CLI.
 */
public final class IdeProject implements JsrcEngine.SearchSource,
        JsrcEngine.CallersSource, JsrcEngine.ImpactSource {
    private final Map<String, JsrcEngine.SearchDocument> documents;
    private final Map<String, String> sourceTexts;
    private final Map<String, Path> classFiles;
    private final List<ClassInfo> classes;
    private final CallGraph graph;
    private final Map<String, String> signatures;
    private final Map<String, String> classPackages;

    private IdeProject(Map<String, JsrcEngine.SearchDocument> documents,
                       Map<String, String> sourceTexts,
                       Map<String, Path> classFiles, List<ClassInfo> classes,
                       CallGraph graph, Map<String, String> signatures,
                       Map<String, String> classPackages) {
        this.documents = Map.copyOf(documents);
        this.sourceTexts = Map.copyOf(sourceTexts);
        this.classFiles = Map.copyOf(classFiles);
        this.classes = List.copyOf(classes);
        this.graph = graph;
        this.signatures = Map.copyOf(signatures);
        this.classPackages = Map.copyOf(classPackages);
    }

    /** Opens a filesystem snapshot without starting the CLI or a remote service. */
    public static IdeProject open(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Project directory not found: " + root);
        }
        var model = new ProjectModelDetector().detect(root);
        List<Path> files = new ProjectFileDiscovery().discover(model);
        var sourceLevels = SourceLevel.resolveFiles(files, model, null);
        var parser = new HybridJavaParser(sourceLevels);
        var documents = new LinkedHashMap<String, JsrcEngine.SearchDocument>();
        var sourceTexts = new LinkedHashMap<String, String>();
        var classFiles = new LinkedHashMap<String, Path>();
        var allClasses = new ArrayList<ClassInfo>();
        var signatures = new LinkedHashMap<String, String>();
        var classPackages = new LinkedHashMap<String, String>();
        for (Path file : files) {
            try {
                String sourceText = Files.readString(file);
                List<String> lines = sourceText.lines().toList();
                List<ClassInfo> types = parser.parseClasses(file);
                documents.put(file.toString(),
                        new JsrcEngine.SearchDocument(file.toString(), lines, types));
                sourceTexts.put(file.toString(), sourceText);
                allClasses.addAll(types);
                for (ClassInfo type : types) {
                    classFiles.put(type.qualifiedName(), file);
                    classPackages.put(type.qualifiedName(), type.packageName());
                    classPackages.putIfAbsent(type.name(), type.packageName());
                    for (var method : type.methods()) {
                        String parameters = SignatureUtils.extractParams(method.signature());
                        String qualifiedKey = type.qualifiedName() + "." + method.name();
                        String simpleKey = type.name() + "." + method.name();
                        signatures.putIfAbsent(qualifiedKey, parameters);
                        signatures.put(qualifiedKey + "/" + method.parameters().size(), parameters);
                        signatures.putIfAbsent(simpleKey, parameters);
                        signatures.putIfAbsent(
                                simpleKey + "/" + method.parameters().size(), parameters);
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read Java source: " + file, exception);
            }
        }
        var builder = new CallGraphBuilder();
        builder.build(files, sourceLevels);
        return new IdeProject(documents, sourceTexts, classFiles, allClasses,
                builder.toCallGraph(),
                signatures, classPackages);
    }

    @Override
    public List<String> paths() {
        return documents.keySet().stream().sorted().toList();
    }

    @Override
    public Optional<JsrcEngine.SearchDocument> document(String path) {
        return Optional.ofNullable(documents.get(path));
    }

    /** Returns source content from the same snapshot used for search and navigation. */
    public Optional<String> sourceText(Path file) {
        return Optional.ofNullable(sourceTexts.get(file.toString()));
    }

    @Override
    public CallGraph callGraph() {
        return graph;
    }

    @Override
    public Map<String, String> signatures() {
        return signatures;
    }

    @Override
    public Map<String, String> classPackages() {
        return classPackages;
    }

    @Override
    public String qualify(String className) {
        if (className.contains(".")) return className;
        String result = null;
        for (ClassInfo type : classes) {
            if (type.name().equals(className)) {
                if (result != null) return className;
                result = type.qualifiedName();
            }
        }
        return result == null ? className : result;
    }

    @Override
    public List<CallersResult.ReflectiveCaller> reflectiveCallers(String methodName) {
        return List.of();
    }

    @Override
    public List<ClassInfo> classes() {
        return classes;
    }

    @Override
    public long textUsages(String methodName) {
        return documents.values().stream()
                .flatMap(document -> document.lines().stream())
                .filter(line -> line.contains(methodName))
                .count();
    }

    /** Returns a source file for a qualified class name or an unambiguous simple name. */
    public Optional<Path> fileForClass(String className) {
        Path exact = classFiles.get(className);
        if (exact != null) return Optional.of(exact);
        String qualified = qualify(className);
        return Optional.ofNullable(classFiles.get(qualified));
    }
}
