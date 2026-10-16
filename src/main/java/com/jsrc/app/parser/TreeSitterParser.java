package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodInfo.ParameterInfo;
import com.jsrc.app.util.SourceText;

import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.Tree;

/**
 * {@link CodeParser} implementation backed by Tree-sitter for fast,
 * syntax-level discovery. Produces {@link MethodInfo#basic} results
 * without semantic fields (annotations, throws, javadoc).
 * <p>
 * Every {@link Node} accessor is a native downcall, and in a GraalVM native image each
 * downcall costs microseconds instead of nanoseconds, so this class keeps node accesses
 * to the ones its results need.
 */
public class TreeSitterParser implements CodeParser {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterParser.class);

    private static final String ALL_METHODS_QUERY =
            "(method_declaration name: (identifier) @methodName)";
    private static final String ALL_CLASSES_QUERY =
            "[(class_declaration name: (identifier) @className)" +
            " (interface_declaration name: (identifier) @className)" +
            " (enum_declaration name: (identifier) @className)" +
            " (record_declaration name: (identifier) @className)]";

    private final Parser parser;
    private final Language language;
    private final java.util.Set<String> skippedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final String languageKey;
    // Compiled on first use and reused: compiling a query costs about as much as parsing a file.
    private Query allMethodsQuery;
    private Query allClassesQuery;

    @SuppressWarnings("null")
    public TreeSitterParser(String languageKey) {
        if (languageKey == null || languageKey.isBlank()) {
            throw new IllegalArgumentException("Language key must not be null or blank");
        }
        this.languageKey = languageKey.toLowerCase();
        this.language = TreeSitterLanguageFactory.getLanguage(this.languageKey);
        this.parser = new Parser();
        this.parser.setLanguage(language);
    }

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName) {
        if (!isValidInput(path, methodName)) return Collections.emptyList();
        return executeMethodQuery(path, methodName);
    }

    @Override
    public List<MethodInfo> findMethods(Path path, String methodName, List<String> parameterTypes) {
        List<MethodInfo> allMatches = findMethods(path, methodName);
        if (parameterTypes == null) return allMatches;
        return allMatches.stream()
                .filter(m -> parameterTypesMatch(m.parameters(), parameterTypes))
                .toList();
    }

    @Override
    public List<MethodInfo> findAllMethods(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();
        return executeMethodQuery(path, null);
    }

    @Override
    public List<ClassInfo> parseClasses(Path path) {
        if (!isValidPath(path)) return Collections.emptyList();
        return executeClassQuery(path);
    }

    @Override
    public List<ClassInfo> parseClasses(Path path, String source) {
        return executeClassQuery(path, source);
    }

    @Override
    public List<MethodInfo> findMethodsByAnnotation(Path path, String annotationName) {
        // Tree-sitter can't reliably parse annotations; return empty.
        // Callers needing annotation search should use HybridJavaParser.
        logger.debug("findMethodsByAnnotation not supported by TreeSitterParser, returning empty");
        return Collections.emptyList();
    }

    @Override
    public List<CodeSmell> detectSmells(Path path) {
        logger.debug("detectSmells not supported by TreeSitterParser, returning empty");
        return Collections.emptyList();
    }

    @Override
    public String getLanguage() {
        return languageKey;
    }

    // -- internal: method queries --

    /**
     * Builds the methods declared in a file, or only those named {@code expectedName}
     * when it is not null.
     */
    @SuppressWarnings("null")
    private List<MethodInfo> executeMethodQuery(Path path, String expectedName) {
        List<MethodInfo> results = new ArrayList<>();
        try {
            SourceFile source = readSource(path);
            // A declaration named expectedName contains that text, so files without it cannot match.
            if (expectedName != null && !source.content().contains(expectedName)) return results;
            try (ParsedFile pf = parse(source);
                 QueryCursor cursor = new QueryCursor(allMethodsQuery())) {
                cursor.findMatches(pf.tree.getRootNode()).forEach(match -> {
                    for (Node nameNode : match.findNodes("methodName")) {
                        String foundName = nameNode.getText();
                        if (expectedName != null && !foundName.equals(expectedName)) continue;
                        Node methodNode = nameNode.getParent().orElse(null);
                        if (methodNode == null) continue;
                        MethodInfo info = buildMethodInfo(methodNode, pf);
                        if (info != null) results.add(info);
                    }
                });
            }
        } catch (IOException ex) {
            logger.debug("Error reading file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        } catch (Exception ex) {
            logger.debug("Unexpected error parsing file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        }
        return results;
    }

    private List<ClassInfo> executeClassQuery(Path path) {
        try (ParsedFile pf = parse(readSource(path))) {
            return executeClassQuery(pf);
        } catch (IOException ex) {
            logger.debug("Error reading file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        } catch (Exception ex) {
            logger.debug("Unexpected error parsing file {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
        }
        return Collections.emptyList();
    }

    private List<ClassInfo> executeClassQuery(Path path, String source) {
        try (ParsedFile pf = parse(new SourceFile(
                source.getBytes(StandardCharsets.UTF_8), source, source.lines().toList()))) {
            return executeClassQuery(pf);
        } catch (Exception ex) {
            logger.debug("Unexpected error parsing source {}: {}", path, ex.getMessage());
            skippedFiles.add(path.toString());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("null")
    private List<ClassInfo> executeClassQuery(ParsedFile pf) {
        List<ClassInfo> results = new ArrayList<>();
        try (QueryCursor cursor = new QueryCursor(allClassesQuery())) {
            cursor.findMatches(pf.tree.getRootNode()).forEach(match -> {
                for (Node nameNode : match.findNodes("className")) {
                    Node classNode = nameNode.getParent().orElse(null);
                    if (classNode == null) continue;
                    ClassInfo ci = buildClassInfo(classNode, pf);
                    if (ci != null) results.add(ci);
                }
            });
        }
        return results;
    }

    // -- building models --

    private MethodInfo buildMethodInfo(Node methodNode, ParsedFile pf) {
        int startLine = methodNode.getStartPoint().row() + 1;
        int endLine = methodNode.getEndPoint().row() + 1;

        String name = extractChildText(methodNode, "name");
        String returnType = extractChildText(methodNode, "type");
        if (returnType.isEmpty()) returnType = "void";
        List<String> modifiers = extractModifiers(methodNode, pf.bytes);
        List<ParameterInfo> parameters = extractParameters(methodNode);
        String className = extractEnclosingClassName(methodNode);
        String content = extractContent(pf.lines, startLine, endLine);

        return MethodInfo.basic(name, className, startLine, endLine,
                returnType, modifiers, parameters, content);
    }

    private ClassInfo buildClassInfo(Node classNode, ParsedFile pf) {
        String name = extractChildText(classNode, "name");
        int startLine = classNode.getStartPoint().row() + 1;
        int endLine = classNode.getEndPoint().row() + 1;
        List<String> modifiers = extractModifiers(classNode, pf.bytes);

        Node bodyNode = classNode.getChildByFieldName("body").orElse(null);
        List<MethodInfo> methods = new ArrayList<>();
        if (bodyNode != null) {
            // One cursor walk: indexed getChild calls cost several downcalls per child.
            for (Node child : bodyNode.getChildren()) {
                if ("method_declaration".equals(child.getType())) {
                    MethodInfo mi = buildMethodInfo(child, pf);
                    if (mi != null) methods.add(mi);
                }
            }
        }

        String packageName = pf.packageName();
        boolean isInterface = "interface_declaration".equals(classNode.getType());
        return new ClassInfo(name, packageName, startLine, endLine,
                modifiers, methods, "", List.of(), List.of(), isInterface);
    }

    // -- node helpers --

    @SuppressWarnings("null")
    private String extractChildText(Node parent, String fieldName) {
        return parent.getChildByFieldName(fieldName)
                .map(Node::getText)
                .orElse("");
    }

    private List<String> extractModifiers(Node node, byte[] fileBytes) {
        List<String> mods = new ArrayList<>();
        // Modifiers usually come first, so indexed access beats walking every child.
        for (int i = 0, count = node.getChildCount(); i < count; i++) {
            Node child = node.getChild(i).orElse(null);
            if (child != null && "modifiers".equals(child.getType())) {
                String modText = substringFromBytes(fileBytes,
                        child.getStartByte(), child.getEndByte()).trim();
                for (String mod : modText.split("\\s+")) {
                    if (!mod.isBlank() && !mod.startsWith("@")) {
                        mods.add(mod);
                    }
                }
                break;
            }
        }
        return mods;
    }

    private List<ParameterInfo> extractParameters(Node methodNode) {
        List<ParameterInfo> params = new ArrayList<>();
        Node paramsNode = methodNode.getChildByFieldName("parameters").orElse(null);
        if (paramsNode == null) return params;

        for (Node child : paramsNode.getChildren()) {
            String kind = child.getType();
            if ("formal_parameter".equals(kind) || "spread_parameter".equals(kind)) {
                String type = child.getChildByFieldName("type").map(Node::getText).orElse("?");
                String pName = child.getChildByFieldName("name").map(Node::getText).orElse("?");
                params.add(new ParameterInfo(type, pName));
            }
        }
        return params;
    }

    private String extractEnclosingClassName(Node node) {
        Node current = node.getParent().orElse(null);
        while (current != null) {
            if (isTypeDeclaration(current)) {
                return current.getChildByFieldName("name")
                        .map(Node::getText).orElse("");
            }
            current = current.getParent().orElse(null);
        }
        return "";
    }

    private static boolean isTypeDeclaration(Node node) {
        return switch (node.getType()) {
            case "class_declaration", "interface_declaration",
                 "enum_declaration", "record_declaration" -> true;
            default -> false;
        };
    }

    private static String extractPackageName(Node root) {
        for (int i = 0, count = root.getChildCount(); i < count; i++) {
            Node child = root.getChild(i).orElse(null);
            if (child != null && "package_declaration".equals(child.getType())) {
                for (int j = 0, subCount = child.getChildCount(); j < subCount; j++) {
                    Node sub = child.getChild(j).orElse(null);
                    if (sub != null && "scoped_identifier".equals(sub.getType())) {
                        return sub.getText();
                    }
                }
            }
        }
        return "";
    }

    private String extractContent(List<String> lines, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < Math.min(endLine, lines.size()); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String substringFromBytes(byte[] bytes, int startByte, int endByte) {
        int safeStart = Math.max(0, Math.min(startByte, bytes.length));
        int safeEnd = Math.max(safeStart, Math.min(endByte, bytes.length));
        return new String(bytes, safeStart, safeEnd - safeStart, StandardCharsets.UTF_8);
    }

    // -- validation & I/O --

    private boolean isValidPath(Path path) {
        if (path == null || !Files.exists(path)) {
            logger.warn("File does not exist or is null: {}", path);
            return false;
        }
        return true;
    }

    private boolean isValidInput(Path path, String methodName) {
        if (!isValidPath(path)) return false;
        if (methodName == null || methodName.isBlank()) {
            logger.warn("Method name is null or blank");
            return false;
        }
        return true;
    }

    /** Reads and decodes a file once. */
    private SourceFile readSource(Path path) throws IOException {
        String content = SourceText.read(path);
        // Node offsets index the UTF-8 text Tree-sitter parsed, not the file's own encoding.
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        return new SourceFile(contentBytes, content, content.lines().toList());
    }

    private ParsedFile parse(SourceFile source) {
        Tree tree = parser.parse(source.content(), InputEncoding.UTF_8).orElseThrow();
        return new ParsedFile(source.bytes(), source.lines(), tree);
    }

    private Query allMethodsQuery() {
        if (allMethodsQuery == null) allMethodsQuery = new Query(language, ALL_METHODS_QUERY);
        return allMethodsQuery;
    }

    private Query allClassesQuery() {
        if (allClassesQuery == null) allClassesQuery = new Query(language, ALL_CLASSES_QUERY);
        return allClassesQuery;
    }

    private boolean parameterTypesMatch(List<ParameterInfo> actual, List<String> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).type().equals(expected.get(i))) return false;
        }
        return true;
    }

    private record SourceFile(byte[] bytes, String content, List<String> lines) {}

    /** A parsed file; closing it frees the native tree, which is not garbage collected. */
    private static final class ParsedFile implements AutoCloseable {
        private final byte[] bytes;
        private final List<String> lines;
        private final Tree tree;
        private String packageName;

        ParsedFile(byte[] bytes, List<String> lines, Tree tree) {
            this.bytes = bytes;
            this.lines = lines;
            this.tree = tree;
        }

        /** The package of every type in the file, read once from the tree root. */
        String packageName() {
            if (packageName == null) packageName = extractPackageName(tree.getRootNode());
            return packageName;
        }

        @Override
        public void close() {
            tree.close();
        }
    }
}