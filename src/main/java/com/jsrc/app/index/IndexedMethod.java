package com.jsrc.app.index;

import java.util.List;

/**
 * Compact method metadata stored in the index.
 *
 * @param name        method name
 * @param signature   full signature string
 * @param startLine   start line
 * @param endLine     end line
 * @param returnType  return type
 * @param annotations annotation names on this method
 * @param complexity  estimated cyclomatic complexity (LOC / 5, min 1). -1 if not computed.
 * @param paramCount  number of parameters (-1 if not computed)
 */
public record IndexedMethod(
        String name,
        String signature,
        int startLine,
        int endLine,
        String returnType,
        List<String> annotations,
        int complexity,
        int paramCount
) {
    /** Backward-compatible constructor without precomputed fields. */
    public IndexedMethod(String name, String signature, int startLine, int endLine,
                         String returnType, List<String> annotations) {
        this(name, signature, startLine, endLine, returnType, annotations,
                computeComplexity(startLine, endLine), countParams(signature));
    }

    private static int computeComplexity(int startLine, int endLine) {
        int loc = endLine - startLine + 1;
        return loc <= 0 ? 1 : Math.max(1, loc / 5);
    }

    private static int countParams(String signature) {
        if (signature == null) return 0;
        int parenStart = signature.indexOf('(');
        int parenEnd = signature.lastIndexOf(')');
        if (parenStart < 0 || parenEnd <= parenStart + 1) return 0;
        String params = signature.substring(parenStart + 1, parenEnd).trim();
        if (params.isEmpty()) return 0;
        // Count commas outside of generics
        int count = 1;
        int depth = 0;
        for (char c : params.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }
}
