package com.jsrc.app.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ByteBufferInputStream InputStream contract tests (I1-I3).
 * Oracles: InputStream specification compliance.
 */
class ByteBufferInputStreamContractTest {

    /**
     * I1: read(buf,0,0)==0 at EOF and mid-stream.
     */
    @Test
    void testI1_readZeroLengthReturnsZero() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        byte[] buf = new byte[10];

        // Mid-stream: read(buf,0,0) should return 0
        int result1 = stream.read(buf, 0, 0);
        assertEquals(0, result1, "read(buf,0,0) mid-stream must return 0");

        // Read all bytes to reach EOF
        stream.read(buf, 0, 5);

        // At EOF: read(buf,0,0) should still return 0 (not -1)
        int result2 = stream.read(buf, 0, 0);
        assertEquals(0, result2, "read(buf,0,0) at EOF must return 0 (not -1)");
    }

    /**
     * I2: skip(-1)==0, position unchanged.
     */
    @Test
    void testI2_skipNegativeReturnsZeroPositionUnchanged() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        // Read one byte to advance position
        assertEquals(1, stream.read());
        int availableBefore = stream.available();

        // skip(-1) should return 0 and not change position
        long skipped = stream.skip(-1);
        assertEquals(0L, skipped, "skip(-1) must return 0");

        int availableAfter = stream.available();
        assertEquals(availableBefore, availableAfter, 
            "skip(-1) must not change position");

        // Verify next read returns expected byte (position unchanged)
        assertEquals(2, stream.read(), 
            "Position should be unchanged after skip(-1)");
    }

    /**
     * I3: skip past end; invalid off/len per InputStream.
     */
    @Test
    void testI3_skipPastEndAndInvalidOffLen() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        // skip(1000) should skip to end, return actual skipped count
        long skipped = stream.skip(1000);
        assertEquals(5L, skipped, "skip(1000) should skip all 5 bytes and return 5");

        // At EOF, available() should return 0
        assertEquals(0, stream.available(), "After skipping past end, available() must be 0");

        // read() at EOF should return -1
        assertEquals(-1, stream.read(), "read() at EOF must return -1");

        // Test invalid off/len for read(buf, off, len)
        ByteBuffer buffer2 = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream2 = new ByteBufferInputStream(buffer2);
        byte[] buf = new byte[10];

        // Invalid: off < 0
        assertThrows(IndexOutOfBoundsException.class, 
            () -> stream2.read(buf, -1, 5),
            "read() with negative offset must throw IndexOutOfBoundsException");

        // Invalid: len < 0
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream2.read(buf, 0, -1),
            "read() with negative length must throw IndexOutOfBoundsException");

        // Invalid: off + len > buf.length
        assertThrows(IndexOutOfBoundsException.class,
            () -> stream2.read(buf, 8, 5),
            "read() with off+len > buf.length must throw IndexOutOfBoundsException");
    }

    /**
     * I1 edge case: read(buf,off,0) with various offsets should always return 0.
     */
    @Test
    void testI1_readZeroLengthVariousOffsets() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        byte[] buf = new byte[10];

        // read(buf, offset, 0) should always return 0 regardless of offset
        assertEquals(0, stream.read(buf, 0, 0));
        assertEquals(0, stream.read(buf, 5, 0));
        assertEquals(0, stream.read(buf, 9, 0));

        // Position should be unchanged
        assertEquals(5, stream.available());
    }

    /**
     * I2 edge case: skip(0) should return 0 and not change position.
     */
    @Test
    void testI2_skipZeroReturnsZeroPositionUnchanged() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        int availableBefore = stream.available();
        long skipped = stream.skip(0);

        assertEquals(0L, skipped, "skip(0) must return 0");
        assertEquals(availableBefore, stream.available(), 
            "skip(0) must not change position");
    }

    /**
     * I3 edge case: read at exact buffer boundary.
     */
    @Test
    void testI3_readAtExactBoundary() throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5});
        ByteBufferInputStream stream = new ByteBufferInputStream(buffer);

        byte[] buf = new byte[5];

        // Read exactly all bytes
        int read = stream.read(buf, 0, 5);
        assertEquals(5, read, "Should read all 5 bytes");

        // Next read should return -1 (EOF)
        int eof = stream.read(buf, 0, 5);
        assertEquals(-1, eof, "Next read after reading all bytes should return -1");
    }
}
