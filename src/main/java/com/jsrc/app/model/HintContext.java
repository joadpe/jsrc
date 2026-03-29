package com.jsrc.app.model;

import java.util.List;

/**
 * Context data for resolving CommandHint templates.
 * Factory methods create contexts for different command result types.
 *
 * @param className       current class name (null if not applicable)
 * @param methodName      current method name (null if not applicable)
 * @param topClass        first top class from overview (null if not applicable)
 * @param firstMatch      first match from search/find (null if not applicable)
 * @param callerClass     class of a caller (null if not applicable)
 * @param callerMethod    method of a caller (null if not applicable)
 * @param calleeClass     class of a callee (null if not applicable)
 * @param calleeMethod    method of a callee (null if not applicable)
 * @param siblingMethods  other methods in the same class (empty if none)
 * @param topClasses      top classes from overview (empty if none)
 * @param packages        packages list (empty if none)
 * @param matches         search/find matches (empty if none)
 */
public record HintContext(
        String className,
        String methodName,
        String topClass,
        String firstMatch,
        String callerClass,
        String callerMethod,
        String calleeClass,
        String calleeMethod,
        List<String> siblingMethods,
        List<String> topClasses,
        List<String> packages,
        List<String> matches
) {

    /** Context for a method read result. */
    public static HintContext forMethod(String className, String methodName,
                                         List<String> siblingMethods) {
        return new HintContext(className, methodName, null, null, null, null, null, null,
                siblingMethods, List.of(), List.of(), List.of());
    }

    /** Context for a class read result. */
    public static HintContext forClass(String className, List<String> methods) {
        return new HintContext(className, null, null, null, null, null, null, null,
                methods, List.of(), List.of(), List.of());
    }

    /** Context for overview result. */
    public static HintContext forOverview(List<String> topClasses, List<String> packages) {
        String top = topClasses.isEmpty() ? null : topClasses.getFirst();
        return new HintContext(null, null, top, null, null, null, null, null,
                List.of(), topClasses, packages, List.of());
    }

    /** Context for callers result. */
    public static HintContext forCallers(String methodName, String className,
                                          String callerClass, String callerMethod) {
        return new HintContext(className, methodName, null, null,
                callerClass, callerMethod, null, null,
                List.of(), List.of(), List.of(), List.of());
    }

    /** Context for callees result. */
    public static HintContext forCallees(String methodName, String className,
                                          String calleeClass, String calleeMethod) {
        return new HintContext(className, methodName, null, null, null, null,
                calleeClass, calleeMethod,
                List.of(), List.of(), List.of(), List.of());
    }

    /** Context for search/find result. */
    public static HintContext forSearch(String query, List<String> matches) {
        String first = matches.isEmpty() ? null : matches.getFirst();
        return new HintContext(null, null, null, first, null, null, null, null,
                List.of(), List.of(), List.of(), matches);
    }
}
