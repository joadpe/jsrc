package com.jsrc.app.architecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Maps REST endpoint annotations to structured endpoint data.
 */
public class EndpointMapper {

    private static final Map<String, String> DEFAULT_HTTP_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "REQUEST"
    );

    private final Set<String> endpointAnnotations;

    public EndpointMapper(List<String> annotations) {
        this.endpointAnnotations = annotations.isEmpty()
                ? DEFAULT_HTTP_METHODS.keySet()
                : Set.copyOf(annotations);
    }

    /**
     * Finds all endpoints across classes.
     */
    public List<Map<String, Object>> findEndpoints(List<ClassInfo> classes) {
        List<Map<String, Object>> endpoints = new ArrayList<>();

        for (ClassInfo ci : classes) {
            // Get class-level path prefix (from @RequestMapping)
            String classPath = extractPath(ci.annotations());

            for (MethodInfo m : ci.methods()) {
                for (AnnotationInfo ann : m.annotations()) {
                    if (endpointAnnotations.contains(ann.name())) {
                        Map<String, Object> endpoint = new LinkedHashMap<>();
                        String methodPath = extractPathFromAnnotation(ann);
                        String fullPath = combinePaths(classPath, methodPath);
                        String httpMethod = DEFAULT_HTTP_METHODS.getOrDefault(ann.name(), ann.name());

                        endpoint.put("path", fullPath);
                        endpoint.put("httpMethod", httpMethod);
                        endpoint.put("controller", ci.qualifiedName());
                        endpoint.put("method", m.name());
                        endpoint.put("signature", m.signature());
                        endpoint.put("line", m.startLine());
                        endpoints.add(endpoint);
                    }
                }
            }
        }

        return endpoints;
    }

    private String extractPath(List<AnnotationInfo> annotations) {
        for (AnnotationInfo ann : annotations) {
            if ("RequestMapping".equals(ann.name())) {
                return extractPathFromAnnotation(ann);
            }
        }
        return "";
    }

    private String extractPathFromAnnotation(AnnotationInfo ann) {
        String value = ann.attributes().getOrDefault("value",
                ann.attributes().getOrDefault("path", ""));
        // Remove quotes if present
        return value.replaceAll("^\"|\"$", "");
    }

    private String combinePaths(String prefix, String path) {
        if (prefix.isEmpty()) return path.isEmpty() ? "/" : path;
        if (path.isEmpty()) return prefix;
        String combined = prefix;
        if (!combined.startsWith("/")) combined = "/" + combined;
        if (combined.endsWith("/") && path.startsWith("/")) {
            combined = combined + path.substring(1);
        } else if (!combined.endsWith("/") && !path.startsWith("/")) {
            combined = combined + "/" + path;
        } else {
            combined = combined + path;
        }
        return combined;
    }
}
