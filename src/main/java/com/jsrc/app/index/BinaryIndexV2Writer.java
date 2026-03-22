package com.jsrc.app.index;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Writes unified binary index with all data in one file.
 * Format: [MAGIC 4B][VERSION 4B][CRC32 4B][PAYLOAD]
 *
 * <p>Sections in payload:
 * <ol>
 *   <li>STRING_TABLE — deduplicated strings</li>
 *   <li>CLASSES — entries with classes/fields/methods</li>
 *   <li>EDGES — raw call edges per file</li>
 *   <li>GRAPH — pre-resolved callerIndex + calleeIndex adjacency lists</li>
 *   <li>SMELLS — cached code smells</li>
 * </ol>
 *
 * <p>Write uses temp file + atomic rename to prevent corruption.</p>
 */
public class BinaryIndexV2Writer {

    static final byte[] MAGIC = {'J', 'S', 'R', '2'};
    static final int VERSION = 2;

    /**
     * Writes the unified index to disk.
     *
     * @param file       target path (.jsrc/index.bin)
     * @param entries    index entries with classes, edges, smells
     * @param callGraph  pre-resolved call graph (may be null if not built yet)
     */
    public static void write(Path file, List<IndexEntry> entries, CallGraph callGraph) throws IOException {
        // Build string table from all data
        Map<String, Integer> stringTable = new LinkedHashMap<>();
        List<String> strings = new ArrayList<>();

        // Intern strings from entries (classes, fields, methods)
        for (var entry : entries) {
            intern(entry.path(), stringTable, strings);
            intern(entry.contentHash(), stringTable, strings);
            for (var ic : entry.classes()) {
                internClass(ic, stringTable, strings);
            }
            for (var edge : entry.callEdges()) {
                intern(edge.callerClass(), stringTable, strings);
                intern(edge.callerMethod(), stringTable, strings);
                intern(edge.calleeClass(), stringTable, strings);
                intern(edge.calleeMethod(), stringTable, strings);
            }
            for (var smell : entry.smells()) {
                intern(smell.ruleId(), stringTable, strings);
                intern(smell.severity(), stringTable, strings);
                intern(smell.message(), stringTable, strings);
                intern(safe(smell.className()), stringTable, strings);
                intern(safe(smell.method()), stringTable, strings);
            }
        }

        // Intern strings from call graph
        if (callGraph != null) {
            for (MethodReference ref : callGraph.getAllMethods()) {
                intern(ref.className(), stringTable, strings);
                intern(ref.methodName(), stringTable, strings);
            }
        }

        // Write payload to buffer first (for CRC32)
        var payload = new ByteArrayOutputStream();
        var out = new DataOutputStream(payload);

        // 1. STRING_TABLE
        out.writeInt(strings.size());
        for (String s : strings) {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }

        // 2. CLASSES
        out.writeInt(entries.size());
        for (var entry : entries) {
            writeEntry(out, entry, stringTable);
        }

        // 3. EDGES (raw, per file)
        int totalEdgeFiles = (int) entries.stream().filter(e -> !e.callEdges().isEmpty()).count();
        out.writeInt(totalEdgeFiles);
        for (var entry : entries) {
            if (entry.callEdges().isEmpty()) continue;
            out.writeInt(ref(entry.path(), stringTable));
            out.writeInt(entry.callEdges().size());
            for (var edge : entry.callEdges()) {
                out.writeInt(ref(edge.callerClass(), stringTable));
                out.writeInt(ref(edge.callerMethod(), stringTable));
                out.writeInt(edge.callerParamCount());
                out.writeInt(ref(edge.calleeClass(), stringTable));
                out.writeInt(ref(edge.calleeMethod(), stringTable));
                out.writeInt(edge.argCount());
                out.writeInt(edge.line());
            }
        }

        // 4. GRAPH (pre-resolved adjacency lists)
        if (callGraph != null) {
            out.writeByte(1); // has graph
            writeGraph(out, callGraph, stringTable);
        } else {
            out.writeByte(0); // no graph
        }

        // 5. SMELLS
        int totalSmellFiles = (int) entries.stream().filter(e -> !e.smells().isEmpty()).count();
        out.writeInt(totalSmellFiles);
        for (var entry : entries) {
            if (entry.smells().isEmpty()) continue;
            out.writeInt(ref(entry.path(), stringTable));
            out.writeInt(entry.smells().size());
            for (var smell : entry.smells()) {
                out.writeInt(ref(smell.ruleId(), stringTable));
                out.writeInt(ref(smell.severity(), stringTable));
                out.writeInt(smell.line());
                out.writeInt(ref(safe(smell.method()), stringTable));
                out.writeInt(ref(safe(smell.className()), stringTable));
                out.writeInt(ref(smell.message(), stringTable));
            }
        }

        out.flush();
        byte[] payloadBytes = payload.toByteArray();

        // Compute CRC32
        CRC32 crc = new CRC32();
        crc.update(payloadBytes);

        // Write to temp file + atomic rename
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var fileOut = new DataOutputStream(Files.newOutputStream(temp))) {
            fileOut.write(MAGIC);
            fileOut.writeInt(VERSION);
            fileOut.writeInt((int) crc.getValue());
            fileOut.write(payloadBytes);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeGraph(DataOutputStream out, CallGraph graph,
                                    Map<String, Integer> stringTable) throws IOException {
        // All methods
        Set<MethodReference> allMethods = graph.getAllMethods();
        out.writeInt(allMethods.size());

        // Assign numeric IDs to methods
        Map<MethodReference, Integer> methodIds = new HashMap<>();
        int id = 0;
        for (MethodReference ref : allMethods) {
            methodIds.put(ref, id);
            out.writeInt(ref(ref.className(), stringTable));
            out.writeInt(ref(ref.methodName(), stringTable));
            out.writeInt(ref.parameterCount());
            id++;
        }

        // CallerIndex: for each callee, list of (caller, line) pairs
        var callerKeys = graph.getAllCallerIndexKeys();
        // Only write entries that have callers
        List<MethodReference> keysWithCallers = new ArrayList<>();
        for (MethodReference key : callerKeys) {
            if (!graph.getCallersOf(key).isEmpty()) keysWithCallers.add(key);
        }
        out.writeInt(keysWithCallers.size());
        for (MethodReference callee : keysWithCallers) {
            out.writeInt(methodIds.getOrDefault(callee, -1));
            Set<MethodCall> callers = graph.getCallersOf(callee);
            out.writeInt(callers.size());
            for (MethodCall call : callers) {
                int callerId = methodIds.getOrDefault(call.caller(), -1);
                out.writeInt(callerId);
                out.writeInt(call.line());
            }
        }

        // CalleeIndex: for each caller, list of (callee, line) pairs
        List<MethodReference> callersWithCallees = new ArrayList<>();
        for (MethodReference ref : allMethods) {
            if (!graph.getCalleesOf(ref).isEmpty()) callersWithCallees.add(ref);
        }
        out.writeInt(callersWithCallees.size());
        for (MethodReference caller : callersWithCallees) {
            out.writeInt(methodIds.getOrDefault(caller, -1));
            Set<MethodCall> callees = graph.getCalleesOf(caller);
            out.writeInt(callees.size());
            for (MethodCall call : callees) {
                int calleeId = methodIds.getOrDefault(call.callee(), -1);
                out.writeInt(calleeId);
                out.writeInt(call.line());
            }
        }
    }

    private static void writeEntry(DataOutputStream out, IndexEntry entry,
                                    Map<String, Integer> stringTable) throws IOException {
        out.writeInt(ref(entry.path(), stringTable));
        out.writeInt(ref(entry.contentHash(), stringTable));
        out.writeLong(entry.lastModified());

        out.writeShort(entry.classes().size());
        for (var ic : entry.classes()) {
            out.writeInt(ref(ic.name(), stringTable));
            out.writeInt(ref(ic.packageName(), stringTable));
            out.writeInt(ic.startLine());
            out.writeInt(ic.endLine());
            out.writeByte((ic.isInterface() ? 1 : 0) | (ic.isAbstract() ? 2 : 0));

            writeStringRefs(out, ic.superClass(), stringTable);
            writeStringRefs(out, ic.interfaces(), stringTable);
            writeStringRefs(out, ic.annotations(), stringTable);
            writeStringRefs(out, ic.imports(), stringTable);

            out.writeShort(ic.fields().size());
            for (var f : ic.fields()) {
                out.writeInt(ref(f.name(), stringTable));
                out.writeInt(ref(f.type(), stringTable));
                writeStringRefs(out, f.modifiers(), stringTable);
            }

            out.writeShort(ic.methods().size());
            for (var m : ic.methods()) {
                out.writeInt(ref(m.name(), stringTable));
                out.writeInt(ref(safe(m.signature()), stringTable));
                out.writeInt(ref(safe(m.returnType()), stringTable));
                out.writeInt(m.startLine());
                out.writeInt(m.endLine());
                out.writeShort(m.complexity());
                out.writeByte(m.paramCount());
                writeStringRefs(out, m.annotations(), stringTable);
            }
        }
    }

    private static void internClass(IndexedClass ic, Map<String, Integer> table, List<String> list) {
        intern(ic.name(), table, list);
        intern(ic.packageName(), table, list);
        for (String s : ic.superClass()) intern(s, table, list);
        for (String s : ic.interfaces()) intern(s, table, list);
        for (String s : ic.annotations()) intern(s, table, list);
        for (String s : ic.imports()) intern(s, table, list);
        for (var f : ic.fields()) {
            intern(f.name(), table, list);
            intern(f.type(), table, list);
            for (String mod : f.modifiers()) intern(mod, table, list);
        }
        for (var m : ic.methods()) {
            intern(m.name(), table, list);
            intern(safe(m.signature()), table, list);
            intern(safe(m.returnType()), table, list);
            for (String a : m.annotations()) intern(a, table, list);
        }
    }

    private static String safe(String s) { return s != null ? s : ""; }

    private static void intern(String s, Map<String, Integer> table, List<String> list) {
        if (s != null && !table.containsKey(s)) {
            table.put(s, list.size());
            list.add(s);
        }
    }

    private static int ref(String s, Map<String, Integer> table) {
        if (s == null || s.isEmpty()) return -1;
        return table.getOrDefault(s, -1);
    }

    private static void writeStringRefs(DataOutputStream out, List<String> strings,
                                         Map<String, Integer> table) throws IOException {
        out.writeShort(strings.size());
        for (String s : strings) out.writeInt(ref(s, table));
    }
}
