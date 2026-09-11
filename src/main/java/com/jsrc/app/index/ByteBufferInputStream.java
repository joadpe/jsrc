package com.jsrc.app.index;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Zero-copy InputStream adapter over ByteBuffer for mmap regions.
 * Used to read memory-mapped V2 binary index without heap allocation.
 */
class ByteBufferInputStream extends InputStream {
    private final ByteBuffer buffer;

    ByteBufferInputStream(ByteBuffer buffer) {
        this.buffer = buffer.duplicate();
    }

    @Override
    public int read() {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        return buffer.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (!buffer.hasRemaining()) {
            return -1;
        }
        int toRead = Math.min(len, buffer.remaining());
        buffer.get(b, off, toRead);
        return toRead;
    }

    @Override
    public int available() {
        return buffer.remaining();
    }

    @Override
    public long skip(long n) {
        int toSkip = (int) Math.min(n, buffer.remaining());
        buffer.position(buffer.position() + toSkip);
        return toSkip;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readlimit) {
        buffer.mark();
    }

    @Override
    public void reset() {
        buffer.reset();
    }
}
