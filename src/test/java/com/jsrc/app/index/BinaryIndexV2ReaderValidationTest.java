package com.jsrc.app.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryIndexV2ReaderValidationTest {

    @Test
    void negativeEntryCountIsReportedAsCorruptIndex(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("index.bin");
        BinaryIndexV2Writer.write(file, List.of(), null);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(16, -1); // empty string table, then entry count
        var crc = new CRC32();
        crc.update(bytes, 12, bytes.length - 12);
        buffer.putInt(8, (int) crc.getValue());
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> BinaryIndexV2Reader.readLazy(file));
    }

    @Test
    void invalidDeferredGraphReferenceFailsAtOpen(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("index.bin");
        var method = new com.jsrc.app.parser.model.MethodReference(
                "A", "run", List.of(), null);
        var graph = com.jsrc.app.analysis.CallGraph.of(
                java.util.Map.of(), java.util.Map.of(), java.util.Set.of(method),
                java.util.Map.of("run", java.util.Set.of(method)));
        BinaryIndexV2Writer.write(file, List.of(), graph);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(12);
        int strings = buffer.getInt();
        for (int i = 0; i < strings; i++) {
            int length = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + length);
        }
        buffer.getInt(); // entries
        buffer.getInt(); // edge files
        buffer.get(); // has graph
        buffer.getInt(); // method count
        buffer.putInt(buffer.position(), -2); // first method class reference
        var crc = new CRC32();
        crc.update(bytes, 12, bytes.length - 12);
        buffer.putInt(8, (int) crc.getValue());
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> BinaryIndexV2Reader.readLazy(file));
    }

    @Test
    void negativeStringCountIsReportedAsCorruptIndex(@TempDir Path tempDir) throws Exception {
        byte[] payload = ByteBuffer.allocate(4).putInt(-1).array();
        var crc = new CRC32();
        crc.update(payload);
        byte[] bytes = ByteBuffer.allocate(12 + payload.length)
                .put(BinaryIndexV2Writer.MAGIC)
                .putInt(BinaryIndexV2Writer.VERSION)
                .putInt((int) crc.getValue())
                .put(payload)
                .array();
        Path file = tempDir.resolve("index.bin");
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> BinaryIndexV2Reader.readLazy(file));
    }

    @Test
    void invalidStringReferenceIsNotTurnedIntoEmptySymbol(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("index.bin");
        var entry = new IndexEntry("A.java", "hash", 0L,
                com.jsrc.app.project.SourceSet.UNKNOWN, List.of(), List.of(), List.of(), 0);
        BinaryIndexV2Writer.write(file, List.of(entry), null);

        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(12);
        int stringCount = buffer.getInt();
        for (int i = 0; i < stringCount; i++) {
            int length = Short.toUnsignedInt(buffer.getShort());
            buffer.position(buffer.position() + length);
        }
        buffer.getInt(); // entry count
        buffer.putInt(buffer.position(), -1); // invalid path reference
        var crc = new CRC32();
        crc.update(bytes, 12, bytes.length - 12);
        buffer.putInt(8, (int) crc.getValue());
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> BinaryIndexV2Reader.readLazy(file));
    }
}
