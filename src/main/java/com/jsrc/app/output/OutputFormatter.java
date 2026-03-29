package com.jsrc.app.output;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.jsrc.app.model.AnnotationMatch;
import com.jsrc.app.model.DependencyResult;
import com.jsrc.app.model.HierarchyResult;
import com.jsrc.app.model.OverviewResult;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Contract for formatting CLI output.
 * Implementations decide the presentation format (text, JSON, etc.)
 * while the business logic remains format-agnostic.
 */
public interface OutputFormatter {

    /**
     * Prints method search results.
     *
     * @param methods    found methods
     * @param file       source file path
     * @param methodName searched method name
     */
    void printMethods(List<MethodInfo> methods, Path file, String methodName);

    /**
     * Prints code smell findings for a file.
     *
     * @param smells detected smells
     * @param file   source file path
     */
    void printSmells(List<CodeSmell> smells, Path file);

    /**
     * Prints smells with appended hints.
     */
    default void printSmells(List<CodeSmell> smells, Path file,
                              java.util.List<com.jsrc.app.model.CommandHint> hints) {
        printSmells(smells, file);
    }

    /**
     * Prints class listing results.
     *
     * @param classes discovered classes
     * @param sourceRoot source root for relative paths
     */
    void printClasses(List<ClassInfo> classes, Path sourceRoot);

    /**
     * Prints architecture check results (violations).
     */
    void printViolations(List<com.jsrc.app.architecture.Violation> violations);

    /**
     * Prints diff results (changed files since last index).
     */
    void printDiff(List<String> modified, List<String> added, List<String> deleted);

    /**
     * Prints caller/callee results.
     *
     * @param refs   list of method references (callers or callees)
     * @param label  "callers" or "callees"
     * @param target the queried method name
     */
    void printRefs(List<Map<String, Object>> refs, String label, String target);

    /**
     * Prints source read result.
     *
     * @param result read result with source content
     */
    void printReadResult(com.jsrc.app.parser.SourceReader.ReadResult result);

    /**
     * Prints codebase overview.
     *
     * @param result overview data
     */
    void printOverview(OverviewResult result);

    /**
     * Print overview with explicit package count (for compact mode where list is omitted).
     */
    default void printOverview(OverviewResult result, int packageCount) {
        printOverview(result);
    }

    /**
     * Print overview with package count and top classes for navigation.
     */
    default void printOverview(OverviewResult result, int packageCount, List<String> topClasses) {
        printOverview(result, packageCount);
    }

    /**
     * Prints class dependency results.
     *
     * @param result dependency data for the queried class
     */
    void printDependencies(DependencyResult result);

    /**
     * Prints class hierarchy results.
     *
     * @param result hierarchy data for the queried class
     */
    void printHierarchy(HierarchyResult result);

    /**
     * Prints annotation search results.
     *
     * @param matches found annotation matches
     */
    void printAnnotationMatches(List<AnnotationMatch> matches);

    /**
     * Prints a compact summary of a single class: metadata + method signatures.
     *
     * @param classInfo the class to summarize
     * @param file      source file path
     */
    void printClassSummary(ClassInfo classInfo, Path file);

    /** Summary with visibility breakdown. Default delegates to base method. */
    default void printClassSummary(ClassInfo classInfo, Path file,
                                     java.util.List<com.jsrc.app.model.CommandHint> hints) {
        printClassSummary(classInfo, file);
    }

    default void printClassSummary(ClassInfo classInfo, Path file,
                                   long publicMethods, long protectedMethods, long privateMethods) {
        printClassSummary(classInfo, file);
    }

    /**
     * Prints call chain analysis results.
     *
     * @param output call chain output with chains, method name, signatures, dead-end roots
     */
    void printCallChains(com.jsrc.app.model.CallChainOutput output);

    /**
     * Prints a generic result (Map or List) with field filtering applied.
     * Use this for commands that build ad-hoc result structures.
     *
     * @param data a Map or List to output
     */
    void printResult(Object data);

    /**
     * Prints result data with appended nextCommands hints.
     * Default: delegates to printResult (non-JSON formatters ignore hints).
     */
    default void printResultWithHints(Object data, java.util.List<com.jsrc.app.model.CommandHint> hints) {
        printResult(data);
    }

    /**
     * Factory method to create the appropriate formatter.
     *
     * @param json true for JSON output, false for human-readable text
     * @return formatter instance
     */
    static OutputFormatter create(boolean json) {
        return create(json, false);
    }

    /**
     * Factory method with signature-only option.
     */
    static OutputFormatter create(boolean json, boolean signatureOnly) {
        return create(json, signatureOnly, null);
    }

    /**
     * Factory method with signature-only and fields options.
     *
     * @param json          true for JSON output, false for human-readable text
     * @param signatureOnly true to emit only method signatures
     * @param fields        set of field names to include in JSON (null = all)
     * @return formatter instance
     */
    static OutputFormatter create(boolean json, boolean signatureOnly, java.util.Set<String> fields) {
        return json ? new JsonFormatter(signatureOnly, fields) : new TextFormatter(signatureOnly);
    }

    /**
     * Factory method with injected output stream.
     *
     * @param json          true for JSON output, false for human-readable text
     * @param signatureOnly true to emit only method signatures
     * @param fields        set of field names to include in JSON (null = all)
     * @param out           output stream to write to
     * @return formatter instance
     */
    static OutputFormatter create(boolean json, boolean signatureOnly, java.util.Set<String> fields,
                                   java.io.PrintStream out) {
        return json ? new JsonFormatter(signatureOnly, fields, out) : new TextFormatter(signatureOnly, out);
    }
}
