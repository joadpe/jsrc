package com.jsrc.app.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Provides ClassInfo and MethodInfo from a persisted index,
 * avoiding full source re-parsing. Auto-refreshes stale entries
 * when files have been modified since indexing.
 */
public class IndexedCodebase {

    private static final Logger logger = LoggerFactory.getLogger(IndexedCodebase.class);

    private final List<IndexEntry> entries; // mutable for setCachedSmells/lazy load
    private List<ClassInfo> allClasses;
    private java.nio.file.Path sourceRoot;
    private boolean edgesLoaded = false;
    private boolean smellsLoaded = false;
    private com.jsrc.app.analysis.CallGraph preBuiltCallGraph;
    private BinaryIndexV2Reader.LazyIndexData lazyIndexData; // loaded from V2 binary
    private java.util.Map<String, IndexedClass> classLookup; // lazy O(1) class lookup
    private java.util.Map<String, String> classToPath; // lazy O(1) class→file path
    private java.util.Map<String, List<CachedMigration>> migrationCache; // path → migrations

    private IndexedCodebase(List<IndexEntry> entries) {
        this.entries = entries;
    }

    /**
     * Lazily load call edges from split JSON file (legacy format).
     * Not needed when loaded from V2 binary index.
     */
    public void ensureEdgesLoaded() {
        if (!edgesLoaded && sourceRoot != null) {
            CodebaseIndex.loadEdgesInto(sourceRoot, entries);
            edgesLoaded = true;
        }
    }

    /**
     * Lazily load cached smells from split JSON file (legacy format).
     * Not needed when loaded from V2 binary index.
     */
    public void ensureSmellsLoaded() {
        if (!smellsLoaded && sourceRoot != null) {
            CodebaseIndex.loadSmellsInto(sourceRoot, entries);
            smellsLoaded = true;
        }
    }

    /**
     * Tries to load an indexed codebase from disk.
     * If files have changed since indexing, automatically re-parses
     * only the modified/new files and updates the persisted index.
     *
     * @param sourceRoot project root where .jsrc/index.json lives
     * @param currentFiles current list of Java files on disk
     * @return IndexedCodebase if index exists, null otherwise
     */
    public static IndexedCodebase tryLoad(Path sourceRoot, List<Path> currentFiles) {
        return tryLoad(sourceRoot, currentFiles, false);
    }

