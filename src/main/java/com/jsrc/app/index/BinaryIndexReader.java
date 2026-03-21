package com.jsrc.app.index;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads index entries from compact binary format.
 * Counterpart to {@link BinaryIndexWriter}.
 */
public class BinaryIndexReader {

    private static final Logger logger = LoggerFactory.getLogger(BinaryIndexReader.class);

    public static List<IndexEntry> read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, BinaryIndexWriter.MAGIC)) {
                throw new IOException("Not a jsrc binary index");
            }
            int version = in.readInt();
            if (version != BinaryIndexWriter.VERSION) {
                throw new IOException("Unsupported binary index version: " + version);
            }

            // String table
            int stringCount = in.readInt();
            String[] strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int len = in.readUnsignedShort();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                strings[i] = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }

            // Entries
            int entryCount = in.readInt();
            List<IndexEntry> entries = new ArrayList<>(entryCount);
            for (int e = 0; e < entryCount; e++) {
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

                    // IndexedClass constructor: name, pkg, startLine, endLine,
                    //   isInterface, isAbstract, superClass, interfaces, methods,
                    //   annotations, imports, fields
                    classes.add(new IndexedClass(name, pkg, startLine, endLine,
                            isInterface, isAbstract, superClass, interfaces, methods,
                            annotations, imports, fields));
                }

                entries.add(new IndexEntry(path, hash, lastModified, classes));
            }

            logger.info("Loaded binary index: {} entries, {} strings", entryCount, stringCount);
            return entries;
        }
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
