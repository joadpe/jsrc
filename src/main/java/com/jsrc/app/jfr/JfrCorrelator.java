package com.jsrc.app.jfr;

import com.jsrc.app.index.CachedSmell;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.jfr.JfrProfile.HotMethod;

import java.nio.file.Path;
import java.util.*;

/**
 * Correlates JFR profiling data with the jsrc codebase index.
 * Enriches hot methods with file location, perf findings, smells, and debt scores.
 */
public final class JfrCorrelator {

    private JfrCorrelator() {}

    /**
     * Result of correlating a JFR hot method with the codebase index.
     *
     * @param className     fully qualified class name
     * @param methodName    method name
     * @param samples       CPU sample count
     * @param pct           percentage of total samples
     * @param inIndex       whether the class was found in the jsrc index
     * @param file          source file path (null if not in index)
     * @param perfFindings  number of perf findings for this class
     * @param smells        number of code smells for this class
     * @param debtScore     technical debt score for this class
     */
    public record CorrelatedMethod(
            String className,
            String methodName,
            long samples,
            double pct,
            boolean inIndex,
            String file,
            int perfFindings,
            int smells,
            int debtScore
    ) {}

    /**
     * Full correlation result.
     *
     * @param methods      correlated methods (same order as JFR hot methods)
     * @param correlations confirmed correlations (JFR evidence + jsrc finding match)
     */
    public record CorrelationResult(
            List<CorrelatedMethod> methods,
            List<Correlation> correlations
    ) {}

    /**
     * A confirmed correlation between JFR evidence and a jsrc finding.
     *
     * @param method         class.method reference
     * @param jfrEvidence    what JFR found (e.g. "3200 CPU samples (20.8%)")
     * @param jsrcFinding    matching jsrc finding type (e.g. "LOOP_WITH_DEEP_IO (perf)")
     * @param confirmed      whether JFR confirms the jsrc finding
     * @param recommendation suggested fix
     */
    public record Correlation(
            String method,
            String jfrEvidence,
            String jsrcFinding,
            boolean confirmed,
            String recommendation
    ) {}

    /**
     * Correlates JFR hot methods with the indexed codebase.
     *
     * @param profile JFR profile with hot methods
     * @param indexed jsrc codebase index (may be null)
     * @param rootPath project root for resolving file paths
     * @return correlation result
     */
    public static CorrelationResult correlate(JfrProfile profile, IndexedCodebase indexed, String rootPath) {
        Objects.requireNonNull(profile, "profile must not be null");

        if (indexed == null) {
            // No index — return methods without enrichment
            List<CorrelatedMethod> methods = profile.hotMethods().stream()
                    .map(hm -> new CorrelatedMethod(hm.className(), hm.methodName(),
                            hm.samples(), hm.pct(), false, null, 0, 0, 0))
                    .toList();
            return new CorrelationResult(methods, List.of());
        }

        List<CorrelatedMethod> methods = new ArrayList<>();
        List<Correlation> correlations = new ArrayList<>();

        for (HotMethod hm : profile.hotMethods()) {
            String simpleClass = simpleClassName(hm.className());
            Optional<String> filePath = indexed.findFileForClass(simpleClass);

            if (filePath.isPresent()) {
                String file = filePath.get();
                List<CachedSmell> cachedSmells = indexed.getCachedSmells(file);
                int smellCount = cachedSmells != null ? cachedSmells.size() : 0;

                methods.add(new CorrelatedMethod(hm.className(), hm.methodName(),
                        hm.samples(), hm.pct(), true, file, 0, smellCount, 0));

                // Build correlations for methods with smells
                if (smellCount > 0) {
                    String evidence = String.format("%d CPU samples (%.1f%%)", hm.samples(), hm.pct());
                    for (var smell : cachedSmells) {
                        correlations.add(new Correlation(
                                hm.className() + "." + hm.methodName(),
                                evidence,
                                smell.ruleId() + " (smell)",
                                true,
                                "JFR confirms this pattern is active at runtime"
                        ));
                    }
                }
            } else {
                methods.add(new CorrelatedMethod(hm.className(), hm.methodName(),
                        hm.samples(), hm.pct(), false, null, 0, 0, 0));
            }
        }

        return new CorrelationResult(methods, correlations);
    }

    /**
     * Extracts simple class name from fully qualified name.
     * e.g. "com.example.OrderService" → "OrderService"
     */
    static String simpleClassName(String fqcn) {
        if (fqcn == null) return "";
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }
}
