package com.jsrc.app.command;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deep analysis of codebase conventions: logging, injection, naming,
 * annotations, null handling, error handling, collections.
 * More detailed than --style, ~800 chars output.
 */
public class PatternsCommand implements Command {

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        if (allClasses.isEmpty()) {
            ctx.formatter().printResult(Map.of("error", "No classes found"));
            return 0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        int totalClasses = allClasses.size();

        // === Logging ===
        Map<String, Integer> loggingCounts = new HashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String imp : ic.imports()) {
                        if (imp.contains("slf4j")) loggingCounts.merge("SLF4J", 1, Integer::sum);
                        else if (imp.contains("log4j")) loggingCounts.merge("Log4j", 1, Integer::sum);
                        else if (imp.contains("java.util.logging")) loggingCounts.merge("JUL", 1, Integer::sum);
                    }
                }
            }
        }
        String topLogging = loggingCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " (" + percent(e.getValue(), totalClasses) + ")")
                .orElse("none detected");
        result.put("logging", topLogging);

        // === Injection ===
        int ctorInjection = 0, fieldInjection = 0;
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    boolean hasCtor = ic.methods().stream()
                            .anyMatch(m -> m.name().equals(ic.name()) && m.signature() != null
                                    && m.signature().contains(","));
                    if (hasCtor) ctorInjection++;
                    if (ic.annotations().stream().anyMatch(a -> a.contains("Autowired") || a.contains("Inject")))
                        fieldInjection++;
                }
            }
        }
        Map<String, Object> injection = new LinkedHashMap<>();
        injection.put("constructor", percent(ctorInjection, totalClasses));
        injection.put("field", percent(fieldInjection, totalClasses));
        result.put("injection", injection);

        // === Naming conventions ===
        Map<String, Integer> classSuffixes = new HashMap<>();
        Map<String, Integer> methodPrefixes = new HashMap<>();
        for (var ci : allClasses) {
            // Class suffixes
            String name = ci.name();
            for (String s : new String[]{"Service", "Repository", "Controller", "Handler",
                    "Factory", "Builder", "Config", "Exception", "Listener", "Adapter"}) {
                if (name.endsWith(s) && name.length() > s.length()) {
                    classSuffixes.merge(s, 1, Integer::sum);
                }
            }
            // Method prefixes
            for (var m : ci.methods()) {
                for (String p : new String[]{"get", "set", "is", "has", "find", "create",
                        "delete", "update", "add", "remove"}) {
                    if (m.name().startsWith(p) && m.name().length() > p.length()
                            && Character.isUpperCase(m.name().charAt(p.length()))) {
                        methodPrefixes.merge(p, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Object> naming = new LinkedHashMap<>();
        naming.put("classSuffixes", topN(classSuffixes, 5));
        naming.put("methodPrefixes", topN(methodPrefixes, 5));
        result.put("naming", naming);

        // === Annotations top-10 ===
        Map<String, Integer> annotationCounts = new HashMap<>();
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (String ann : ic.annotations()) {
                        annotationCounts.merge("@" + ann, 1, Integer::sum);
                    }
                    for (var m : ic.methods()) {
                        for (String ann : m.annotations()) {
                            annotationCounts.merge("@" + ann, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        result.put("annotations", topN(annotationCounts, 10));

        // === Null handling ===
        int requireNonNull = 0, optionalReturns = 0;
        for (var ci : allClasses) {
            for (var m : ci.methods()) {
                if (m.returnType() != null && m.returnType().startsWith("Optional")) optionalReturns++;
            }
        }
        if (ctx.indexed() != null) {
            for (var entry : ctx.indexed().getEntries()) {
                for (var ic : entry.classes()) {
                    for (var m : ic.methods()) {
                        if (m.signature() != null && m.signature().contains("requireNonNull"))
                            requireNonNull++;
                    }
                }
            }
        }
        Map<String, Object> nullHandling = new LinkedHashMap<>();
        nullHandling.put("requireNonNull", requireNonNull);
        nullHandling.put("optionalReturns", optionalReturns);
        result.put("nullHandling", nullHandling);

        // === Error handling ===
        long customExceptions = allClasses.stream()
                .filter(ci -> ci.superClass().contains("Exception")
                        || ci.superClass().contains("RuntimeException"))
                .count();
        result.put("errorHandling", Map.of("customExceptions", customExceptions));

        result.put("totalClasses", totalClasses);

        ctx.formatter().printResult(result);
        return 1;
    }

    private String percent(int count, int total) {
        if (total == 0) return "0%";
        return Math.round(count * 100.0 / total) + "%";
    }

    private Map<String, Integer> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
