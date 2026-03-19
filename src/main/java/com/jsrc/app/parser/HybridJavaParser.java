package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.jsrc.app.analysis.CodeSmellDetector;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;

/**
 * Hybrid parser combining Tree-sitter's speed with JavaParser's semantic depth.
 * <p>
 * Strategy:
 * <ul>
 *   <li>For targeted searches ({@code findMethods}): Tree-sitter quickly identifies
 *       which lines contain matches, then JavaParser does deep analysis of the file
 *       using those locations as a filter — reading the file only once.</li>
 *   <li>For full-file operations ({@code parseClasses}, {@code findAllMethods}):
 *       JavaParser handles it directly since there's nothing to filter.</li>
 *   <li>Fallback: if JavaParser fails (syntax errors), Tree-sitter results are used.</li>
 * </ul>
 */
public class HybridJavaParser implements CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(HybridJavaParser.class);

    private final TreeSitterParser treeSitter;
    private final JavaParser javaParser;
    private final CodeSmellDetector smellDetector;
    private final java.util.Set<String> skippedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public HybridJavaParser() {
        this.treeSitter = new TreeSitterParser("java");
        var config = new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
        this.javaParser = new JavaParser(config);
        this.smellDetector = new CodeSmellDetector();
    }

    @Override
    public String getLanguage() {
        return "java";
    }

    @Override
    public java.util.Set<String> getSkippedFiles() {
        return java.util.Collections.unmodifiableSet(skippedFiles);
    }

    // ---- targeted search: TreeSitter locates, JavaParser enriches ----

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName) {
        if (!isValidInput(path, methodName)) return Collections.emptyList();

        List<MethodInfo> tsLocations = treeSitter.findMethods(path, methodName);
        if (tsLocations.isEmpty()) return Collections.emptyList();

        Set<Integer> targetLines = tsLocations.stream()
                .map(MethodInfo::startLine)
                .collect(Collectors.toSet());

        logger.debug("TreeSitter located '{}' at lines {} in {}, enriching with JavaParser",
                methodName, targetLines, path.getFileName());

        CompilationUnit cu = parseWithJavaParserStrict(path);
        if (cu == null) return tsLocations;

        List<MethodInfo> enriched = cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getNameAsString().equals(methodName))
                .filter(md -> md.getBegin().isPresent())
                .filter(md -> isNearAnyTargetLine(md, targetLines))
                .map(md -> toRichMethodInfo(md, findSourceContent(tsLocations, md)))
                .toList();

        return enriched.isEmpty() ? tsLocations : enriched;
    }

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName, List<String> parameterTypes) {
        List<MethodInfo> allMatches = findMethods(path, methodName);
        if (parameterTypes == null) return allMatches;
        return allMatches.stream()
                .filter(m -> parameterTypesMatch(m.parameters(), parameterTypes))
                .toList();
    }

    // ---- full-file operations: JavaParser directly ----

    @Override
    public List<MethodInfo> findAllMethods(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();

        CompilationUnit cu = parseWithJavaParserStrict(path);
        if (cu == null) return treeSitter.findAllMethods(path);

        return cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getBegin().isPresent())
                .map(md -> toRichMethodInfo(md, null))
                .toList();
    }

    @Override
    public List<ClassInfo> parseClasses(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();

        CompilationUnit cu = parseWithJavaParser(path);
        if (cu == null) return treeSitter.parseClasses(path);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        var classes = new java.util.ArrayList<com.jsrc.app.parser.model.ClassInfo>();
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(cid -> cid.getBegin().isPresent())
                .map(cid -> toClassInfo(cid, packageName))
                .forEach(classes::add);

        // Also handle records (Java 16+)
        cu.findAll(com.github.javaparser.ast.body.RecordDeclaration.class).stream()
                .filter(rd -> rd.getBegin().isPresent())
                .map(rd -> recordToClassInfo(rd, packageName))
                .forEach(classes::add);

        // Also handle enums
        cu.findAll(com.github.javaparser.ast.body.EnumDeclaration.class).stream()
                .filter(ed -> ed.getBegin().isPresent())
                .map(ed -> enumToClassInfo(ed, packageName))
                .forEach(classes::add);

        return classes;
    }

    private ClassInfo recordToClassInfo(com.github.javaparser.ast.body.RecordDeclaration rd,
                                         String packageName) {
        String name = rd.getNameAsString();
        int startLine = rd.getBegin().map(p -> p.line).orElse(0);
        int endLine = rd.getEnd().map(p -> p.line).orElse(0);
        List<String> modifiers = rd.getModifiers().stream()
                .map(mod -> mod.getKeyword().asString()).toList();
        List<MethodInfo> methods = rd.getMethods().stream()
                .map(md -> toRichMethodInfo(md, null)).toList();
        List<AnnotationInfo> annotations = rd.getAnnotations().stream()
                .map(this::toAnnotationInfo).toList();
        List<String> interfaces = rd.getImplementedTypes().stream()
                .map(t -> t.asString()).toList();
        List<com.jsrc.app.parser.model.FieldInfo> fields = rd.getParameters().stream()
                .map(p -> new com.jsrc.app.parser.model.FieldInfo(p.getNameAsString(), p.getTypeAsString()))
                .toList();

        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, "", interfaces, annotations, false, fields);
    }

    private ClassInfo enumToClassInfo(com.github.javaparser.ast.body.EnumDeclaration ed,
                                       String packageName) {
        String name = ed.getNameAsString();
        int startLine = ed.getBegin().map(p -> p.line).orElse(0);
        int endLine = ed.getEnd().map(p -> p.line).orElse(0);
        List<String> modifiers = ed.getModifiers().stream()
                .map(mod -> mod.getKeyword().asString()).toList();
        var methods = new java.util.ArrayList<MethodInfo>();
        ed.getMethods().stream().map(md -> toRichMethodInfo(md, null)).forEach(methods::add);
        ed.getConstructors().forEach(cd -> {
            int ctorStart = cd.getBegin().map(p -> p.line).orElse(0);
            int ctorEnd = cd.getEnd().map(p -> p.line).orElse(0);
            String ctorSig = cd.getDeclarationAsString(true, true, true);
            List<MethodInfo.ParameterInfo> ctorParams = cd.getParameters().stream()
                    .map(this::toParameterInfo).toList();
            methods.add(new MethodInfo(name, name, ctorStart, ctorEnd, "",
                    List.of(), ctorParams, cd.toString(), List.of(), List.of(), List.of(), null));
        });
        List<AnnotationInfo> annotations = ed.getAnnotations().stream()
                .map(this::toAnnotationInfo).toList();
        List<String> interfaces = ed.getImplementedTypes().stream()
                .map(t -> t.asString()).toList();
        List<com.jsrc.app.parser.model.FieldInfo> fields = ed.getFields().stream()
                .flatMap(fd -> {
                    String fieldType = fd.getCommonType().asString();
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    final String ft = fieldType;
                    return fd.getVariables().stream()
                            .map(v -> new com.jsrc.app.parser.model.FieldInfo(v.getNameAsString(), ft));
                }).toList();

        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, "", interfaces, annotations, false, fields);
    }

    @Override
    public List<MethodInfo> findMethodsByAnnotation(Path path, String annotationName) {
        if (!isValidPath(path) || annotationName == null || annotationName.isBlank()) {
            return Collections.emptyList();
        }

        CompilationUnit cu = parseWithJavaParserStrict(path);
        if (cu == null) return Collections.emptyList();

        return cu.findAll(MethodDeclaration.class).stream()
                .filter(md -> md.getBegin().isPresent())
                .filter(md -> md.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(annotationName)))
                .map(md -> toRichMethodInfo(md, null))
                .toList();
    }

    // ---- code smell detection ----

    @Override
    public List<CodeSmell> detectSmells(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();

        CompilationUnit cu = parseWithJavaParserStrict(path);
        if (cu == null) return Collections.emptyList();

        return smellDetector.analyzeFile(cu);
    }

    // ---- JavaParser model extraction ----

    private MethodInfo toRichMethodInfo(MethodDeclaration md, String fallbackContent) {
        String name = md.getNameAsString();
        int startLine = md.getBegin().map(p -> p.line).orElse(-1);
        int endLine = md.getEnd().map(p -> p.line).orElse(-1);

        String className = findEnclosingClassName(md);

        String returnType = md.getTypeAsString();

        List<String> modifiers = md.getModifiers().stream()
                .map(mod -> mod.getKeyword().asString())
                .toList();

        List<ParameterInfo> parameters = md.getParameters().stream()
                .map(this::toParameterInfo)
                .toList();

        String content = fallbackContent != null ? fallbackContent : md.toString();

        List<AnnotationInfo> annotations = md.getAnnotations().stream()
                .map(this::toAnnotationInfo)
                .toList();

        List<String> thrownExceptions = md.getThrownExceptions().stream()
                .map(t -> t.asString())
                .toList();

        List<String> typeParameters = md.getTypeParameters().stream()
                .map(tp -> tp.asString())
                .toList();

        String javadoc = md.getJavadocComment()
                .map(JavadocComment::getContent)
                .map(String::trim)
                .orElse(null);

        return new MethodInfo(name, className, startLine, endLine,
                returnType, modifiers, parameters, content,
                annotations, thrownExceptions, typeParameters, javadoc);
    }

    private ClassInfo toClassInfo(ClassOrInterfaceDeclaration cid, String packageName) {
        String name = cid.getNameAsString();
        int startLine = cid.getBegin().map(p -> p.line).orElse(-1);
        int endLine = cid.getEnd().map(p -> p.line).orElse(-1);

        List<String> modifiers = cid.getModifiers().stream()
                .map(mod -> mod.getKeyword().asString())
                .toList();

        var methods = new java.util.ArrayList<MethodInfo>();
        cid.getMethods().stream()
                .map(md -> toRichMethodInfo(md, null))
                .forEach(methods::add);
        // Include constructors as methods (for dependency analysis and call graph)
        cid.getConstructors().forEach(cd -> {
            int ctorStart = cd.getBegin().map(p -> p.line).orElse(0);
            int ctorEnd = cd.getEnd().map(p -> p.line).orElse(0);
            String ctorSig = cd.getDeclarationAsString(true, true, true);
            List<MethodInfo.ParameterInfo> ctorParams = cd.getParameters().stream()
                    .map(this::toParameterInfo).toList();
            methods.add(new MethodInfo(cid.getNameAsString(), cid.getNameAsString(),
                    ctorStart, ctorEnd, "", List.of("public"), ctorParams,
                    cd.toString(), List.of(), List.of(), List.of(), null));
        });

        String superClass = cid.getExtendedTypes().stream()
                .findFirst()
                .map(t -> t.asString())
                .orElse("");

        List<String> interfaces = cid.getImplementedTypes().stream()
                .map(t -> t.asString())
                .toList();

        List<AnnotationInfo> annotations = cid.getAnnotations().stream()
                .map(this::toAnnotationInfo)
                .toList();

        boolean isInterface = cid.isInterface();

        List<com.jsrc.app.parser.model.FieldInfo> fields = cid.getFields().stream()
                .flatMap(fd -> {
                    String fieldType = fd.getCommonType().asString();
                    int genIdx = fieldType.indexOf('<');
                    if (genIdx > 0) fieldType = fieldType.substring(0, genIdx);
                    final String ft = fieldType;
                    return fd.getVariables().stream()
                            .map(v -> new com.jsrc.app.parser.model.FieldInfo(v.getNameAsString(), ft));
                })
                .toList();

        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, superClass, interfaces, annotations, isInterface, fields);
    }

    private AnnotationInfo toAnnotationInfo(AnnotationExpr ae) {
        String name = ae.getNameAsString();

        if (ae instanceof NormalAnnotationExpr nae) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (MemberValuePair pair : nae.getPairs()) {
                attrs.put(pair.getNameAsString(), pair.getValue().toString());
            }
            return new AnnotationInfo(name, attrs);
        }

        if (ae instanceof SingleMemberAnnotationExpr sae) {
            return new AnnotationInfo(name, Map.of("value", sae.getMemberValue().toString()));
        }

        return AnnotationInfo.marker(name);
    }

    private ParameterInfo toParameterInfo(Parameter param) {
        return new ParameterInfo(param.getTypeAsString(), param.getNameAsString());
    }

    // ---- helpers ----

    /**
     * Parses with strict mode — only returns CU if parse was fully successful.
     * Use for operations that need full AST fidelity (findMethods, detectSmells).
     */
    private CompilationUnit parseWithJavaParserStrict(Path path) {
        try {
            String source = Files.readString(path);
            var result = javaParser.parse(source);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult().get();
            }
            logger.warn("JavaParser could not parse {}, falling back to TreeSitter", path.getFileName());
        } catch (IOException ex) {
            logger.debug("Error reading file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        }
        return null;
    }

    /**
     * Parses with lenient mode — returns CU even with parse problems.
     * Use for metadata extraction (parseClasses) where partial info is still useful.
     */
    private CompilationUnit parseWithJavaParser(Path path) {
        try {
            String source = Files.readString(path);
            var result = javaParser.parse(source);
            if (result.getResult().isPresent()) {
                if (!result.isSuccessful()) {
                    logger.debug("Parsed {} with {} problem(s)", path.getFileName(), result.getProblems().size());
                }
                return result.getResult().get();
            }
            logger.warn("JavaParser could not parse {}, falling back to TreeSitter", path.getFileName());
        } catch (IOException ex) {
            logger.debug("Error reading file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        }
        return null;
    }

    private boolean isNearAnyTargetLine(MethodDeclaration md, Set<Integer> targetLines) {
        int jpLine = md.getBegin().map(p -> p.line).orElse(-1);
        if (jpLine < 0) return false;
        return targetLines.stream().anyMatch(tsLine -> Math.abs(jpLine - tsLine) <= 2);
    }

    private String findSourceContent(List<MethodInfo> tsLocations, MethodDeclaration md) {
        int jpLine = md.getBegin().map(p -> p.line).orElse(-1);
        return tsLocations.stream()
                .filter(ts -> Math.abs(ts.startLine() - jpLine) <= 2)
                .map(MethodInfo::content)
                .findFirst()
                .orElse(null);
    }

    private String findEnclosingClassName(MethodDeclaration md) {
        Node current = md.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ClassOrInterfaceDeclaration cid) {
                return cid.getNameAsString();
            }
            current = current.getParentNode().orElse(null);
        }
        return "";
    }

    private boolean isValidPath(Path path) {
        return path != null && Files.exists(path);
    }

    private boolean isValidInput(Path path, String methodName) {
        return isValidPath(path) && methodName != null && !methodName.isBlank();
    }

    private boolean parameterTypesMatch(List<ParameterInfo> actual, List<String> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).type().equals(expected.get(i))) return false;
        }
        return true;
    }
}
