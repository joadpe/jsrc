package com.jsrc.app.index;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads unified binary index (V2) with classes, edges, pre-resolved call graph, and smells.
 * Counterpart to {@link BinaryIndexV2Writer}.
 */
public class BinaryIndexV2Reader {

    private static final Logger logger = LoggerFactory.getLogger(BinaryIndexV2Reader.class);

    private static volatile boolean graphParsedFlag = false;
    private static volatile int graphParseCount = 0;

    public static void resetGraphParsedFlag() {
        graphParsedFlag = false;
        graphParseCount = 0;
    }

    public static boolean wasGraphParsed() {
        return graphParsedFlag;
    }

    public static int getGraphParseCount() {
        return graphParseCount;
    }

    /**
     * Result of reading the binary index.
     */
    public record IndexData(
            List<IndexEntry> entries,
            CallGraph callGraph,
            Map<String, List<CachedMigration>> migrations
    ) {}

    /**
     * Lazy container for index data with deferred graph loading.
     * Uses ByteBuffer (potentially mmap) instead of byte[] for zero heap allocation.
     */
    public static class LazyIndexData {
        private final IndexData lightData;  // callGraph=null initially
        private final ByteBuffer payload;
        private final int graphSectionOffset;
        private final String[] stringTable;
        private CallGraph lazyGraph;

        LazyIndexData(IndexData lightData, ByteBuffer payload, int graphSectionOffset, String[] stringTable) {
            this.lightData = lightData;
            this.payload = payload;
            this.graphSectionOffset = graphSectionOffset;
            this.stringTable = stringTable;
        }

        /**
         * Returns the index data (graph may be null if not yet loaded).
         */
        public IndexData getData() {
            return lightData;
        }

        /**
         * Ensures the call graph is loaded and returns it.
         * Subsequent calls return the same instance (cached).
         *
         * @return loaded CallGraph, or null if index has no graph section
         */
        public CallGraph ensureGraph() {
            if (lazyGraph == null && graphSectionOffset >= 0) {
                try {
                    int offsetInPayload = graphSectionOffset + 12;
                    ByteBuffer sliced = payload.duplicate();
                    sliced.position(offsetInPayload);
                    var in = new DataInputStream(new ByteBufferInputStream(sliced));
                    lazyGraph = readGraph(in, stringTable);
                    graphParsedFlag = true;
                    graphParseCount++;
                    logger.debug("Lazy-loaded CallGraph on demand");
                } catch (IOException e) {
                    logger.warn("Failed to lazy-load CallGraph: {}", e.getMessage());
                }
            }
            return lazyGraph;
        }
    }

    /**
     * Reads the unified binary index with eager graph loading.
     *
     * @param file path to index.bin
     * @return parsed index data, or null if file is invalid/corrupt
     */
    public static IndexData read(Path file) throws IOException {
        byte[] allBytes = Files.readAllBytes(file);
        if (allBytes.length < 12) throw new IOException("Binary index too small");

        // Read header
        if (allBytes[0] != 'J' || allBytes[1] != 'S' || allBytes[2] != 'R' || allBytes[3] != '2') {
            throw new IOException("Not a jsrc V2 binary index");
        }

        var headerIn = new DataInputStream(new ByteArrayInputStream(allBytes, 4, 8));
        int version = headerIn.readInt();
        if (version != BinaryIndexV2Writer.VERSION) {
            throw new IOException("Unsupported V2 index version: " + version);
        }
        int storedCrc = headerIn.readInt();

        // Verify CRC32
        CRC32 crc = new CRC32();
        crc.update(allBytes, 12, allBytes.length - 12);
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException("CRC32 mismatch — index may be corrupt");
        }

        var in = new DataInputStream(new ByteArrayInputStream(allBytes, 12, allBytes.length - 12));

        // 1. STRING_TABLE
        int stringCount = in.readInt();
        String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int len = in.readUnsignedShort();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            strings[i] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        // 2. CLASSES
        int entryCount = in.readInt();
        List<IndexEntry> entries = new ArrayList<>(entryCount);
        for (int e = 0; e < entryCount; e++) {
            entries.add(readEntry(in, strings));
        }

