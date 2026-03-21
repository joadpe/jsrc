package com.jsrc.app.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Verifies that a class implementation matches its Markdown spec.
 * Reports discrepancies.
 */
public class SpecVerifier {

    /**
     * Compares implementation against spec and returns discrepancies.
     */
    public static Map<String, Object> verify(ClassInfo impl, SpecParser.Spec spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", spec.className());
        result.put("specMethods", spec.methods().size());
        result.put("implMethods", impl.methods().size());

        List<Map<String, Object>> discrepancies = new ArrayList<>();

        // Check each spec method exists in implementation
        for (SpecParser.SpecMethod sm : spec.methods()) {
            MethodInfo found = impl.methods().stream()
                    .filter(m -> m.name().equals(sm.name()))
                    .findFirst().orElse(null);

            if (found == null) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", "missing_method");
                d.put("method", sm.name());
                d.put("message", "Method '" + sm.name() + "' defined in spec but not found in implementation");
                discrepancies.add(d);
                continue;
            }

            // Check throws
            for (String expectedThrow : sm.expectedThrows()) {
                if (!found.thrownExceptions().contains(expectedThrow)) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("type", "missing_throws");
                    d.put("method", sm.name());
                    d.put("expected", expectedThrow);
                    d.put("message", "Method '" + sm.name() + "' should throw " + expectedThrow);
                    discrepancies.add(d);
                }
            }

            // Check annotations
            for (String expectedAnn : sm.expectedAnnotations()) {
                if (!found.hasAnnotation(expectedAnn)) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("type", "missing_annotation");
                    d.put("method", sm.name());
                    d.put("expected", "@" + expectedAnn);
                    d.put("message", "Method '" + sm.name() + "' should have @" + expectedAnn);
                    discrepancies.add(d);
                }
            }
        }

        // Check implementation methods not in spec
        for (MethodInfo m : impl.methods()) {
            boolean inSpec = spec.methods().stream()
                    .anyMatch(sm -> sm.name().equals(m.name()));
            if (!inSpec) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", "undocumented_method");
                d.put("method", m.name());
                d.put("message", "Method '" + m.name() + "' exists in implementation but not in spec");
                discrepancies.add(d);
            }
        }

        result.put("discrepancies", discrepancies);
        result.put("pass", discrepancies.isEmpty());
        return result;
    }
}