    /**
     * Tries to load an indexed codebase from disk with optional frozen mode.
     * 
     * @param sourceRoot project root where .jsrc/index.json lives
     * @param currentFiles current list of Java files on disk
     * @param frozenIndex if true, skip filesystem walk and fail if index missing/corrupt
     * @return IndexedCodebase if index exists, null otherwise
     * @throws com.jsrc.app.exception.JsrcIOException if frozenIndex is true and index is missing or corrupt
     */
    public static IndexedCodebase tryLoad(Path sourceRoot, List<Path> currentFiles, boolean frozenIndex) {
        Path v2File = sourceRoot.resolve(".jsrc/index.bin");
        
        // Frozen mode: load existing index without filesystem walk
        if (frozenIndex) {
            if (!Files.exists(v2File)) {
                throw new com.jsrc.app.exception.JsrcIOException(
                    "--frozen-index set but index file missing: " + v2File + "\n" +
                    "Run 'jsrc index' first or omit --frozen-index to rebuild.");
            }
            
            try {
                BinaryIndexV2Reader.LazyIndexData lazyData = BinaryIndexV2Reader.readLazy(v2File);
                List<IndexEntry> entries = lazyData.getData().entries();
                java.util.Map<String, List<CachedMigration>> loadedMigrations = lazyData.getData().migrations();
                
                logger.info("Loaded V2 binary index in FROZEN mode (LAZY): {} entries", entries.size());
                
                var indexed = new IndexedCodebase(entries);
                indexed.sourceRoot = sourceRoot;
                indexed.edgesLoaded = entries.stream().anyMatch(e -> !e.callEdges().isEmpty());
                indexed.smellsLoaded = entries.stream().anyMatch(e -> !e.smells().isEmpty());
                indexed.preBuiltCallGraph = null; // Keep lazy until ensureGraph
                indexed.lazyIndexData = lazyData;
                indexed.migrationCache = loadedMigrations;
                return indexed;
            } catch (IOException e) {
                throw new com.jsrc.app.exception.JsrcIOException(
                    "--frozen-index set but index file corrupt: " + e.getMessage() + "\n" +
                    "Run 'jsrc index' to rebuild or omit --frozen-index.", e);
            }
        }
        
        // Normal mode: existing refresh logic
        com.jsrc.app.analysis.CallGraph preBuiltGraph = null;
        BinaryIndexV2Reader.LazyIndexData lazyData = null;
        java.util.Map<String, List<CachedMigration>> loadedMigrations = null;
        List<IndexEntry> existing;

        if (Files.exists(v2File)) {
            try {
                lazyData = BinaryIndexV2Reader.readLazy(v2File);
                existing = lazyData.getData().entries();
                loadedMigrations = lazyData.getData().migrations();
                logger.info("Loaded V2 binary index (LAZY): {} entries",
                        existing.size());
            } catch (IOException e) {
                logger.warn("V2 binary index corrupt, falling back to JSON: {}", e.getMessage());
                existing = CodebaseIndex.loadClassesOnly(sourceRoot);
            }
        } else {
            existing = CodebaseIndex.loadClassesOnly(sourceRoot);
        }
        if (existing.isEmpty()) {
            return null;
        }

        Map<String, IndexEntry> byPath = new HashMap<>();
        for (IndexEntry e : existing) {
            byPath.put(e.path(), e);
        }

        List<IndexEntry> refreshed = new ArrayList<>();
        Set<String> currentPaths = new HashSet<>();
        int staleCount = 0;
        CodeParser parser = null;

        for (Path file : currentFiles) {
            String relativePath = sourceRoot.relativize(file).toString();
            currentPaths.add(relativePath);

            IndexEntry prev = byPath.get(relativePath);
            if (prev != null) {
                try {
                    long currentModified = Files.getLastModifiedTime(file).toMillis();
                    if (currentModified <= prev.lastModified()) {
                        refreshed.add(prev);
                        continue;
                    }
                } catch (IOException e) {
                }
            }

            if (parser == null) parser = new HybridJavaParser();
            staleCount++;
            try {
                byte[] content = Files.readAllBytes(file);
                String hash = com.jsrc.app.util.Hashing.sha256(content);
                long lastModified = Files.getLastModifiedTime(file).toMillis();
                List<ClassInfo> classes = parser.parseClasses(file);
                List<IndexedClass> indexed = classes.stream()
                        .map(ci -> classInfoToIndexed(ci))
                        .toList();
                var edgeResolver = new EdgeResolver();
                var edgeParser = new com.github.javaparser.JavaParser();
                List<CallEdge> edges = edgeResolver.extractCallEdges(file, edgeParser);
                var smells = parser.detectSmells(file).stream()
                        .map(s -> new CachedSmell(s.ruleId(), s.severity().name(),
                                s.line(), s.methodName(), s.className(), s.message()))
                        .toList();
                refreshed.add(new IndexEntry(relativePath, hash, lastModified, indexed, edges, smells));
            } catch (IOException e) {
                logger.error("Error refreshing {}: {}", file, e.getMessage());
                if (prev != null) refreshed.add(prev);
            }
        }

        if (staleCount > 0) {
            logger.info("Auto-refreshed {} stale/new file(s), {} cached", staleCount, refreshed.size() - staleCount);
            var updatedIndex = new CodebaseIndex(refreshed);
            try {
                var builder = new com.jsrc.app.analysis.CallGraphBuilder();
                builder.loadFromIndex(refreshed);
                var graphForSave = builder.toCallGraph();
                updatedIndex.saveWithGraph(sourceRoot, graphForSave, loadedMigrations);
                
                // Re-read index in LAZY mode to restore lazy state after refresh
                if (Files.exists(v2File)) {
                    try {
                        lazyData = BinaryIndexV2Reader.readLazy(v2File);
                        refreshed = new ArrayList<>(lazyData.getData().entries());
                        loadedMigrations = lazyData.getData().migrations();
                        preBuiltGraph = null; // keep lazy until ensureGraph
                        logger.debug("Re-loaded index in lazy mode after refresh");
                    } catch (IOException readEx) {
                        logger.warn("Could not re-read lazy index after save: {}", readEx.getMessage());
                        preBuiltGraph = graphForSave;
                        lazyData = null;
                    }
                } else {
                    preBuiltGraph = graphForSave;
                    lazyData = null;
                }
            } catch (IOException e) {
                logger.warn("Could not save refreshed index: {}", e.getMessage());
            }
        } else {
            logger.info("Index up-to-date: {} entries", refreshed.size());
        }

        var indexed = new IndexedCodebase(refreshed);
        indexed.sourceRoot = sourceRoot;
        indexed.edgesLoaded = refreshed.stream().anyMatch(e -> !e.callEdges().isEmpty());
        indexed.smellsLoaded = refreshed.stream().anyMatch(e -> !e.smells().isEmpty());
        indexed.preBuiltCallGraph = preBuiltGraph;
        indexed.lazyIndexData = lazyData;
        indexed.migrationCache = loadedMigrations;
        return indexed;
    }

