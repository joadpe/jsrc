package com.jsrc.app.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.MethodResolver;
import com.jsrc.app.util.MethodTargetResolver;

/**
 * Generates a step-by-step checklist for modifying a method.
 * Pre-masticates the plan that small models can't derive alone.
 * <p>
 * Uses the call graph to identify callers that need updating,
 * and common patterns to suggest specific actions.
 */
public class ChecklistCommand implements Command {

    private final String methodInput;
    private final String task;

    public ChecklistCommand(String methodInput, String task) {
        this.methodInput = methodInput;
        this.task = task;
    }

    @Override
    public int execute(CommandContext ctx) {
        var ref = MethodResolver.parse(methodInput);
        CallGraph graph = ctx.callGraph();
        var resolved = MethodTargetResolver.resolve(ref, graph);

        if (resolved.targets().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("error", "Method not found: " + methodInput);
            ctx.formatter().printResult(result);
            return 0;
        }

        // Find file for target class
        String targetClass = resolved.targets().iterator().next().className();
        String filePath = ctx.indexed() != null
                ? ctx.indexed().findFileForClass(targetClass).orElse(targetClass + ".java")
                : targetClass + ".java";

        // Find callers
        List<String> callerRefs = new ArrayList<>();
        for (var target : resolved.targets()) {
            for (MethodCall call : graph.getCallersOf(target)) {
                String callerClass = call.caller().className();
                if (!"?".equals(callerClass) && !callerClass.equals(targetClass)) {
                    String callerFile = ctx.indexed() != null
                            ? ctx.indexed().findFileForClass(callerClass).orElse(callerClass + ".java")
                            : callerClass + ".java";
                    callerRefs.add(callerClass + " (" + callerFile + ":" + call.line() + ")");
                }
            }
        }
        callerRefs = callerRefs.stream().distinct().toList();

        // Build steps based on task pattern
        List<Map<String, Object>> steps = new ArrayList<>();
        int step = 1;

        // Step 1: modify the method
        steps.add(makeStep(step++, "edit", filePath,
                "Modify " + methodInput + taskSuffix()));

        // Pattern-specific steps
        String taskLower = task != null ? task.toLowerCase() : "";
        if (taskLower.contains("null check") || taskLower.contains("requirenonnull")) {
            steps.add(makeStep(step++, "add-import", filePath,
                    "Add import java.util.Objects if missing"));
        }
        if (taskLower.contains("rename")) {
            // Renaming requires updating all callers
            for (String caller : callerRefs) {
                steps.add(makeStep(step++, "update-caller", caller,
                        "Update call site to use new name"));
            }
        }
        if (taskLower.contains("parameter") || taskLower.contains("signature")) {
            for (String caller : callerRefs) {
                steps.add(makeStep(step++, "update-caller", caller,
                        "Update call to match new signature"));
            }
        }

        // Generic: check callers if not already added
        if (!taskLower.contains("rename") && !taskLower.contains("parameter")
                && !callerRefs.isEmpty()) {
            if (callerRefs.size() <= 10) {
                steps.add(makeStep(step++, "check-callers", String.join(", ", callerRefs),
                        "Verify " + callerRefs.size() + " caller(s) are compatible with change"));
            } else {
                // Group by package for large caller sets
                steps.add(makeStep(step++, "check-callers", callerRefs.size() + " callers",
                        "Verify callers across " + callerRefs.stream()
                                .map(r -> r.split("\\(")[0].trim())
                                .map(r -> r.contains(".") ? r.substring(0, r.lastIndexOf('.')) : "default")
                                .distinct().count() + " package(s)"));
            }
        }

        // Step N: test
        steps.add(makeStep(step, "test", "mvn test",
                "Run tests to verify change"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", methodInput);
        result.put("task", task != null ? task : "modify method");
        result.put("file", filePath);
        result.put("callerCount", callerRefs.size());
        result.put("steps", steps);

        ctx.formatter().printResult(result);
        return steps.size();
    }

    private Map<String, Object> makeStep(int num, String action, String location, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", num);
        step.put("action", action);
        step.put("location", location);
        step.put("description", description);
        return step;
    }

    private String taskSuffix() {
        return task != null ? " — " + task : "";
    }
}
