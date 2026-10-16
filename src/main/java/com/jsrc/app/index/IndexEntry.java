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
 * @param smells       precomputed code smells (empty if none or not yet computed)
 * @param smellsComputed whether smells were computed for this content; when true an
 *                     empty {@code smells} list means the file has none
 * @param invokerFingerprint fingerprint of the invoker configuration the call edges were
 *                     extracted with; empty when no invoker was configured
 */
public record IndexEntry(
        String path,
        String contentHash,
        long lastModified,
        com.jsrc.app.project.SourceSet sourceSet,
        List<IndexedClass> classes,
        List<CallEdge> callEdges,
        List<CachedSmell> smells,
        int sourceVersion,
        String declarationFingerprint,
        boolean smellsComputed,
        String invokerFingerprint
) {
    public IndexEntry {
        declarationFingerprint = declarationFingerprint == null ? "" : declarationFingerprint;
        smellsComputed = smellsComputed || !smells.isEmpty();
        invokerFingerprint = invokerFingerprint == null ? "" : invokerFingerprint;
    }

    /** Creates an entry whose call edges were extracted without configured invokers. */
    public IndexEntry(String path, String contentHash, long lastModified,
                      com.jsrc.app.project.SourceSet sourceSet, List<IndexedClass> classes,
                      List<CallEdge> callEdges, List<CachedSmell> smells, int sourceVersion,
                      String declarationFingerprint, boolean smellsComputed) {
        this(path, contentHash, lastModified, sourceSet, classes, callEdges, smells,
                sourceVersion, declarationFingerprint, smellsComputed, "");
    }

    /** Creates an entry whose smells count as computed only when the list is non-empty. */
    public IndexEntry(String path, String contentHash, long lastModified,
                      com.jsrc.app.project.SourceSet sourceSet, List<IndexedClass> classes,
                      List<CallEdge> callEdges, List<CachedSmell> smells, int sourceVersion,
                      String declarationFingerprint) {
        this(path, contentHash, lastModified, sourceSet, classes, callEdges, smells,
                sourceVersion, declarationFingerprint, false);
    }

    public IndexEntry(String path, String contentHash, long lastModified,
                      com.jsrc.app.project.SourceSet sourceSet, List<IndexedClass> classes,
                      List<CallEdge> callEdges, List<CachedSmell> smells, int sourceVersion) {
        this(path, contentHash, lastModified, sourceSet, classes, callEdges, smells,
                sourceVersion, "");
    }
    public IndexEntry(String path, String contentHash, long lastModified,
                      com.jsrc.app.project.SourceSet sourceSet,
                      List<IndexedClass> classes, List<CallEdge> callEdges,
                      List<CachedSmell> smells) {
        this(path, contentHash, lastModified, sourceSet, classes, callEdges, smells, -1);
    }
    /** Backward-compatible constructor for entries without call edges or smells. */
    public IndexEntry(String path, String contentHash, long lastModified, List<IndexedClass> classes) {
        this(path, contentHash, lastModified, com.jsrc.app.project.SourceSet.UNKNOWN,
                classes, List.of(), List.of());
    }

    /** Backward-compatible constructor for entries without smells. */
    public IndexEntry(String path, String contentHash, long lastModified,
                      List<IndexedClass> classes, List<CallEdge> callEdges) {
        this(path, contentHash, lastModified, com.jsrc.app.project.SourceSet.UNKNOWN,
                classes, callEdges, List.of());
    }

    /** Backward-compatible constructor for callers without source-set metadata. */
    public IndexEntry(String path, String contentHash, long lastModified,
                      List<IndexedClass> classes, List<CallEdge> callEdges,
                      List<CachedSmell> smells) {
        this(path, contentHash, lastModified, com.jsrc.app.project.SourceSet.UNKNOWN,
                classes, callEdges, smells);
    }

    /** Returns a copy with different call edges. */
    public IndexEntry withEdges(List<CallEdge> newEdges) {
        return withEdges(newEdges, invokerFingerprint);
    }

    /** Returns a copy with call edges extracted with the given invoker configuration. */
    public IndexEntry withEdges(List<CallEdge> newEdges, String newInvokerFingerprint) {
        return new IndexEntry(
                path, contentHash, lastModified, sourceSet, classes, newEdges, smells,
                sourceVersion, declarationFingerprint, smellsComputed, newInvokerFingerprint);
    }

    /** Returns a copy with computed smells; an empty list records a file without smells. */
    public IndexEntry withSmells(List<CachedSmell> newSmells) {
        return new IndexEntry(
                path, contentHash, lastModified, sourceSet, classes, callEdges, newSmells,
                sourceVersion, declarationFingerprint, true, invokerFingerprint);
    }
}