    // tryLoad(Path) without refresh removed — use tryLoad(Path, List<Path>) which auto-refreshes

    /**
     * Returns all classes from the index, converted to ClassInfo.
     */
    public List<ClassInfo> getAllClasses() {
        if (allClasses == null) {
            allClasses = new ArrayList<>();
            for (IndexEntry entry : entries) {
                for (IndexedClass ic : entry.classes()) {
                    allClasses.add(toClassInfo(ic));
                }
            }
        }
        return allClasses;
    }

    /**
     * Returns the file path (relative) for a given class name.
     */
    public Optional<String> findFileForClass(String className) {
        classLookup(); // ensure maps are built
        String path = classToPath.get(className);
        return path != null ? Optional.of(path) : Optional.empty();
    }

    /**
     * Assembles dependency information for a class from the index.
     * Avoids on-the-fly parsing — uses indexed imports, fields, and constructor params.
     *
     * @param className simple or qualified class name
     * @return dependency result, or empty if not found
     */
    /**
     * Returns a lazily-built O(1) lookup map from class name to IndexedClass.
     * Maps both simple name and qualified name for fast lookup.
     */
    private java.util.Map<String, IndexedClass> classLookup() {
        if (classLookup == null) {
            classLookup = new java.util.HashMap<>();
            classToPath = new java.util.HashMap<>();
            for (IndexEntry entry : entries) {
                for (IndexedClass ic : entry.classes()) {
                    classLookup.putIfAbsent(ic.name(), ic);
                    classLookup.putIfAbsent(ic.qualifiedName(), ic);
                    classToPath.putIfAbsent(ic.name(), entry.path());
                    classToPath.putIfAbsent(ic.qualifiedName(), entry.path());
                }
            }
        }
        return classLookup;
    }

    public Optional<com.jsrc.app.model.DependencyResult> getDependencies(String className) {
        IndexedClass ic = classLookup().get(className);
        if (ic == null) return Optional.empty();

        List<com.jsrc.app.model.DependencyResult.FieldDep> fieldDeps = ic.fields().stream()
                .map(f -> new com.jsrc.app.model.DependencyResult.FieldDep(f.type(), f.name()))
                .toList();

        // Constructor params: find methods named after the class (constructors)
        List<com.jsrc.app.model.DependencyResult.FieldDep> ctorDeps = ic.methods().stream()
                .filter(m -> m.name().equals(ic.name()))
                .flatMap(m -> extractParamsFromSignature(m.signature()).stream())
                .toList();

        return Optional.of(new com.jsrc.app.model.DependencyResult(
                ic.qualifiedName(), ic.imports(), fieldDeps, ctorDeps));
    }

    /**
     * Extracts parameter names and types from a method signature string.
     * E.g. "public OrderService(OrderRepo repo, EventBus bus)" → [(OrderRepo,repo), (EventBus,bus)]
     */
    private static List<com.jsrc.app.model.DependencyResult.FieldDep> extractParamsFromSignature(String signature) {
        if (signature == null || signature.isEmpty()) return List.of();
        int openParen = signature.indexOf('(');
        int closeParen = signature.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen + 1) return List.of();

        String paramStr = signature.substring(openParen + 1, closeParen).trim();
        if (paramStr.isEmpty()) return List.of();

