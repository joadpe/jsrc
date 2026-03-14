package com.jsrc.app.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Provides ClassInfo and MethodInfo from a persisted index,
 * avoiding full source re-parsing. Falls back to null if no index exists.
 */
public class IndexedCodebase {

    private static final Logger logger = LoggerFactory.getLogger(IndexedCodebase.class);

    private final List<IndexEntry> entries;
    private List<ClassInfo> allClasses;
    private Map<String, List<ClassInfo>> classesByFile;

    private IndexedCodebase(List<IndexEntry> entries) {
        this.entries = entries;
    }

    /**
     * Tries to load an indexed codebase from disk.
     *
     * @param sourceRoot project root where .jsrc/index.json lives
     * @return IndexedCodebase if index exists and has entries, null otherwise
     */
    public static IndexedCodebase tryLoad(Path sourceRoot) {
        List<IndexEntry> entries = CodebaseIndex.load(sourceRoot);
        if (entries.isEmpty()) {
            return null;
        }
        logger.info("Using index with {} file entries", entries.size());
        return new IndexedCodebase(entries);
    }

    /**
     * Returns all classes from the index, converted to ClassInfo.
     */
    public List<ClassInfo> getAllClasses() {
        if (allClasses == null) {
            allClasses = new ArrayList<>();
            for (IndexEntry entry : entries) {
                for (IndexedClass ic : entry.classes()) {
                    allClasses.add(toClassInfo(ic));
                }
            }
        }
        return allClasses;
    }

    /**
     * Returns the file path (relative) for a given class name.
     */
    public String findFileForClass(String className) {
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                if (ic.name().equals(className) || ic.qualifiedName().equals(className)) {
                    return entry.path();
                }
            }
        }
        return null;
    }

    /**
     * Returns all methods matching a name from the index.
     */
    public List<MethodInfo> findMethodsByName(String methodName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.name().equals(methodName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all methods with a given annotation from the index.
     */
    public List<MethodInfo> findMethodsByAnnotation(String annotationName) {
        List<MethodInfo> results = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass ic : entry.classes()) {
                for (IndexedMethod im : ic.methods()) {
                    if (im.annotations().contains(annotationName)) {
                        results.add(toMethodInfo(im, ic.name()));
                    }
                }
            }
        }
        return results;
    }

    /**
     * Returns all classes with a given annotation from the index.
     */
    public List<ClassInfo> findClassesByAnnotation(String annotationName) {
        return getAllClasses().stream()
                .filter(ci -> ci.annotations().stream()
                        .anyMatch(a -> a.name().equals(annotationName)))
                .toList();
    }

    /**
     * Returns the number of indexed files.
     */
    public int fileCount() {
        return entries.size();
    }

    // ---- conversion ----

    private static ClassInfo toClassInfo(IndexedClass ic) {
        List<MethodInfo> methods = ic.methods().stream()
                .map(im -> toMethodInfo(im, ic.name()))
                .toList();

        List<AnnotationInfo> annotations = ic.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        String superClass = ic.superClass().isEmpty() ? "" : ic.superClass().getFirst();

        return new ClassInfo(
                ic.name(), ic.packageName(), ic.startLine(), ic.endLine(),
                List.of(), methods, superClass,
                ic.interfaces(), annotations, ic.isInterface());
    }

    private static MethodInfo toMethodInfo(IndexedMethod im, String className) {
        List<AnnotationInfo> annotations = im.annotations().stream()
                .map(AnnotationInfo::marker)
                .toList();

        return new MethodInfo(
                im.name(), className, im.startLine(), im.endLine(),
                im.returnType(), List.of(), List.of(),
                "", // no content from index
                annotations, List.of(), List.of(), null);
    }
}
