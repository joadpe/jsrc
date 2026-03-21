package com.jsrc.app.index;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes index entries in compact binary format.
 * ~5x smaller than JSON, ~5x faster to read.
 */
public class BinaryIndexWriter {

    static final byte[] MAGIC = {'J', 'S', 'R', 'C'};
    static final int VERSION = 1;

    public static void write(Path file, List<IndexEntry> entries) throws IOException {
        Map<String, Integer> stringTable = new LinkedHashMap<>();
        List<String> strings = new ArrayList<>();

        // Build string table from all entries
        for (var entry : entries) {
            intern(entry.path(), stringTable, strings);
            intern(entry.contentHash(), stringTable, strings);
            for (var ic : entry.classes()) {
                intern(ic.name(), stringTable, strings);
                intern(ic.packageName(), stringTable, strings);
                for (String s : ic.superClass()) intern(s, stringTable, strings);
                for (String s : ic.interfaces()) intern(s, stringTable, strings);
                for (String s : ic.annotations()) intern(s, stringTable, strings);
                for (String s : ic.imports()) intern(s, stringTable, strings);
                for (var f : ic.fields()) {
                    intern(f.name(), stringTable, strings);
                    intern(f.type(), stringTable, strings);
                    for (String mod : f.modifiers()) intern(mod, stringTable, strings);
                }
                for (var m : ic.methods()) {
                    intern(m.name(), stringTable, strings);
                    intern(safe(m.signature()), stringTable, strings);
                    intern(safe(m.returnType()), stringTable, strings);
                    for (String a : m.annotations()) intern(a, stringTable, strings);
                }
            }
        }

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(MAGIC);
            out.writeInt(VERSION);

            // String table
            out.writeInt(strings.size());
            for (String s : strings) {
                byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeShort(bytes.length);
                out.write(bytes);
            }

            // Entries
            out.writeInt(entries.size());
            for (var entry : entries) {
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

                    // Fields
                    out.writeShort(ic.fields().size());
                    for (var f : ic.fields()) {
                        out.writeInt(ref(f.name(), stringTable));
                        out.writeInt(ref(f.type(), stringTable));
                        writeStringRefs(out, f.modifiers(), stringTable);
                    }

                    // Methods
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