        // 3. EDGES
        int edgeFileCount = in.readInt();
        Map<String, List<CallEdge>> edgesByPath = new HashMap<>();
        for (int ef = 0; ef < edgeFileCount; ef++) {
            String path = str(in.readInt(), strings);
            int edgeCount = in.readInt();
            List<CallEdge> edges = new ArrayList<>(edgeCount);
            for (int i = 0; i < edgeCount; i++) {
                String callerClass = str(in.readInt(), strings);
                String callerMethod = str(in.readInt(), strings);
                int callerParamCount = in.readInt();
                String calleeClass = str(in.readInt(), strings);
                String calleeMethod = str(in.readInt(), strings);
                int argCount = in.readInt();
                int line = in.readInt();
                edges.add(new CallEdge(callerClass, callerMethod, callerParamCount,
                        calleeClass, calleeMethod, argCount, line));
            }
            edgesByPath.put(path, edges);
        }

        // Merge edges into entries
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            List<CallEdge> edges = edgesByPath.get(entry.path());
            if (edges != null) {
                entries.set(i, entry.withEdges(edges));
            }
        }

        // 4. GRAPH (pre-resolved) - EAGER
        CallGraph callGraph = null;
        byte hasGraph = in.readByte();
        if (hasGraph == 1) {
            callGraph = readGraph(in, strings);
            graphParsedFlag = true;
            graphParseCount++;
        }

        // 5. SMELLS
        int smellFileCount = in.readInt();
        for (int sf = 0; sf < smellFileCount; sf++) {
            String path = str(in.readInt(), strings);
            int smellCount = in.readInt();
            List<CachedSmell> smells = new ArrayList<>(smellCount);
            for (int i = 0; i < smellCount; i++) {
                String ruleId = str(in.readInt(), strings);
                String severity = str(in.readInt(), strings);
                int line = in.readInt();
                String method = str(in.readInt(), strings);
                String className = str(in.readInt(), strings);
                String message = str(in.readInt(), strings);
                smells.add(new CachedSmell(ruleId, severity, line, method, className, message));
            }
            // Merge smells into entry
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).path().equals(path)) {
                    entries.set(i, entries.get(i).withSmells(smells));
                    break;
                }
            }
        }

        // 6. MIGRATIONS
        Map<String, List<CachedMigration>> migrations = new HashMap<>();
        if (in.available() > 0) {
            byte hasMigrations = in.readByte();
            if (hasMigrations == 1) {
                int migFileCount = in.readInt();
                for (int mf = 0; mf < migFileCount; mf++) {
                    String path = str(in.readInt(), strings);
                    int migCount = in.readUnsignedShort();
                    List<CachedMigration> migs = new ArrayList<>(migCount);
                    for (int m = 0; m < migCount; m++) {
                        int patternId = in.readUnsignedByte();
                        int line = in.readUnsignedShort();
                        migs.add(new CachedMigration(patternId, line));
                    }
                    migrations.put(path, migs);
                }
            }
        }

        logger.info("Loaded V2 binary index: {} entries, {} strings, graph={}, migrations={}",
                entryCount, stringCount, callGraph != null, migrations.size());
        return new IndexData(entries, callGraph, migrations);
    }

    /**
     * Reads the unified binary index with LAZY graph loading.
     * Graph parsing is deferred until ensureGraph() is called.
     * Uses memory-mapped I/O (FileChannel.map) to avoid heap allocation.
     * Falls back to Files.readAllBytes if mmap fails.
     *
     * @param file path to index.bin
     * @return LazyIndexData with deferred graph loading
     */
    public static LazyIndexData readLazy(Path file) throws IOException {
        ByteBuffer buffer;
        boolean usedMmap = false;

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 12) {
                throw new IOException("Binary index too small");
            }

            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            usedMmap = true;
            logger.debug("Using memory-mapped I/O for index.bin ({} bytes)", fileSize);
        } catch (IOException e) {
            logger.warn("Memory-mapping failed, falling back to heap copy: {}", e.getMessage());
            byte[] allBytes = Files.readAllBytes(file);
            buffer = ByteBuffer.wrap(allBytes);
        }

        if (buffer.remaining() < 12) {
            throw new IOException("Binary index too small");
        }

        // Read header
        if (buffer.get() != 'J' || buffer.get() != 'S' || buffer.get() != 'R' || buffer.get() != '2') {
            throw new IOException("Not a jsrc V2 binary index");
        }

        int version = buffer.getInt();
        if (version != BinaryIndexV2Writer.VERSION) {
            throw new IOException("Unsupported V2 index version: " + version);
        }
        int storedCrc = buffer.getInt();

        // Verify CRC32 over mmap region (chunk-based if no backing array)
        CRC32 crc = new CRC32();
        validateCrc32(buffer, 12, buffer.limit() - 12, crc);
        if ((int) crc.getValue() != storedCrc) {
            throw new IOException("CRC32 mismatch — index may be corrupt");
        }

        // Position after header
        buffer.position(12);
        var in = new DataInputStream(new ByteBufferInputStream(buffer));

        // 1. STRING_TABLE
        int stringCount = in.readInt();
        String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int len = in.readUnsignedShort();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            strings[i] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        // 2. CLASSES
        int entryCount = in.readInt();
        List<IndexEntry> entries = new ArrayList<>(entryCount);
        for (int e = 0; e < entryCount; e++) {
            entries.add(readEntry(in, strings));
        }

        // 3. EDGES
        int edgeFileCount = in.readInt();
        Map<String, List<CallEdge>> edgesByPath = new HashMap<>();
        for (int ef = 0; ef < edgeFileCount; ef++) {
            String path = str(in.readInt(), strings);
            int edgeCount = in.readInt();
            List<CallEdge> edges = new ArrayList<>(edgeCount);
            for (int i = 0; i < edgeCount; i++) {
                String callerClass = str(in.readInt(), strings);
                String callerMethod = str(in.readInt(), strings);
                int callerParamCount = in.readInt();
                String calleeClass = str(in.readInt(), strings);
                String calleeMethod = str(in.readInt(), strings);
                int argCount = in.readInt();
                int line = in.readInt();
                edges.add(new CallEdge(callerClass, callerMethod, callerParamCount,
                        calleeClass, calleeMethod, argCount, line));
            }
            edgesByPath.put(path, edges);
        }

        // Merge edges into entries
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            List<CallEdge> edges = edgesByPath.get(entry.path());
            if (edges != null) {
                entries.set(i, entry.withEdges(edges));
            }
        }

        // 4. GRAPH (pre-resolved) - LAZY: record offset but don't parse yet
        int graphSectionOffset = -1;
        int graphStartOffset = buffer.limit() - in.available() - 12;
        byte hasGraph = in.readByte();
        if (hasGraph == 1) {
            graphSectionOffset = graphStartOffset + 1;
        }

        // Skip graph section to continue reading smells/migrations
        if (hasGraph == 1) {
            skipGraphSection(in);
        }

        // 5. SMELLS
        int smellFileCount = in.readInt();
        for (int sf = 0; sf < smellFileCount; sf++) {
            String path = str(in.readInt(), strings);
            int smellCount = in.readInt();
            List<CachedSmell> smells = new ArrayList<>(smellCount);
            for (int i = 0; i < smellCount; i++) {
                String ruleId = str(in.readInt(), strings);
                String severity = str(in.readInt(), strings);
                int line = in.readInt();
                String method = str(in.readInt(), strings);
                String className = str(in.readInt(), strings);
                String message = str(in.readInt(), strings);
                smells.add(new CachedSmell(ruleId, severity, line, method, className, message));
            }
            // Merge smells into entry
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).path().equals(path)) {
                    entries.set(i, entries.get(i).withSmells(smells));
                    break;
                }
            }
        }

        // 6. MIGRATIONS
        Map<String, List<CachedMigration>> migrations = new HashMap<>();
        if (in.available() > 0) {
            byte hasMigrations = in.readByte();
            if (hasMigrations == 1) {
                int migFileCount = in.readInt();
                for (int mf = 0; mf < migFileCount; mf++) {
                    String path = str(in.readInt(), strings);
                    int migCount = in.readUnsignedShort();
                    List<CachedMigration> migs = new ArrayList<>(migCount);
                    for (int m = 0; m < migCount; m++) {
                        int patternId = in.readUnsignedByte();
                        int line = in.readUnsignedShort();
                        migs.add(new CachedMigration(patternId, line));
                    }
                    migrations.put(path, migs);
                }
            }
        }

        logger.info("Loaded V2 binary index (LAZY, mmap={}): {} entries, {} strings, graph={} (deferred), migrations={}",
                usedMmap, entryCount, stringCount, hasGraph == 1, migrations.size());

        var lightData = new IndexData(entries, null, migrations);
        return new LazyIndexData(lightData, buffer, graphSectionOffset, strings);
    }

    /**
     * Validates CRC32 over ByteBuffer region (works for both mmap and heap).
     * Reads in chunks to handle buffers without backing arrays.
     */
    private static void validateCrc32(ByteBuffer buffer, int offset, int length, CRC32 crc) {
        ByteBuffer slice = buffer.duplicate();
        slice.position(offset);
        slice.limit(offset + length);

        if (slice.hasArray()) {
            crc.update(slice.array(), slice.arrayOffset() + slice.position(), slice.remaining());
        } else {
            byte[] chunk = new byte[8192];
            while (slice.hasRemaining()) {
                int toRead = Math.min(chunk.length, slice.remaining());
                slice.get(chunk, 0, toRead);
                crc.update(chunk, 0, toRead);
            }
        }
    }

    /**
     * Skips the graph section by reading and discarding its data.
     * Used by readLazy to continue to smells/migrations sections.
     */
    private static void skipGraphSection(DataInputStream in) throws IOException {
        // All methods
        int methodCount = in.readInt();
        for (int i = 0; i < methodCount; i++) {
            in.readInt(); // className
            in.readInt(); // methodName
            in.readInt(); // paramCount
        }

        // CallerIndex
        int callerEntryCount = in.readInt();
        for (int i = 0; i < callerEntryCount; i++) {
            in.readInt(); // calleeId
            int callerCount = in.readInt();
            for (int j = 0; j < callerCount; j++) {
                in.readInt(); // callerId
                in.readInt(); // line
            }
        }

        // CalleeIndex
        int calleeEntryCount = in.readInt();
        for (int i = 0; i < calleeEntryCount; i++) {
            in.readInt(); // callerId
            int calleeCount = in.readInt();
            for (int j = 0; j < calleeCount; j++) {
                in.readInt(); // calleeId
                in.readInt(); // line
            }
        }
    }

    private static CallGraph readGraph(DataInputStream in, String[] strings) throws IOException {
        // All methods
        int methodCount = in.readInt();
        MethodReference[] methods = new MethodReference[methodCount];
        for (int i = 0; i < methodCount; i++) {
            String className = str(in.readInt(), strings);
            String methodName = str(in.readInt(), strings);
            int paramCount = in.readInt();
            methods[i] = new MethodReference(className, methodName, paramCount, null);
        }

        // CallerIndex
        Map<MethodReference, Set<MethodCall>> callerIndex = new HashMap<>();
        int callerEntryCount = in.readInt();
        for (int i = 0; i < callerEntryCount; i++) {
            int calleeId = in.readInt();
            MethodReference callee = calleeId >= 0 && calleeId < methodCount ? methods[calleeId] : null;
            int callerCount = in.readInt();
            Set<MethodCall> callers = new HashSet<>(callerCount);
            for (int j = 0; j < callerCount; j++) {
                int callerId = in.readInt();
                int line = in.readInt();
                MethodReference caller = callerId >= 0 && callerId < methodCount ? methods[callerId] : null;
                if (caller != null && callee != null) {
                    callers.add(new MethodCall(caller, callee, line));
                }
            }
            if (callee != null && !callers.isEmpty()) {
                callerIndex.put(callee, callers);
            }
        }

        // CalleeIndex
        Map<MethodReference, Set<MethodCall>> calleeIndex = new HashMap<>();
        int calleeEntryCount = in.readInt();
        for (int i = 0; i < calleeEntryCount; i++) {
            int callerId = in.readInt();
            MethodReference caller = callerId >= 0 && callerId < methodCount ? methods[callerId] : null;
            int calleeCount = in.readInt();
            Set<MethodCall> callees = new HashSet<>(calleeCount);
            for (int j = 0; j < calleeCount; j++) {
                int cId = in.readInt();
                int line = in.readInt();
                MethodReference callee = cId >= 0 && cId < methodCount ? methods[cId] : null;
                if (caller != null && callee != null) {
                    callees.add(new MethodCall(caller, callee, line));
                }
            }
            if (caller != null && !callees.isEmpty()) {
                calleeIndex.put(caller, callees);
            }
        }

        // Build methodsByName
        Set<MethodReference> allMethods = new HashSet<>(Arrays.asList(methods));
        Map<String, Set<MethodReference>> methodsByName = new HashMap<>();
        for (MethodReference ref : methods) {
            methodsByName.computeIfAbsent(ref.methodName(), k -> new HashSet<>()).add(ref);
        }

        return CallGraph.of(callerIndex, calleeIndex, allMethods, methodsByName);
    }

    private static IndexEntry readEntry(DataInputStream in, String[] strings) throws IOException {
        String path = str(in.readInt(), strings);
        String hash = str(in.readInt(), strings);
        long lastModified = in.readLong();

        int classCount = in.readUnsignedShort();
        List<IndexedClass> classes = new ArrayList<>(classCount);
        for (int c = 0; c < classCount; c++) {
            String name = str(in.readInt(), strings);
            String pkg = str(in.readInt(), strings);
            int startLine = in.readInt();
            int endLine = in.readInt();
            byte flags = in.readByte();
            boolean isInterface = (flags & 1) != 0;
            boolean isAbstract = (flags & 2) != 0;

            List<String> superClass = readStringRefs(in, strings);
            List<String> interfaces = readStringRefs(in, strings);
            List<String> annotations = readStringRefs(in, strings);
            List<String> imports = readStringRefs(in, strings);

            int fieldCount = in.readUnsignedShort();
            List<IndexedField> fields = new ArrayList<>(fieldCount);
            for (int f = 0; f < fieldCount; f++) {
                String fName = str(in.readInt(), strings);
                String fType = str(in.readInt(), strings);
                List<String> modifiers = readStringRefs(in, strings);
                fields.add(new IndexedField(fName, fType, modifiers));
            }

            int methodCount = in.readUnsignedShort();
            List<IndexedMethod> methods = new ArrayList<>(methodCount);
            for (int m = 0; m < methodCount; m++) {
                String mName = str(in.readInt(), strings);
                String sig = str(in.readInt(), strings);
                String retType = str(in.readInt(), strings);
                int mStart = in.readInt();
                int mEnd = in.readInt();
                short complexity = in.readShort();
                byte paramCount = in.readByte();
                List<String> mAnns = readStringRefs(in, strings);
                methods.add(new IndexedMethod(mName, sig, mStart, mEnd,
                        retType, mAnns, complexity, paramCount));
            }

            classes.add(new IndexedClass(name, pkg, startLine, endLine,
                    isInterface, isAbstract, superClass, interfaces, methods,
                    annotations, imports, fields));
        }

        return new IndexEntry(path, hash, lastModified, classes);
    }

    private static String str(int ref, String[] table) {
        if (ref < 0 || ref >= table.length) return "";
        return table[ref];
    }

    private static List<String> readStringRefs(DataInputStream in, String[] table) throws IOException {
        int count = in.readUnsignedShort();
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(str(in.readInt(), table));
        }
        return result;
    }
}