        var result = new ArrayList<com.jsrc.app.model.DependencyResult.FieldDep>();
        for (String param : paramStr.split(",")) {
            param = param.trim();
            // Remove annotations like @NonNull
            while (param.startsWith("@")) {
                int space = param.indexOf(' ');
                if (space > 0) param = param.substring(space + 1).trim();
                else break;
            }
            int lastSpace = param.lastIndexOf(' ');
            if (lastSpace > 0) {
                String type = param.substring(0, lastSpace).trim();
                String name = param.substring(lastSpace + 1).trim();
                // Strip generics from type
                int genIdx = type.indexOf('<');
                if (genIdx > 0) type = type.substring(0, genIdx);
                result.add(new com.jsrc.app.model.DependencyResult.FieldDep(type, name));
            }
        }
        return result;
    }

    /**
     * Returns all methods matching a name from the index.
     */
    public List<MethodInfo> findMethodsByName(String methodName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.name().equals(methodName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all methods with a given annotation from the index.
     */
    public List<MethodInfo> findMethodsByAnnotation(String annotationName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.annotations().contains(annotationName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all classes with a given annotation from the index.
     */
    public List<ClassInfo> findClassesByAnnotation(String annotationName) {
        return getAllClasses().stream()
                .filter(ci -> ci.annotations().stream()
                        .anyMatch(a -> a.name().equals(annotationName)))
                .toList();
    }

    /**
     * Returns ClassInfo objects for all classes defined in a given file path.
     *
     * @param filePath relative or absolute file path
     * @return classes in that file (empty list if not indexed)
     */
    public List<ClassInfo> findClassesInFile(String filePath) {
        for (IndexEntry entry : entries) {
            if (filePath.endsWith(entry.path()) || entry.path().equals(filePath)) {
                return entry.classes().stream()
                        .map(IndexedCodebase::toClassInfo)
                        .toList();
            }
        }
        return List.of();
    }

    /**
     * Returns index entries whose classes or methods contain the pattern in their names.
     * Used by SearchCommand to narrow file search when an index is available.
     *
     * @param pattern search pattern (matched case-insensitively against class names,
     *                method names, and annotation names)
     * @return matching entries (may be empty)
     */
    public List<IndexEntry> findEntriesContaining(String pattern) {
        String lower = pattern.toLowerCase();
        List<IndexEntry> matching = new ArrayList<>();
        for (IndexEntry entry : entries) {
            if (entryContains(entry, lower)) {
                matching.add(entry);
            }
        }
        return matching;
    }

    private static boolean entryContains(IndexEntry entry, String lower) {
        for (IndexedClass ic : entry.classes()) {
            // Class/interface names
            if (ic.name().toLowerCase().contains(lower)
                    || ic.qualifiedName().toLowerCase().contains(lower)) {
                return true;
            }
            // Class annotations
            for (String ann : ic.annotations()) {
                if (ann.toLowerCase().contains(lower)) return true;
            }
            // Imports (e.g. searching for a type used as parameter)
            for (String imp : ic.imports()) {
                if (imp.toLowerCase().contains(lower)) return true;
            }
            // Methods: name, signature, annotations
            for (IndexedMethod im : ic.methods()) {
                if (im.name().toLowerCase().contains(lower)) return true;
                if (im.signature() != null && im.signature().toLowerCase().contains(lower)) return true;
                for (String ann : im.annotations()) {
                    if (ann.toLowerCase().contains(lower)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the raw index entries (used by CallGraphBuilder.loadFromIndex).
     */
    public List<IndexEntry> getEntries() {
        return entries;
    }

    /**
     * Returns true if any entry has call edges indexed.
     */
    /**
     * Returns the pre-built call graph loaded from V2 binary index, or null.
     * Triggers lazy loading if graph hasn't been loaded yet.
     */
    public com.jsrc.app.analysis.CallGraph preBuiltCallGraph() {
        if (preBuiltCallGraph == null && lazyIndexData != null) {
            preBuiltCallGraph = lazyIndexData.ensureGraph();
        }
        return preBuiltCallGraph;
    }

    public boolean hasCachedMigrations() {
        return migrationCache != null && !migrationCache.isEmpty();
    }

    public List<CachedMigration> getCachedMigrations(String path) {
        if (migrationCache == null) return List.of();
        return migrationCache.getOrDefault(path, List.of());
    }

    public java.util.Map<String, List<CachedMigration>> getAllMigrations() {
        return migrationCache != null ? migrationCache : java.util.Map.of();
    }

    public void setMigrationCache(java.util.Map<String, List<CachedMigration>> cache) {
        this.migrationCache = cache;
    }

    public boolean hasCallEdges() {
        ensureEdgesLoaded();
        return entries.stream().anyMatch(e -> !e.callEdges().isEmpty());
    }

    /** Returns true if the index has precomputed code smells. */
    public boolean hasCachedSmells() {
        ensureSmellsLoaded();
        return entries.stream().anyMatch(e -> !e.smells().isEmpty());
    }

    /** Returns cached smells for a given file path, or empty list. */
    public List<CachedSmell> getCachedSmells(String path) {
        for (var entry : entries) {
            if (entry.path().equals(path)) {
                return entry.smells();
            }
        }
        return List.of();
    }

    /** Updates cached smells for a given file path. */
    public void setCachedSmells(String path, List<CachedSmell> smells) {
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.path().equals(path)) {
                entries.set(i, new IndexEntry(entry.path(), entry.contentHash(),
                        entry.lastModified(), entry.classes(), entry.callEdges(), smells));
                return;
            }
        }
    }

    /** Persists the index (with cached smells) to disk. */
    public void save(java.nio.file.Path projectRoot) {
        try {
            var index = new CodebaseIndex();
            index.saveEntries(projectRoot, entries);
        } catch (Exception e) {
            logger.warn("Failed to persist cached smells: {}", e.getMessage());
        }
    }

    /**
     * Returns the number of indexed files.
     */
    public int fileCount() {
        return entries.size();
    }

    // ---- conversion ----

    private static ClassInfo toClassInfo(IndexedClass ic) {
        List<MethodInfo> methods = ic.methods().stream()
                .map(im -> toMethodInfo(im, ic.name()))
                .toList();

        List<AnnotationInfo> annotations = ic.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        String superClass = ic.superClass().isEmpty() ? "" : ic.superClass().getFirst();

        List<com.jsrc.app.parser.model.FieldInfo> fields = ic.fields().stream()
                .map(f -> new com.jsrc.app.parser.model.FieldInfo(f.name(), f.type(), f.modifiers()))
                .toList();

        return new ClassInfo(
                ic.name(), ic.packageName(), ic.startLine(), ic.endLine(),
                List.of(), methods, superClass,
                ic.interfaces(), annotations, ic.isInterface(), fields);
    }

    private static MethodInfo toMethodInfo(IndexedMethod im, String className) {
        List<AnnotationInfo> annotations = im.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        var params = parseParamsFromSignature(im.signature());
        var thrownExceptions = parseThrowsFromSignature(im.signature());
        var modifiers = parseModifiersFromSignature(im.signature());

        return new MethodInfo(
                im.name(), className, im.startLine(), im.endLine(),
                im.returnType(), modifiers, params,
                "", // no content from index
                annotations, thrownExceptions, List.of(), null);
    }

    /**
     * Extracts parameter info from a signature string.
     * E.g. "public BindResult<T> bind(String name, Class<T> target)" → [String name, Class<T> target]
     */
    private static List<com.jsrc.app.parser.model.MethodInfo.ParameterInfo> parseParamsFromSignature(String sig) {
        if (sig == null || !sig.contains("(")) return List.of();
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (close <= open + 1) return List.of();
        String paramStr = sig.substring(open + 1, close).trim();
        if (paramStr.isEmpty()) return List.of();

        var result = new ArrayList<com.jsrc.app.parser.model.MethodInfo.ParameterInfo>();
        // Split by comma, but respect generics: "Map<K,V> map, String name"
        int depth = 0;
        int start = 0;
        for (int i = 0; i < paramStr.length(); i++) {
            char c = paramStr.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                addParam(result, paramStr.substring(start, i).trim());
                start = i + 1;
            }
        }
        addParam(result, paramStr.substring(start).trim());
        return result;
    }

    private static List<String> parseThrowsFromSignature(String sig) {
        if (sig == null || !sig.contains(" throws ")) return List.of();
        String afterThrows = sig.substring(sig.lastIndexOf(" throws ") + 8).trim();
        return java.util.Arrays.stream(afterThrows.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> parseModifiersFromSignature(String sig) {
        if (sig == null) return List.of();
        var mods = new ArrayList<String>();
        String[] tokens = sig.split("\\s+");
        for (String t : tokens) {
            if (java.util.Set.of("public", "protected", "private", "static",
                    "final", "abstract", "synchronized", "native", "default").contains(t)) {
                mods.add(t);
            } else break;
        }
        return mods;
    }

    private static void addParam(List<com.jsrc.app.parser.model.MethodInfo.ParameterInfo> list, String param) {
        if (param.isEmpty()) return;
        int lastSpace = param.lastIndexOf(' ');
        if (lastSpace > 0) {
            list.add(new com.jsrc.app.parser.model.MethodInfo.ParameterInfo(
                    param.substring(0, lastSpace).trim(),
                    param.substring(lastSpace + 1).trim()));
        }
    }

    static IndexedClass classInfoToIndexed(ClassInfo ci) {
        List<IndexedMethod> methods = ci.methods().stream()
                .map(m -> new IndexedMethod(
                        m.name(), m.signature(), m.startLine(), m.endLine(),
                        m.returnType(),
                        m.annotations().stream().map(a -> a.name()).toList()))
                .toList();

        List<String> annotations = ci.annotations().stream()
                .map(a -> a.name()).toList();

        return new IndexedClass(
                ci.name(), ci.packageName(), ci.startLine(), ci.endLine(),
                ci.isInterface(), ci.isAbstract(),
                ci.superClass().isEmpty() ? List.of() : List.of(ci.superClass()),
                ci.interfaces(), methods, annotations, List.of());
    }

}
