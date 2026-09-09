package com.jsrc.app.output;

import com.jsrc.app.cli.BudgetContext;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * JSON formatter with budget enforcement.
 * Applies limits, truncation, and _budget metadata injection.
 * 
 * Important: Does NOT wrap array roots to preserve JSON contracts.
 * _budget metadata is only injected into object roots.
 */
public class BudgetAwareJsonFormatter extends JsonFormatter {
    
    private final BudgetContext budgetContext;

    public BudgetAwareJsonFormatter(boolean signatureOnly, Set<String> fields, 
                                    PrintStream out, BudgetContext budgetContext) {
        super(signatureOnly, fields, out);
        this.budgetContext = budgetContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void printResult(Object data) {
        Object processed = applyBudgetLimitsAndInjectMeta(data);
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void printResultWithHints(Object data, java.util.List<com.jsrc.app.model.CommandHint> hints) {
        Object processed = applyBudgetLimitsAndInjectMeta(data);
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
        
        // Print hints if provided
        if (hints != null && !hints.isEmpty()) {
            super.printHints(hints);
        }
    }

    @Override
    public void printClasses(List<com.jsrc.app.parser.model.ClassInfo> classes, Path sourceRoot) {
        List<com.jsrc.app.parser.model.ClassInfo> limited = applyListLimit(classes);
        if (limited.size() < classes.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Apply field set filtering for budget profiles
        var fieldSet = com.jsrc.app.cli.BudgetPolicy.getFieldsForProfile("class", budgetContext.profile());
        if (fieldSet != null) {
            budgetContext.addTransform("fields:" + budgetContext.profile().fieldSet());
        }
        
        super.printClasses(limited, sourceRoot);
    }

    @Override
    public void printClassSummary(com.jsrc.app.parser.model.ClassInfo classInfo, Path file) {
        // Apply field set if under budget
        var fieldSet = com.jsrc.app.cli.BudgetPolicy.getFieldsForProfile("class", budgetContext.profile());
        if (fieldSet != null) {
            budgetContext.addTransform("fields:" + budgetContext.profile().fieldSet());
        }
        super.printClassSummary(classInfo, file);
    }

    @Override
    public void printMethods(List<com.jsrc.app.parser.model.MethodInfo> methods, Path file, String methodName) {
        List<com.jsrc.app.parser.model.MethodInfo> limited = applyListLimit(methods);
        if (limited.size() < methods.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Apply field set filtering for methods
        var fieldSet = com.jsrc.app.cli.BudgetPolicy.getFieldsForProfile("method", budgetContext.profile());
        if (fieldSet != null) {
            budgetContext.addTransform("fields:" + budgetContext.profile().fieldSet());
        }
        
        super.printMethods(limited, file, methodName);
    }

    @Override
    public void printRefs(List<Map<String, Object>> refs, String label, String target) {
        List<Map<String, Object>> limited = applyListLimit(refs);
        if (limited.size() < refs.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        super.printRefs(limited, label, target);
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result) {
        super.printOverview(result);
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result, int packageCount) {
        super.printOverview(result, packageCount);
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result, int packageCount, java.util.List<String> topClasses) {
        super.printOverview(result, packageCount, topClasses);
    }

    @Override
    public void printReadResult(com.jsrc.app.parser.SourceReader.ReadResult result) {
        super.printReadResult(result);
    }

    @SuppressWarnings("unchecked")
    private Object applyBudgetLimitsAndInjectMeta(Object data) {
        // Apply limits first
        Object limited = applyLimits(data);
        
        // Inject metadata ONLY into object roots, never wrap arrays
        if (limited instanceof Map<?, ?>) {
            return injectMetadataIntoMap((Map<String, Object>) limited);
        }
        
        // For array roots, do NOT wrap - preserve contract
        return limited;
    }

    @SuppressWarnings("unchecked")
    private Object applyLimits(Object data) {
        if (data instanceof Collection<?> coll) {
            List<?> limited = applyListLimit(new ArrayList<>(coll));
            if (limited.size() < coll.size()) {
                budgetContext.setTruncated(true);
                budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
            }
            return limited;
        } else if (data instanceof Map<?, ?> map) {
            Map<String, Object> mutableMap = new LinkedHashMap<>((Map<String, Object>) map);
            
            // Apply limits to nested lists
            for (Map.Entry<String, Object> entry : mutableMap.entrySet()) {
                if (entry.getValue() instanceof List<?> list) {
                    List<?> limited = applyListLimit(new ArrayList<>(list));
                    if (limited.size() < list.size()) {
                        budgetContext.setTruncated(true);
                        budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
                    }
                    entry.setValue(limited);
                }
            }
            return mutableMap;
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> applyListLimit(List<T> items) {
        int limit = budgetContext.effectiveLimit();
        if (items.size() <= limit) {
            return items;
        }
        return items.subList(0, limit);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> injectMetadataIntoMap(Map<String, Object> map) {
        Map<String, Object> metadata = budgetContext.buildMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return map;
        }

        // Inject _budget at the beginning of the map
        Map<String, Object> withMeta = new LinkedHashMap<>();
        withMeta.put("_budget", metadata);
        withMeta.putAll(map);
        return withMeta;
    }

    /**
     * Apply max-bytes truncation if configured.
     * @param json the JSON string to truncate
     * @return truncated JSON or original if under limit
     */
    private String applyMaxBytes(String json) {
        int maxBytes = budgetContext.effectiveMaxBytes();
        if (maxBytes <= 0 || json.length() <= maxBytes) {
            return json;
        }
        
        budgetContext.setTruncated(true);
        budgetContext.addTransform("max-bytes:" + maxBytes);
        
        // Truncate with ellipsis to indicate truncation
        return json.substring(0, maxBytes - 20) + "...\"truncated\":true}";
    }
}
