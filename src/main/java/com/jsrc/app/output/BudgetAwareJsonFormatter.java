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
        super.printResult(processed);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void printResultWithHints(Object data, java.util.List<com.jsrc.app.model.CommandHint> hints) {
        Object processed = applyBudgetLimitsAndInjectMeta(data);
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

    @Override
    public void printMethods(List<com.jsrc.app.parser.model.MethodInfo> methods, Path file, String methodName) {
        List<com.jsrc.app.parser.model.MethodInfo> limited = applyListLimit(methods);
        if (limited.size() < methods.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        super.printMethods(limited, file, methodName);
    }

    @Override
    public void printAnnotationMatches(List<com.jsrc.app.model.AnnotationMatch> matches) {
        List<com.jsrc.app.model.AnnotationMatch> limited = applyListLimit(matches);
        if (limited.size() < matches.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        super.printAnnotationMatches(limited);
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
}
