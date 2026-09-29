package com.jsrc.app.engine;

import com.jsrc.app.model.InvocationKind;
import com.jsrc.app.model.ResolutionLevel;
import java.util.List;

/** Call graph query data, including ambiguity and resolution evidence. */
public record CallersResult(
        Status status,
        String method,
        List<String> candidates,
        List<Caller> callers) {
    public CallersResult {
        candidates = List.copyOf(candidates);
        callers = List.copyOf(callers);
    }

    public enum Status { FOUND, AMBIGUOUS, NOT_FOUND }

    public sealed interface Caller permits DirectCaller, ReflectiveCaller {
        String className();
        String method();
        int line();
    }

    public record DirectCaller(
            String className,
            String method,
            String signature,
            int line,
            InvocationKind dispatch,
            ResolutionLevel resolution,
            List<String> evidence) implements Caller {
        public DirectCaller {
            evidence = List.copyOf(evidence);
        }
    }

    public record ReflectiveCaller(
            String className,
            String method,
            int line,
            String targetClass) implements Caller {}
}
