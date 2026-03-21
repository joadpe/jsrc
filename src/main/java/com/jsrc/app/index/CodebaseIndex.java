package com.jsrc.app.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.model.ClassInfo;

import com.github.javaparser.JavaParser;

/**
 * Builds and persists a codebase index for fast lookups.
 * Index is stored as JSON in {@code .jsrc/index.json}.
 * <p>
 * Edge extraction and resolution is delegated to {@link EdgeResolver}.
 */
public class CodebaseIndex {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseIndex.class);
    private static final String INDEX_DIR = ".jsrc";
    private static final String INDEX_FILE = "index.json";

    private final List<IndexEntry> entries;
    private final EdgeResolver edgeResolver;

    public CodebaseIndex() {
        this.entries = new ArrayList<>();
        this.edgeResolver = new EdgeResolver();
    }

    public CodebaseIndex(List<IndexEntry> entries) {
        this.entries = new ArrayList<>(entries);
        this.edgeResolver = new EdgeResolver();
    }

    public List<IndexEntry> getEntries() {
        return entries;
    }

    /**
     * Builds the index by parsing all given files.
     * Skips files whose content hash matches an existing entry (incremental).
     *
     * @param parser    parser to use
     * @param files     files to index
     * @param sourceRoot source root for relative paths
     * @param existing  existing index entries (for incremental update)
     * @return number of files re-indexed
     */
    public int build(CodeParser parser, List<Path> files, Path sourceRoot,
                     List<IndexEntry> existing) {
        return build(parser, files, sourceRoot, existing, List.of());
    }

    /**
     * Builds the index with optional invoker definitions for reflective edge extraction.
     */
    public int build(CodeParser parser, List<Path> files, Path sourceRoot,
                     List<IndexEntry> existing,
                     List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> invokers) {
        Map<String, IndexEntry> existingByPath = new LinkedHashMap<>();
        for (IndexEntry e : existing) {
            existingByPath.put(e.path(), e);
        }

        entries.clear();
        int reindexed = 0;
        var edgeConfig = new com.github.javaparser.ParserConfiguration().setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21); var edgeParser = new JavaParser(edgeConfig); // reused across files

        for (Path file : files) {
            String relativePath = sourceRoot.relativize(file).toString();
            try {
                byte[] content = Files.readAllBytes(file);
                String hash = com.jsrc.app.util.Hashing.sha256(content);
                long lastModified = Files.getLastModifiedTime(file).toMillis();

                IndexEntry prev = existingByPath.get(relativePath);
                if (prev != null && prev.contentHash().equals(hash)) {
                    entries.add(prev);
                    continue;
                }

                // Need to re-index
                List<ClassInfo> classes = parser.parseClasses(file);

                // Extract imports from file for return type resolution
                List<String> fileImports = extractImports(file, edgeParser);

                List<IndexedClass> indexed = classes.stream()
                        .map(ci -> toIndexedClass(ci, file, parser, fileImports))
                        .toList();

                // Extract call edges (direct + reflective) via EdgeResolver
                List<CallEdge> edges = new ArrayList<>(edgeResolver.extractCallEdges(file, edgeParser));
                if (!invokers.isEmpty()) {
                    edges.addAll(edgeResolver.extractReflectiveEdges(file, edgeParser, invokers));
                }

                entries.add(new IndexEntry(relativePath, hash, lastModified, indexed, edges));
                reindexed++;
            } catch (IOException ex) {
                logger.error("Error indexing {}: {}", file, ex.getMessage());
            }
        }

        // Post-build: resolve ?field:/?ret: markers using cross-class type info
        edgeResolver.resolveMarkers(entries);

        return reindexed;
    }

    /**
     * Persists the index to {@code .jsrc/index.json} under the given root.
     */
    public void save(Path projectRoot) throws IOException {
        Path indexDir = projectRoot.resolve(INDEX_DIR);
        Files.createDirectories(indexDir);
        Path indexFile = indexDir.resolve(INDEX_FILE);

        List<Map<String, Object>> serialized = entries.stream()
                .map(this::entryToMap)
                .toList();

        String json = JsonWriter.toJson(serialized);
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);
        logger.info("Index saved: {} entries to {}", entries.size(), indexFile);
    }

    /** Saves a list of entries to disk (used by IndexedCodebase for smell cache persistence). */
    public void saveEntries(Path projectRoot, List<IndexEntry> entriesToSave) throws IOException {
        entries.clear();
        entries.addAll(entriesToSave);
        save(projectRoot);
    }

    /**
     * Loads an existing index from disk.
     *
     * @return list of entries, or empty if no index exists
     */
    @SuppressWarnings("unchecked")
    public static List<IndexEntry> load(Path projectRoot) {
        Path indexFile = projectRoot.resolve(INDEX_DIR).resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            String json = Files.readString(indexFile, java.nio.charset.StandardCharsets.UTF_8);
            Object parsed = com.jsrc.app.output.JsonReader.parse(json);
            if (!(parsed instanceof List<?> rawList)) {
                logger.warn("Index file is not a JSON array: {}", indexFile);
                return List.of();
            }

            List<IndexEntry> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> map) {
                    result.add(mapToEntry((Map<String, Object>) map));
                }
            }
            logger.info("Loaded index: {} entries from {}", result.size(), indexFile);
            return result;
        } catch (IOException e) {
            logger.error("Error reading index {}: {}", indexFile, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.warn("Error parsing index {}: {}", indexFile, e.getMessage());
            return List.of();
        }
    }

    // ---- deserialization ----

    @SuppressWarnings("unchecked")
    private static IndexEntry mapToEntry(Map<String, Object> map) {
        String path = (String) map.getOrDefault("path", "");
        String hash = (String) map.getOrDefault("contentHash", "");
        long lastModified = map.get("lastModified") instanceof Number n ? n.longValue() : 0;

        List<IndexedClass> classes = new ArrayList<>();
        Object classesRaw = map.get("classes");
        if (classesRaw instanceof List<?> classList) {
            for (Object c : classList) {
                if (c instanceof Map<?, ?> cm) {
                    classes.add(mapToIndexedClass((Map<String, Object>) cm));
                }
            }
        }
        List<CallEdge> callEdges = new ArrayList<>();
        Object edgesRaw = map.get("callEdges");
        if (edgesRaw instanceof List<?> edgeList) {
            for (Object e : edgeList) {
                if (e instanceof Map<?, ?> em) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> edgeMap = (Map<String, Object>) em;
                    int callerParamCount = edgeMap.containsKey("callerParamCount") ? intVal(edgeMap, "callerParamCount") : -1;
                    int argCount = edgeMap.containsKey("argCount") ? intVal(edgeMap, "argCount") : -1;
                    callEdges.add(new CallEdge(
                            str(edgeMap, "callerClass"), str(edgeMap, "callerMethod"),
                            callerParamCount,
                            str(edgeMap, "calleeClass"), str(edgeMap, "calleeMethod"),
                            intVal(edgeMap, "line"), argCount));
                }
            }
        }
        // Deserialize cached smells
        List<CachedSmell> smells = new ArrayList<>();
        Object smellsRaw = map.get("smells");
        if (smellsRaw instanceof List<?> smellList) {
            for (Object s : smellList) {
                if (s instanceof Map<?, ?> sm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> smellMap = (Map<String, Object>) sm;
                    smells.add(new CachedSmell(
                            str(smellMap, "r"), str(smellMap, "s"),
                            intVal(smellMap, "l"), str(smellMap, "m"),
                            str(smellMap, "c"), str(smellMap, "msg")));
                }
            }
        }
        return new IndexEntry(path, hash, lastModified, classes, callEdges, smells);
    }

    @SuppressWarnings("unchecked")
    private static IndexedClass mapToIndexedClass(Map<String, Object> map) {
        String name = str(map, "name");
        String pkg = str(map, "packageName");
        int startLine = intVal(map, "startLine");
        int endLine = intVal(map, "endLine");
        boolean isInterface = bool(map, "isInterface");
        boolean isAbstract = bool(map, "isAbstract");
        List<String> superClass = strList(map.get("superClass"));
        List<String> interfaces = strList(map.get("interfaces"));
        List<String> annotations = strList(map.get("annotations"));
        List<String> imports = strList(map.get("imports"));

        List<IndexedMethod> methods = new ArrayList<>();
        Object methodsRaw = map.get("methods");
        if (methodsRaw instanceof List<?> ml) {
            for (Object m : ml) {
                if (m instanceof Map<?, ?> mm) {
                    methods.add(mapToIndexedMethod((Map<String, Object>) mm));
                }
            }
        }

        List<IndexedField> fields = new ArrayList<>();
        Object fieldsRaw = map.get("fields");
        if (fieldsRaw instanceof List<?> fl) {
            for (Object f : fl) {
                if (f instanceof Map<?, ?> fm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fieldMap = (Map<String, Object>) fm;
                    fields.add(new IndexedField(str(fieldMap, "name"), str(fieldMap, "type")));
                }
            }
        }

        return new IndexedClass(name, pkg, startLine, endLine,
                isInterface, isAbstract, superClass, interfaces,
                methods, annotations, imports, fields);
    }

    @SuppressWarnings("unchecked")
    private static IndexedMethod mapToIndexedMethod(Map<String, Object> map) {
        return new IndexedMethod(
                str(map, "name"), str(map, "signature"),
                intVal(map, "startLine"), intVal(map, "endLine"),
                str(map, "returnType"), strList(map.get("annotations")),
                intVal(map, "complexity"), intVal(map, "paramCount"));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : "";
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean b && b;
    }

    private static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    // ---- class/field conversion ----

    private IndexedClass toIndexedClass(ClassInfo ci, Path file, CodeParser parser,
                                        List<String> fileImports) {
        List<IndexedMethod> methods = ci.methods().stream()
                .map(m -> new IndexedMethod(
                        m.name(), m.signature(), m.startLine(), m.endLine(),
                        m.returnType(),
                        m.annotations().stream().map(a -> a.name()).toList()))
                .toList();

        List<String> annotations = ci.annotations().stream()
                .map(a -> a.name()).toList();

        List<IndexedField> fields = ci.fields().stream()
                .map(f -> new IndexedField(f.name(), f.type()))
                .toList();

        return new IndexedClass(
                ci.name(), ci.packageName(), ci.startLine(), ci.endLine(),
                ci.isInterface(), ci.isAbstract(),
                ci.superClass().isEmpty() ? List.of() : List.of(ci.superClass()),
                ci.interfaces(), methods, annotations, fileImports, fields);
    }

    // extractFields removed — fields now come from ClassInfo.fields() via HybridJavaParser

    /**
     * Extracts import statements from a Java file.
     */
    private static List<String> extractImports(Path file, JavaParser jp) {
        try {
            String source = Files.readString(file);
            var result = jp.parse(source);
            if (!result.getResult().isPresent()) return List.of();
            return result.getResult().get().getImports().stream()
                    .map(imp -> imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- serialization ----

    private Map<String, Object> entryToMap(IndexEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", entry.path());
        map.put("contentHash", entry.contentHash());
        map.put("lastModified", entry.lastModified());
        map.put("classes", entry.classes().stream().map(this::classToMap).toList());
        if (!entry.callEdges().isEmpty()) {
            map.put("callEdges", entry.callEdges().stream().map(this::edgeToMap).toList());
        }
        if (!entry.smells().isEmpty()) {
            map.put("smells", entry.smells().stream().map(this::smellToMap).toList());
        }
        return map;
    }

    private Map<String, Object> smellToMap(CachedSmell s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("r", s.ruleId());
        map.put("s", s.severity());
        map.put("l", s.line());
        map.put("m", s.method());
        map.put("c", s.className());
        map.put("msg", s.message());
        return map;
    }

    private Map<String, Object> edgeToMap(CallEdge edge) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("callerClass", edge.callerClass());
        map.put("callerMethod", edge.callerMethod());
        if (edge.callerParamCount() >= 0) {
            map.put("callerParamCount", edge.callerParamCount());
        }
        map.put("calleeClass", edge.calleeClass());
        map.put("calleeMethod", edge.calleeMethod());
        map.put("line", edge.line());
        if (edge.argCount() >= 0) {
            map.put("argCount", edge.argCount());
        }
        return map;
    }

    private Map<String, Object> classToMap(IndexedClass ic) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", ic.name());
        map.put("packageName", ic.packageName());
        map.put("qualifiedName", ic.qualifiedName());
        map.put("line", ic.startLine());
        map.put("endLine", ic.endLine());
        map.put("isInterface", ic.isInterface());
        map.put("isAbstract", ic.isAbstract());
        map.put("superClass", ic.superClass());
        map.put("interfaces", ic.interfaces());
        map.put("methods", ic.methods().stream().map(this::methodToMap).toList());
        map.put("annotations", ic.annotations());
        if (!ic.imports().isEmpty()) {
            map.put("imports", ic.imports());
        }
        if (!ic.fields().isEmpty()) {
            map.put("fields", ic.fields().stream().map(this::fieldToMap).toList());
        }
        return map;
    }

    private Map<String, Object> fieldToMap(IndexedField f) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", f.name());
        map.put("type", f.type());
        return map;
    }

    private Map<String, Object> methodToMap(IndexedMethod im) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", im.name());
        map.put("signature", im.signature());
        map.put("line", im.startLine());
        map.put("endLine", im.endLine());
        map.put("returnType", im.returnType());
        map.put("annotations", im.annotations());
        if (im.complexity() > 0) map.put("complexity", im.complexity());
        if (im.paramCount() > 0) map.put("paramCount", im.paramCount());
        return map;
    }
}
