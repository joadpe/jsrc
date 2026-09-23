package com.jsrc.app.index;

import java.util.List;

/**
 * Represents a method call edge in the call graph index.
 *
 * @param callerClass  class containing the calling method
 * @param callerMethod name of the calling method
 * @param calleeClass  class containing the called method (or "?" if unresolved)
 * @param calleeMethod name of the called method
 * @param line         source line of the call
 */
public record CallEdge(
        String callerClass,
        String callerMethod,
        List<String> callerParameterTypes,
        int callerParamCount,
        String calleeClass,
        String calleeMethod,
        List<String> calleeParameterTypes,
        int line,
        int argCount
) {
    public static final String UNKNOWN_PARAMETER_TYPE = "?";

    public CallEdge {
        callerParameterTypes = List.copyOf(callerParameterTypes);
        calleeParameterTypes = List.copyOf(calleeParameterTypes);
    }

    /** Backward-compatible constructor without callee parameter types. */
    public CallEdge(String callerClass, String callerMethod,
                    List<String> callerParameterTypes, int callerParamCount,
                    String calleeClass, String calleeMethod, int line, int argCount) {
        this(callerClass, callerMethod, callerParameterTypes, callerParamCount,
                calleeClass, calleeMethod, List.of(), line, argCount);
    }

    /** Backward-compatible constructor with caller arity but without parameter types. */
    public CallEdge(String callerClass, String callerMethod, int callerParamCount,
                    String calleeClass, String calleeMethod, int line, int argCount) {
        this(callerClass, callerMethod, List.of(), callerParamCount,
                calleeClass, calleeMethod, List.of(), line, argCount);
    }

    /** Backward-compatible constructor without callerParamCount and argCount. */
    public CallEdge(String callerClass, String callerMethod,
                    String calleeClass, String calleeMethod, int line) {
        this(callerClass, callerMethod, -1, calleeClass, calleeMethod, line, -1);
    }

    /** Constructor without callerParamCount. */
    public CallEdge(String callerClass, String callerMethod,
                    String calleeClass, String calleeMethod, int line, int argCount) {
        this(callerClass, callerMethod, -1, calleeClass, calleeMethod, line, argCount);
    }
}
