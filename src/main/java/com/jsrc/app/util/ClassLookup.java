package com.jsrc.app.util;

import java.util.List;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Shared class resolution utilities.
 * Used by 14+ commands to resolve class names with fuzzy matching.
 * Extracted from SummaryCommand for cross-package reuse.
 */
public class ClassLookup {

    /**
     * Resolves a class by name from a list. Handles ambiguity and not-found with
     * helpful error messages. Returns null if not found or ambiguous.
     */
    public static ClassInfo resolveOrExit(List<ClassInfo> allClasses, String className) {
        var resolution = com.jsrc.app.util.ClassResolver.resolve(allClasses, className);
        return switch (resolution) {
            case com.jsrc.app.util.ClassResolver.Resolution.Found found -> found.classInfo();
            case com.jsrc.app.util.ClassResolver.Resolution.Ambiguous ambiguous -> {
                com.jsrc.app.util.ClassResolver.printAmbiguous(ambiguous.candidates(), className);
                yield null;
            }
            case com.jsrc.app.util.ClassResolver.Resolution.NotFound n -> {
                String suggestion = findClosestClass(allClasses, className);
                StringBuilder msg = new StringBuilder();
                msg.append(String.format("Class '%s' not found in index.", className));
                if (suggestion != null) {
                    msg.append(String.format(" Did you mean '%s'?", suggestion));
                }
                System.err.println(msg);
                yield null;
            }
        };
    }

    /**
     * Finds the closest class name by Levenshtein distance (fuzzy suggest).
     */
    public static String findClosestClass(List<ClassInfo> allClasses, String target) {
        String best = null;
        int bestDist = 4;
        for (var ci : allClasses) {
            int dist = levenshtein(target.toLowerCase(), ci.name().toLowerCase());
            if (dist < bestDist) {
                bestDist = dist;
                best = ci.name();
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                curr[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }
}
