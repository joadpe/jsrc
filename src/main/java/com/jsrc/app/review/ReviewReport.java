package com.jsrc.app.review;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.output.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable review evidence; presentation limits are applied only after analysis and ranking. */
public record ReviewReport(String ref, String oid, List<FileReview> files,
                           List<SymbolReview> symbols, List<TestCandidate> tests,
                           List<String> unresolved) {
    public ReviewReport {
        files = List.copyOf(files);
        symbols = List.copyOf(symbols);
        tests = List.copyOf(tests);
        unresolved = List.copyOf(unresolved);
    }

    public record FileReview(String path, String status, String oldPath, boolean javaSource) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("status", status);
            if (oldPath != null) map.put("oldPath", oldPath);
            map.put("analysis", javaSource ? "semantic" : "notApplicable");
            return map;
        }
    }

    public record SymbolReview(String path, String type, String symbol, String change,
                               String before, String after, String contract,
                               String risk, List<String> reasons,
                               List<String> impacted, String confidence) {
        public SymbolReview {
            reasons = List.copyOf(reasons);
            impacted = List.copyOf(impacted);
        }

        Map<String, Object> toMap(boolean compact) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("symbol", symbol);
            map.put("change", change);
            map.put("risk", risk);
            map.put("reasons", reasons);
            if (!compact) {
                map.put("type", type);
                map.put("before", before);
                map.put("after", after);
                map.put("contract", contract);
                map.put("impacted", impacted);
                map.put("confidence", confidence);
            }
            return map;
        }
    }

    public record TestCandidate(String path, String reason, String confidence) {
        Map<String, Object> toMap() {
            return Map.of("path", path, "reason", reason, "confidence", confidence);
        }
    }

    public Map<String, Object> toMap(BudgetContext budget) {
        BudgetProfile profile = budget == null ? BudgetProfile.STANDARD : budget.profile();
        int limit = budget == null ? Integer.MAX_VALUE : Math.max(0, budget.effectiveLimit());
        int maxBytes = budget == null || budget.effectiveMaxBytes() <= 0
                ? Integer.MAX_VALUE : budget.effectiveMaxBytes();
        boolean compact = profile != BudgetProfile.STANDARD;
        List<Map<String, Object>> shownFiles = new ArrayList<>();
        List<Map<String, Object>> shownSymbols = new ArrayList<>();
        List<Map<String, Object>> shownTests = new ArrayList<>();
        List<String> shownUnresolved = new ArrayList<>();
        int fileLimit = Math.min(files.size(), limit);
        int symbolLimit = Math.min(symbols.size(), limit);
        int testLimit = Math.min(tests.size(), limit);
        int unresolvedLimit = Math.min(unresolved.size(), limit);
        for (int i = 0; i < fileLimit; i++) shownFiles.add(files.get(i).toMap());
        for (int i = 0; i < symbolLimit; i++) shownSymbols.add(symbols.get(i).toMap(compact));
        for (int i = 0; i < testLimit; i++) shownTests.add(tests.get(i).toMap());
        for (int i = 0; i < unresolvedLimit; i++) shownUnresolved.add(unresolved.get(i));
        Map<String, Object> output = map(shownFiles, shownSymbols, shownTests, shownUnresolved);
        // Reserve envelope and budget metadata space. The same selection is used for legacy and v1.
        int payloadMax = maxBytes == Integer.MAX_VALUE ? maxBytes : Math.max(0, maxBytes - 650);
        while (size(output) > payloadMax &&
                (!shownTests.isEmpty() || !shownFiles.isEmpty() || !shownSymbols.isEmpty()
                        || !shownUnresolved.isEmpty())) {
            if (!shownTests.isEmpty()) shownTests.removeLast();
            else if (!shownFiles.isEmpty()) shownFiles.removeLast();
            else if (!shownSymbols.isEmpty()) shownSymbols.removeLast();
            else shownUnresolved.removeLast();
            output = map(shownFiles, shownSymbols, shownTests, shownUnresolved);
        }
        if (budget != null && (shownFiles.size() < files.size() || shownSymbols.size() < symbols.size()
                || shownTests.size() < tests.size() || shownUnresolved.size() < unresolved.size())) {
            budget.setTruncated(true);
            budget.addTransform("review:ranked-selection");
        }
        return output;
    }

    /** Renders the same selected evidence as JSON, including omission counts. */
    @SuppressWarnings("unchecked")
    public String toText(BudgetContext budget) {
        Map<String, Object> selected = toMap(budget);
        Map<String, Object> summary = (Map<String, Object>) selected.get("summary");
        Map<String, Object> omitted = (Map<String, Object>) selected.get("omitted");
        StringBuilder text = new StringBuilder();
        text.append("Review ").append(ref).append(" [").append(summary.get("risk"));
        if (Boolean.TRUE.equals(summary.get("partial"))) text.append(", partial");
        text.append("]: ").append(summary.get("changedFiles")).append(" files, ")
                .append(summary.get("changedSymbols")).append(" symbols, ")
                .append(summary.get("suggestedTests")).append(" tests, ")
                .append(summary.get("unresolved")).append(" unresolved\n");
        for (Map<String, Object> file : (List<Map<String, Object>>) selected.get("files")) {
            text.append(file.get("status")).append(' ').append(file.get("path"));
            if (file.containsKey("oldPath")) text.append(" <- ").append(file.get("oldPath"));
            text.append('\n');
        }
        for (Map<String, Object> symbol : (List<Map<String, Object>>) selected.get("symbols")) {
            text.append(symbol.get("change")).append(' ').append(symbol.get("symbol"))
                    .append(" [").append(symbol.get("risk")).append("] — ")
                    .append(String.join("; ", (List<String>) symbol.get("reasons"))).append('\n');
        }
        for (Map<String, Object> test : (List<Map<String, Object>>) selected.get("tests")) {
            text.append("Test ").append(test.get("path")).append(" [")
                    .append(test.get("confidence")).append("]: ")
                    .append(test.get("reason")).append('\n');
        }
        for (String issue : (List<String>) selected.get("unresolved")) {
            text.append("Unresolved: ").append(issue).append('\n');
        }
        text.append("omitted: ").append(omitted.get("files")).append(" files, ")
                .append(omitted.get("symbols")).append(" symbols, ")
                .append(omitted.get("tests")).append(" tests, ")
                .append(omitted.get("unresolved")).append(" unresolved (budget selection)\n");
        int maxBytes = budget == null ? Integer.MAX_VALUE : budget.effectiveMaxBytes();
        if (maxBytes > 0 && text.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            String fallback = "Review details omitted: max-bytes=" + maxBytes + "\n";
            return fallback.getBytes(StandardCharsets.UTF_8).length <= maxBytes ? fallback : "";
        }
        return text.toString();
    }

    private Map<String, Object> map(List<Map<String, Object>> shownFiles,
                                    List<Map<String, Object>> shownSymbols,
                                    List<Map<String, Object>> shownTests,
                                    List<String> shownUnresolved) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changedFiles", files.size());
        summary.put("changedSymbols", symbols.size());
        summary.put("suggestedTests", tests.size());
        summary.put("unresolved", unresolved.size());
        summary.put("risk", symbols.stream().anyMatch(s -> "high".equals(s.risk())) ? "high"
                : !unresolved.isEmpty() || symbols.stream().anyMatch(s -> "unknown".equals(s.risk()))
                ? "unknown" : symbols.stream().anyMatch(s -> "medium".equals(s.risk()))
                ? "medium" : symbols.isEmpty() ? "none" : "low");
        summary.put("partial", !unresolved.isEmpty());
        Map<String, Object> omitted = new LinkedHashMap<>();
        omitted.put("files", files.size() - shownFiles.size());
        omitted.put("symbols", symbols.size() - shownSymbols.size());
        omitted.put("tests", tests.size() - shownTests.size());
        omitted.put("unresolved", unresolved.size() - shownUnresolved.size());
        omitted.put("reason", "budget: ranked selection after full analysis");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ambiguous", !unresolved.isEmpty());
        output.put("ref", ref);
        output.put("oid", oid);
        output.put("summary", summary);
        output.put("files", shownFiles);
        output.put("symbols", shownSymbols);
        output.put("tests", shownTests);
        output.put("unresolved", shownUnresolved);
        output.put("omitted", omitted);
        return output;
    }

    private static int size(Object value) {
        return JsonWriter.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
