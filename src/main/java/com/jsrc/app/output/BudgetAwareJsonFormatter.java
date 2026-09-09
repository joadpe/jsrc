package com.jsrc.app.output;

import com.jsrc.app.cli.BudgetContext;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JSON formatter with budget enforcement.
 * Applies limits, truncation, and _budget metadata injection.
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
        Object processed = applyBudgetLimits(data);
        processed = injectBudgetMetadata(processed);
        super.printResult(processed);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void printResultWithHints(Object data, java.util.List<com.jsrc.app.model.CommandHint> hints) {
        Object processed = applyBudgetLimits(data);
        processed = injectBudgetMetadata(processed);
        super.printResultWithHints(processed, hints);
    }

    @Override
    public void printClasses(List<com.jsrc.app.parser.model.ClassInfo> classes, Path sourceRoot) {
        List<com.jsrc.app.parser.model.ClassInfo> limited = applyListLimit(classes);
        if (limited.size() < classes.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        super.printClasses(limited, sourceRoot);
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

    @SuppressWarnings("unchecked")
    private Object applyBudgetLimits(Object data) {
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
    private Object injectBudgetMetadata(Object data) {
        Map<String, Object> metadata = budgetContext.buildMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return data;
        }

        if (data instanceof Map<?, ?> map) {
            Map<String, Object> withMeta = new LinkedHashMap<>();
            withMeta.put("_budget", metadata);
            withMeta.putAll((Map<String, Object>) map);
            return withMeta;
        } else if (data instanceof Collection<?> coll) {
            // For collections at root, wrap in an object
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("_budget", metadata);
            wrapper.put("items", coll);
            wrapper.put("count", coll.size());
            return wrapper;
        }
        
        return data;
    }
}
