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
        // Apply budget limits and metadata
        Object processed = applyBudgetLimitsAndInjectMeta(data);
        
        // Merge hints into the processed data if provided
        if (hints != null && !hints.isEmpty()) {
            if (processed instanceof Map<?, ?> map) {
                var merged = new java.util.LinkedHashMap<>((Map<String, Object>) map);
                merged.put("nextCommands", hints.stream()
                        .map(h -> {
                            var m = new java.util.LinkedHashMap<String, String>();
                            m.put("command", h.command());
                            m.put("description", h.description());
                            return m;
                        })
                        .toList());
                processed = merged;
            }
        }
        
        // Serialize and apply max-bytes
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
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
        
        // Convert to maps with field filtering applied
        List<java.util.Map<String, Object>> items = limited.stream()
            .map(ci -> {
                var map = classToCompactMap(ci);
                // Apply budget field filtering if set, otherwise use explicit fields
                var effectiveFields = fieldSet != null ? fieldSet : fields;
                return com.jsrc.app.output.FieldsFilter.filter(map, effectiveFields);
            })
            .toList();
        
        // Serialize with max-bytes
        String json = com.jsrc.app.output.JsonWriter.toJson(items);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    private java.util.Map<String, Object> classToCompactMap(com.jsrc.app.parser.model.ClassInfo ci) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", ci.name());
        map.put("packageName", ci.packageName());
        map.put("line", ci.startLine());
        map.put("modifiers", ci.modifiers());
        map.put("isInterface", ci.isInterface());
        map.put("isAbstract", ci.isAbstract());
        map.put("methodCount", ci.methods().size());
        if (!ci.superClass().isEmpty()) {
            map.put("superClass", ci.superClass());
        }
        if (!ci.interfaces().isEmpty()) {
            map.put("interfaces", ci.interfaces());
        }
        if (!ci.annotations().isEmpty()) {
            map.put("annotations", ci.annotations().stream()
                    .map(this::annotationToMap).toList());
        }
        return map;
    }
    
    private java.util.Map<String, Object> annotationToMap(com.jsrc.app.parser.model.AnnotationInfo a) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", a.name());
        if (!a.isMarker()) {
            map.put("attributes", a.attributes());
        }
        return map;
    }

    @Override
    public void printClassSummary(com.jsrc.app.parser.model.ClassInfo classInfo, Path file) {
        // Apply field set if under budget
        var fieldSet = com.jsrc.app.cli.BudgetPolicy.getFieldsForProfile("class", budgetContext.profile());
        if (fieldSet != null) {
            budgetContext.addTransform("fields:" + budgetContext.profile().fieldSet());
        }
        
        // Build the summary map
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", classInfo.name());
        map.put("packageName", classInfo.packageName());
        map.put("file", file.toString());
        map.put("line", classInfo.startLine());
        map.put("modifiers", classInfo.modifiers());
        map.put("isInterface", classInfo.isInterface());
        map.put("isAbstract", classInfo.isAbstract());
        if (!classInfo.superClass().isEmpty()) {
            map.put("superClass", classInfo.superClass());
        }
        if (!classInfo.interfaces().isEmpty()) {
            map.put("interfaces", classInfo.interfaces());
        }
        if (!classInfo.annotations().isEmpty()) {
            map.put("annotations", classInfo.annotations().stream()
                    .map(this::annotationToMap).toList());
        }
        List<java.util.Map<String, Object>> methods = classInfo.methods().stream()
                .map(m -> {
                    java.util.Map<String, Object> mmap = new java.util.LinkedHashMap<>();
                    mmap.put("name", m.name());
                    mmap.put("signature", m.signature());
                    mmap.put("line", m.startLine());
                    mmap.put("returnType", m.returnType());
                    return mmap;
                }).toList();
        map.put("methods", methods);
        
        // Apply field filtering if applicable
        var effectiveFields = fieldSet != null ? fieldSet : fields;
        var filtered = com.jsrc.app.output.FieldsFilter.filter(map, effectiveFields);
        
        // Serialize with max-bytes
        String json = com.jsrc.app.output.JsonWriter.toJson(filtered);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    @Override
    public void printAnnotationMatches(List<com.jsrc.app.model.AnnotationMatch> matches) {
        // Apply list limit
        List<com.jsrc.app.model.AnnotationMatch> limited = applyListLimit(matches);
        if (limited.size() < matches.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Convert to maps
        List<java.util.Map<String, Object>> items = limited.stream()
                .map(m -> {
                    java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("type", m.type());
                    map.put("name", m.name());
                    map.put("class", m.className());
                    map.put("file", m.file().toString());
                    map.put("line", m.line());
                    map.put("annotation", annotationToMap(m.annotation()));
                    return map;
                }).toList();
        
        // Serialize with max-bytes
        String json = com.jsrc.app.output.JsonWriter.toJson(items);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
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
        
        // Convert to maps with field filtering applied
        List<java.util.Map<String, Object>> items = limited.stream()
            .map(m -> {
                var map = methodToMap(m, file);
                var effectiveFields = fieldSet != null ? fieldSet : fields;
                return com.jsrc.app.output.FieldsFilter.filter(map, effectiveFields);
            })
            .toList();
        
        // Serialize with max-bytes
        String json = com.jsrc.app.output.JsonWriter.toJson(items);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    private java.util.Map<String, Object> methodToMap(com.jsrc.app.parser.model.MethodInfo m, Path file) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", m.name());
        map.put("class", m.className());
        map.put("file", file.toString());
        map.put("line", m.startLine());
        map.put("signature", m.signature());

        if (!signatureOnly) {
            map.put("returnType", m.returnType());
            map.put("modifiers", m.modifiers());
            map.put("parameters", m.parameters().stream().map(this::paramToMap).toList());

            if (!m.annotations().isEmpty()) {
                map.put("annotations", m.annotations().stream()
                        .map(this::annotationToMap).toList());
            }
            if (!m.thrownExceptions().isEmpty()) {
                map.put("thrownExceptions", m.thrownExceptions());
            }
            if (!m.typeParameters().isEmpty()) {
                map.put("typeParameters", m.typeParameters());
            }
        }
        return map;
    }
    
    private java.util.Map<String, Object> paramToMap(com.jsrc.app.parser.model.MethodInfo.ParameterInfo p) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", p.type());
        map.put("name", p.name());
        return map;
    }

    @Override
    public void printRefs(List<Map<String, Object>> refs, String label, String target) {
        // V3: Apply list limit AND max-bytes (not just limit then super)
        List<Map<String, Object>> limited = applyListLimit(refs);
        if (limited.size() < refs.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Serialize with max-bytes enforcement
        String json = com.jsrc.app.output.JsonWriter.toJson(limited);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result) {
        printOverview(result, result.packages().size(), List.of());
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result, int packageCount) {
        printOverview(result, packageCount, List.of());
    }

    @Override
    public void printOverview(com.jsrc.app.model.OverviewResult result, int packageCount, java.util.List<String> topClasses) {
        // V1: Apply budget enforcement (no bare super bypass)
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalFiles", result.totalFiles());
        map.put("totalClasses", result.totalClasses());
        map.put("totalInterfaces", result.totalInterfaces());
        map.put("totalMethods", result.totalMethods());
        map.put("totalPackages", packageCount);
        
        // Apply list limit to packages
        if (!result.packages().isEmpty()) {
            List<String> limitedPackages = applyListLimit(new ArrayList<>(result.packages()));
            if (limitedPackages.size() < result.packages().size()) {
                budgetContext.setTruncated(true);
                budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
            }
            map.put("packages", limitedPackages);
        }
        
        // Apply list limit to topClasses
        if (!topClasses.isEmpty()) {
            List<String> limitedTopClasses = applyListLimit(new ArrayList<>(topClasses));
            if (limitedTopClasses.size() < topClasses.size()) {
                budgetContext.setTruncated(true);
                budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
            }
            map.put("topClasses", limitedTopClasses);
        }
        
        // Use serialize helper with budget metadata and max-bytes
        Object processed = applyBudgetLimitsAndInjectMeta(map);
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }

    @Override
    public void printReadResult(com.jsrc.app.parser.SourceReader.ReadResult result) {
        // V2: Apply max-bytes enforcement (not bare super bypass)
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("class", result.className());
        if (result.methodName() != null) {
            map.put("method", result.methodName());
        }
        map.put("file", result.file().toString());
        map.put("line", result.startLine());
        map.put("content", result.content());
        
        // Apply field filtering if configured
        var filtered = com.jsrc.app.output.FieldsFilter.filter(map, fields);
        
        // Serialize with max-bytes enforcement
        String json = com.jsrc.app.output.JsonWriter.toJson(filtered);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    @Override
    public void printDependencies(com.jsrc.app.model.DependencyResult result) {
        // B2: Apply budget limits to dependency lists
        List<String> limitedImports = applyListLimit(new ArrayList<>(result.imports()));
        List<com.jsrc.app.model.DependencyResult.FieldDep> limitedFields = 
            applyListLimit(new ArrayList<>(result.fieldDependencies()));
        List<com.jsrc.app.model.DependencyResult.FieldDep> limitedConstructor = 
            applyListLimit(new ArrayList<>(result.constructorDependencies()));
        
        if (limitedImports.size() < result.imports().size() ||
            limitedFields.size() < result.fieldDependencies().size() ||
            limitedConstructor.size() < result.constructorDependencies().size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Create limited result
        var limitedResult = new com.jsrc.app.model.DependencyResult(
            result.className(),
            limitedImports,
            limitedFields,
            limitedConstructor
        );
        
        // Serialize with budget metadata injection
        Object processed = applyBudgetLimitsAndInjectMeta(buildDependenciesMap(limitedResult));
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    private Map<String, Object> buildDependenciesMap(com.jsrc.app.model.DependencyResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("class", result.className());
        map.put("imports", result.imports());
        map.put("fieldDependencies", result.fieldDependencies().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", d.type());
                    m.put("name", d.name());
                    return m;
                }).toList());
        map.put("constructorDependencies", result.constructorDependencies().stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", d.type());
                    m.put("name", d.name());
                    return m;
                }).toList());
        return map;
    }
    
    @Override
    public void printHierarchy(com.jsrc.app.model.HierarchyResult result) {
        // B2: Apply budget limits to hierarchy lists
        List<String> limitedInterfaces = applyListLimit(new ArrayList<>(result.interfaces()));
        List<String> limitedSubClasses = applyListLimit(new ArrayList<>(result.subClasses()));
        List<String> limitedImplementors = applyListLimit(new ArrayList<>(result.implementors()));
        
        if (limitedInterfaces.size() < result.interfaces().size() ||
            limitedSubClasses.size() < result.subClasses().size() ||
            limitedImplementors.size() < result.implementors().size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Create limited result
        var limitedResult = new com.jsrc.app.model.HierarchyResult(
            result.target(),
            result.superClass(),
            limitedInterfaces,
            limitedSubClasses,
            limitedImplementors
        );
        
        // Serialize with budget metadata injection
        Object processed = applyBudgetLimitsAndInjectMeta(buildHierarchyMap(limitedResult));
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    private Map<String, Object> buildHierarchyMap(com.jsrc.app.model.HierarchyResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("target", result.target());
        map.put("superClass", result.superClass());
        map.put("interfaces", result.interfaces());
        map.put("subClasses", result.subClasses());
        map.put("implementors", result.implementors());
        return map;
    }
    
    @Override
    public void printSmells(List<com.jsrc.app.parser.model.CodeSmell> smells, Path file,
                             java.util.List<com.jsrc.app.model.CommandHint> hints) {
        printSmellsWithBudget(smells, file, hints);
    }

    @Override
    public void printSmells(List<com.jsrc.app.parser.model.CodeSmell> smells, Path file) {
        printSmellsWithBudget(smells, file, null);
    }
    
    private void printSmellsWithBudget(List<com.jsrc.app.parser.model.CodeSmell> smells, Path file,
                                        java.util.List<com.jsrc.app.model.CommandHint> hints) {
        // B2: Apply budget limits to smells list
        List<com.jsrc.app.parser.model.CodeSmell> limitedSmells = applyListLimit(new ArrayList<>(smells));
        
        if (limitedSmells.size() < smells.size()) {
            budgetContext.setTruncated(true);
            budgetContext.addTransform("limit:" + budgetContext.effectiveLimit());
        }
        
        // Build findings from limited smells
        List<Map<String, Object>> findings = limitedSmells.stream()
                .map(this::smellToMap)
                .toList();

        // Calculate summary from limited smells (reflects what's shown)
        long errors = limitedSmells.stream()
            .filter(s -> s.severity() == com.jsrc.app.parser.model.CodeSmell.Severity.ERROR).count();
        long warnings = limitedSmells.stream()
            .filter(s -> s.severity() == com.jsrc.app.parser.model.CodeSmell.Severity.WARNING).count();
        long infos = limitedSmells.stream()
            .filter(s -> s.severity() == com.jsrc.app.parser.model.CodeSmell.Severity.INFO).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", limitedSmells.size());
        summary.put("errors", errors);
        summary.put("warnings", warnings);
        summary.put("infos", infos);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.toString());
        result.put("findings", findings);
        result.put("summary", summary);

        if (hints != null && !hints.isEmpty()) {
            result.put("nextCommands", hints.stream()
                    .map(h -> {
                        var m = new LinkedHashMap<String, String>();
                        m.put("command", h.command());
                        m.put("description", h.description());
                        return m;
                    })
                    .toList());
        }
        
        // Apply budget metadata injection
        Object processed = applyBudgetLimitsAndInjectMeta(result);
        String json = com.jsrc.app.output.JsonWriter.toJson(processed);
        String truncated = applyMaxBytes(json);
        out.println(truncated);
    }
    
    private Map<String, Object> smellToMap(com.jsrc.app.parser.model.CodeSmell smell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", smell.ruleId());
        map.put("severity", smell.severity().toString().toLowerCase());
        map.put("message", smell.message());
        map.put("line", smell.line());
        if (smell.methodName() != null && !smell.methodName().isEmpty()) {
            map.put("method", smell.methodName());
        }
        if (smell.className() != null && !smell.className().isEmpty()) {
            map.put("class", smell.className());
        }
        return map;
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
     * Produces valid JSON (object or array) with truncation marker.
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
        
        // V4: Produce valid JSON with truncation marker
        boolean isArray = json.trim().startsWith("[");
        boolean isObject = json.trim().startsWith("{");
        
        if (isObject) {
            // For objects: Reserve space for truncation suffix
            String suffix = ",\"_truncated\":true}";
            int suffixLen = suffix.length();
            
            // If maxBytes is too small to fit even the minimal object, return safe fallback
            if (maxBytes < suffixLen + 1) {
                return "{\"_truncated\":true}";
            }
            
            // Calculate safe truncation point, reserving space for suffix
            int safeLength = Math.min(maxBytes - suffixLen, json.length());
            if (safeLength <= 1) {
                return "{\"_truncated\":true}";
            }
            
            String truncated = json.substring(0, safeLength);
            
            // Find last complete field (look for last comma or opening brace)
            int lastComma = truncated.lastIndexOf(',');
            int openBrace = truncated.indexOf('{');
            
            // CRITICAL FIX: If no comma found, we can't preserve any field safely
            // Return minimal valid object with just the truncation marker
            if (lastComma <= openBrace) {
                return "{\"_truncated\":true}";
            }
            
            // Cut at the last comma (removes incomplete field)
            truncated = truncated.substring(0, lastComma);
            
            // Add truncated marker and close
            return truncated + suffix;
        } else if (isArray) {
            // For arrays: Reserve space for closing bracket
            String suffix = "]";
            int suffixLen = suffix.length();
            
            // If maxBytes is too small, return empty array
            if (maxBytes < suffixLen + 1) {
                return "[]";
            }
            
            // Calculate safe truncation point
            int safeLength = Math.min(maxBytes - suffixLen, json.length());
            if (safeLength <= 1) {
                return "[]";
            }
            
            String truncated = json.substring(0, safeLength);
            
            // Find last complete item (look for last comma or opening bracket)
            int lastComma = truncated.lastIndexOf(',');
            int openBracket = truncated.indexOf('[');
            
            // If no comma found, return empty array (safer than partial element)
            if (lastComma <= openBracket) {
                return "[]";
            }
            
            // Cut at the last comma
            truncated = truncated.substring(0, lastComma);
            
            // Close array
            return truncated + suffix;
        } else {
            // Fallback: simple truncation with marker
            return "{\"_truncated\":true}";
        }
    }
}
