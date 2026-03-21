package com.jsrc.app.index;

import java.util.List;

/**
 * Index entry for a single source file.
 *
 * @param path         relative file path from source root
 * @param contentHash  SHA-256 hash of file content for invalidation
 * @param lastModified file last modified timestamp (epoch millis)
 * @param classes      class metadata indexed from this file
 * @param callEdges    call edges extracted from this file
 * @param smells       precomputed code smells (empty if not yet computed)
 */
public record IndexEntry(
        String path,
        String contentHash,
        long lastModified,
        List<IndexedClass> classes,
        List<CallEdge> callEdges,
        List<CachedSmell> smells
) {
    /** Backward-compatible constructor for entries without call edges or smells. */
    public IndexEntry(String path, String contentHash, long lastModified, List<IndexedClass> classes) {
        this(path, contentHash, lastModified, classes, List.of(), List.of());
    }

    /** Backward-compatible constructor for entries without smells. */
    public IndexEntry(String path, String contentHash, long lastModified,
                      List<IndexedClass> classes, List<CallEdge> callEdges) {
        this(path, contentHash, lastModified, classes, callEdges, List.of());
    }
}
