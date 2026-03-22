package com.jsrc.app.command;

import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.PatternDetector;
import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Calculates technical debt score per class and ranks them.
 * Aggregates data from smells, complexity, perf, coupling, and test coverage.
 */
public class DebtCommand implements Command {

    private final boolean rank;

    public DebtCommand(boolean rank) {
        this.rank = rank;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        CallGraph graph = ctx.callGraph();

        List<Map<String, Object>> scores = new ArrayList<>();

        for (ClassInfo ci : allClasses) {
            // Skip test classes
            if (CommandContext.isTestPath(ci.qualifiedName().replace('.', '/'))
                    || ci.name().endsWith("Test") || ci.name().endsWith("Tests")) continue;

            int smellCount = countSmells(ci, ctx);
            int avgComplexity = estimateComplexity(ci);
            int perfIssues = countPerfIssues(ci, ctx);
            int coupling = countCallers(ci, graph);
            int loc = ci.methods().stream().mapToInt(m -> Math.max(0, m.endLine() - m.startLine())).sum();
            boolean hasCoverage = hasTestCoverage(ci, allClasses);

            // Score formula
            int score = smellCount * 2
                    + avgComplexity * 1
                    + perfIssues * 5
                    + Math.min(coupling, 50) * 1
                    + (hasCoverage ? 0 : 15)
                    + Math.max(0, (loc - 500)) / 10;

            if (score > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", ci.qualifiedName());
                entry.put("score", Math.min(score, 100));
                entry.put("breakdown", Map.of(
                        "smells", smellCount,
                        "complexity", avgComplexity,
                        "perfIssues", perfIssues,
                        "coupling", coupling,
                        "loc", loc,
                        "hasTests", hasCoverage
                ));
                scores.add(entry);
            }
        }

        // Sort by score descending
        scores.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));

        // Calculate overall
        int totalScore = scores.isEmpty() ? 0 : (int) scores.stream()
                .mapToInt(s -> (int) s.get("score")).average().orElse(0);
        String grade = scoreToGrade(totalScore);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", totalScore);
        result.put("grade", grade);
        result.put("classesAnalyzed", scores.size());

        if (rank) {
            result.put("topClasses", scores.size() > 20 ? scores.subList(0, 20) : scores);
        }

        // Category breakdown
        int perfTotal = scores.stream().mapToInt(s -> {
            @SuppressWarnings("unchecked")
            var b = (Map<String, Object>) s.get("breakdown");
            return (int) b.get("perfIssues");
        }).sum();
        int smellTotal = scores.stream().mapToInt(s -> {
            @SuppressWarnings("unchecked")
            var b = (Map<String, Object>) s.get("breakdown");
            return (int) b.get("smells");
        }).sum();
        long untestedCount = scores.stream().filter(s -> {
            @SuppressWarnings("unchecked")
            var b = (Map<String, Object>) s.get("breakdown");
            return !(boolean) b.get("hasTests");
        }).count();

        result.put("byCategory", Map.of(
                "performance", perfTotal,
                "maintainability", smellTotal,
                "testability", untestedCount
        ));

        ctx.formatter().printResult(result);
        return scores.size();
    }

    private int countSmells(ClassInfo ci, CommandContext ctx) {
        // Use cached smells from index if available
        if (ctx.indexed() != null && ctx.indexed().hasCachedSmells()) {
            var filePath = ctx.indexed().findFileForClass(ci.name());
            if (filePath.isPresent()) {
                return ctx.indexed().getCachedSmells(filePath.get()).size();
            }
        }
        return 0;
    }

    private int estimateComplexity(ClassInfo ci) {
        // Use method count as rough proxy (no cyclomatic in ClassInfo)
        return Math.min(ci.methods().size(), 30);
    }

    private int countPerfIssues(ClassInfo ci, CommandContext ctx) {
        String source = SourceResolver.loadClassSource(ci.name(), ctx);
        if (source == null) return 0;
        int count = 0;
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            for (var p : PerfCommand.PERF_PATTERNS) {
                if (p.detector().test(trimmed)) { count++; break; }
            }
        }
        return Math.min(count, 20); // cap
    }

    private int countCallers(ClassInfo ci, CallGraph graph) {
        int total = 0;
        for (var mi : ci.methods()) {
            for (MethodReference ref : graph.findMethodsByName(mi.name())) {
                if (ref.className().equals(ci.name())) {
                    total += graph.getCallersOf(ref).size();
                }
            }
        }
        return total;
    }

    private boolean hasTestCoverage(ClassInfo ci, List<ClassInfo> allClasses) {
        String testName = ci.name() + "Test";
        String testName2 = ci.name() + "Tests";
        return allClasses.stream().anyMatch(c ->
                c.name().equals(testName) || c.name().equals(testName2));
    }

    private String scoreToGrade(int score) {
        if (score < 10) return "A";
        if (score < 25) return "B";
        if (score < 50) return "C";
        if (score < 75) return "D";
        return "F";
    }
}
