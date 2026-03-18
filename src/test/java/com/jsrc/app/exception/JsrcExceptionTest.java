package com.jsrc.app.exception;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Tests for the jsrc exception hierarchy.
 */
class JsrcExceptionTest {

    // -- JsrcException base --

    @Test
    void jsrcException_isRuntimeException() {
        var ex = new JsrcException(1, "test");
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void jsrcException_preservesExitCodeAndMessage() {
        var ex = new JsrcException(42, "something went wrong");
        assertEquals(42, ex.exitCode());
        assertEquals("something went wrong", ex.getMessage());
    }

    @Test
    void jsrcException_preservesCause() {
        var cause = new RuntimeException("root cause");
        var ex = new JsrcException(3, "wrapped", cause);
        assertEquals(3, ex.exitCode());
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    // -- BadUsageException --

    @Test
    void badUsage_exitCodeIsAlways2() {
        var ex = new BadUsageException("invalid arg");
        assertEquals(2, ex.exitCode());
        assertEquals("invalid arg", ex.getMessage());
    }

    @Test
    void badUsage_isJsrcException() {
        assertInstanceOf(JsrcException.class, new BadUsageException("x"));
    }

    // -- JsrcIOException --

    @Test
    void jsrcIO_exitCodeIsAlways3() {
        var cause = new java.io.IOException("disk full");
        var ex = new JsrcIOException("cannot write index", cause);
        assertEquals(3, ex.exitCode());
        assertEquals("cannot write index", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void jsrcIO_withoutCause() {
        var ex = new JsrcIOException("file not found");
        assertEquals(3, ex.exitCode());
        assertNull(ex.getCause());
    }

    @Test
    void jsrcIO_isJsrcException() {
        assertInstanceOf(JsrcException.class, new JsrcIOException("x"));
    }

    // -- ParseFailedException --

    @Test
    void parseFailed_exitCodeIs3_includesPath() {
        var path = Path.of("src/main/java/Bad.java");
        var ex = new ParseFailedException("syntax error", path);
        assertEquals(3, ex.exitCode());
        assertEquals(path, ex.file());
        assertTrue(ex.getMessage().contains("syntax error"));
    }

    @Test
    void parseFailed_isJsrcException() {
        assertInstanceOf(JsrcException.class,
                new ParseFailedException("x", Path.of("f.java")));
    }

    // -- NotFoundException --

    @Test
    void notFound_exitCodeIs1() {
        var ex = new NotFoundException("class OrderService not found");
        assertEquals(1, ex.exitCode());
        assertEquals("class OrderService not found", ex.getMessage());
    }

    @Test
    void notFound_isJsrcException() {
        assertInstanceOf(JsrcException.class, new NotFoundException("x"));
    }
}
