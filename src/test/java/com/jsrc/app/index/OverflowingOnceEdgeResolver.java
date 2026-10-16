package com.jsrc.app.index;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

/** Overflows the stack the first time it extracts the edges of one file, on whichever thread. */
final class OverflowingOnceEdgeResolver extends EdgeResolver {
    private final AtomicBoolean overflowed;
    private final String fileName;

    OverflowingOnceEdgeResolver(AtomicBoolean overflowed, String fileName) {
        this.overflowed = overflowed;
        this.fileName = fileName;
    }

    @Override
    public Extraction extractEdges(Path file, ParseResult<CompilationUnit> result) {
        if (file.getFileName().toString().equals(fileName) && overflowed.compareAndSet(false, true)) {
            throw new StackOverflowError("simulated deeply nested source");
        }
        return super.extractEdges(file, result);
    }
}
