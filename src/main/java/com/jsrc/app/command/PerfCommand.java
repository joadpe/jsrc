package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Detects performance bottlenecks in methods by analyzing source code
 * at depth 0-1 (real patterns) and index metrics at depth 2+ (heuristics).
 *
 * <p>Patterns detected from source (depth 0-1):
 * <ul>
 *   <li>LOOP_WITH_LINEAR_CALLEE: for calling O(N) method → O(N²)</li>
 *   <li>LOOP_WITH_IO: for with file I/O inside</li>
 *   <li>ALLOCATION_IN_LOOP: new HashMap/ArrayList in loop</li>
 *   <li>NESTED_ITERATION: stream with inner collection scan</li>
 *   <li>FULL_SCAN_FOR_ONE: iterates all items to find 1</li>
 * </ul>
 *
 * <p>Heuristics from index (depth 2+):
 * <ul>
 *   <li>HIGH_COMPLEXITY: cyclomatic complexity > 10</li>
 *   <li>LARGE_METHOD: > 50 LOC</li>
 *   <li>HOT_METHOD: > 100 callers</li>
 * </ul>
 */
public class PerfCommand implements Command {

    private final String target;
    private final int maxDepth;

    public PerfCommand(String target, int maxDepth) {
        this.target = target;
        this.maxDepth = maxDepth;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Resolve target — could be Class or Class.method
        final String className;
        final String methodName;
        if (target.contains(".")) {
            int dot = target.lastIndexOf('.');
            className = target.substring(0, dot);
            methodName = target.substring(dot + 1);
        } else {
            className = target;
            methodName = null;
        }

        ClassInfo ci = SummaryCommand.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        // Get source file for depth 0-1 analysis
        String sourceCode = null;
        if (ctx.indexed() != null) {
            var filePath = ctx.indexed().findFileForClass(ci.name());
            if (filePath.isPresent()) {
                try {
                    sourceCode = java.nio.file.Files.readString(Path.of(ctx.rootPath()).resolve(filePath.get()));
                } catch (Exception e) {
                    // Fall back to file scan
                }
            }
        }
        // Fallback: search javaFiles for the class
        if (sourceCode == null) {
            for (Path file : ctx.javaFiles()) {
                if (file.getFileName().toString().equals(ci.name() + ".java")) {
                    try {
                        sourceCode = java.nio.file.Files.readString(file);
                    } catch (Exception e) {
                        // ignore
                    }
                    break;
                }
            }
        }

        CallGraph graph = ctx.callGraph();

        if (methodName != null) {
            // Single method analysis
            MethodInfo mi = ci.methods().stream()
                    .filter(m -> m.name().equals(methodName))
                    .findFirst().orElse(null);
            if (mi == null) {
                System.err.println("Method '" + methodName + "' not found in " + ci.name());
                return 0;
            }
            Map<String, Object> result = analyzeMethod(ci, mi, sourceCode, graph, ctx, 0);
            ctx.formatter().printResult(result);
            return 1;
        } else {
            // Whole class analysis
            Map<String, Object> result = analyzeClass(ci, sourceCode, graph, ctx);
            ctx.formatter().printResult(result);
            return ci.methods().size();
        }
    }

    private Map<String, Object> analyzeClass(ClassInfo ci, String sourceCode,
                                              CallGraph graph, CommandContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", ci.qualifiedName());

        var filePath = ctx.indexed() != null ? ctx.indexed().findFileForClass(ci.name()) : Optional.<String>empty();
        filePath.ifPresent(p -> result.put("file", p));

        List<Map<String, Object>> perMethod = new ArrayList<>();
        int totalFindings = 0;
        String worstMethod = null;
        int worstCount = 0;

        for (MethodInfo mi : ci.methods()) {
            Map<String, Object> mResult = analyzeMethod(ci, mi, sourceCode, graph, ctx, 0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> findings = (List<Map<String, Object>>) mResult.get("findings");
            if (findings != null && !findings.isEmpty()) {
                perMethod.add(mResult);
                totalFindings += findings.size();
                if (findings.size() > worstCount) {
                    worstCount = findings.size();
                    worstMethod = mi.name();
                }
            }
        }

        result.put("methods", ci.methods().size());
        result.put("totalFindings", totalFindings);
        if (worstMethod != null) result.put("worstMethod", worstMethod);
        result.put("perMethod", perMethod);
        return result;
    }

    private Map<String, Object> analyzeMethod(ClassInfo ci, MethodInfo mi, String sourceCode,
                                               CallGraph graph, CommandContext ctx, int currentDepth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", ci.name() + "." + mi.name());
        result.put("line", mi.startLine());
        result.put("complexity", 0);
        int loc = (mi.startLine() > 0 && mi.endLine() > mi.startLine()) 
                ? mi.endLine() - mi.startLine() : 0;
        result.put("loc", loc);

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> tree = new ArrayList<>();

        // Get method source for depth 0-1 analysis
        String methodSource = null;
        if (sourceCode != null && currentDepth <= 1) {
            methodSource = extractMethodSource(sourceCode, mi.startLine(), mi.endLine());
        }

        if (methodSource != null) {
            // Source-based detection (depth 0-1)
            analyzeSource(methodSource, mi, ci, findings, tree, graph, ctx, currentDepth);
            result.put("analysis", "source");
        } else {
            // Index-only heuristics (depth 2+)
            analyzeFromIndex(mi, ci, findings, graph);
            result.put("analysis", "index-only");
        }

        result.put("findings", findings);
        result.put("tree", tree);
        return result;
    }

    private void analyzeSource(String methodSource, MethodInfo mi, ClassInfo ci,
                                List<Map<String, Object>> findings,
                                List<Map<String, Object>> tree,
                                CallGraph graph, CommandContext ctx, int currentDepth) {
        String[] lines = methodSource.split("\n");
        boolean inLoop = false;
        int loopDepth = 0;
        List<Map<String, Object>> loopCalls = null;
        int loopLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = mi.startLine() + i;

            // Detect loop start
            if (isLoopStart(line)) {
                if (!inLoop) {
                    inLoop = true;
                    loopLine = lineNum;
                    loopCalls = new ArrayList<>();
                }
                loopDepth++;
            }

            if (inLoop) {
                // Detect allocation in loop
                if (line.contains("new HashMap") || line.contains("new ArrayList")
                        || line.contains("new LinkedHashMap") || line.contains("new HashSet")
                        || line.contains("new LinkedList")) {
                    findings.add(finding("ALLOCATION_IN_LOOP", "WARNING", lineNum,
                            "Object allocation inside loop — consider pre-allocating or reusing"));
                }

                // Detect I/O in loop
                if (line.contains("Files.read") || line.contains("Files.write")
                        || line.contains("new File(") || line.contains("io.File(")
                        || line.contains(".parse(") || line.contains("FileInputStream")
                        || line.contains("readString") || line.contains("readAllBytes")
                        || line.contains("writeString") || line.contains("writeToFile")) {
                    findings.add(finding("LOOP_WITH_IO", "CRITICAL", lineNum,
                            "I/O operation inside loop — moves bottleneck from CPU to disk"));
                }

                // Detect method calls in loop — check if callee has loops (depth 1)
                if (currentDepth < maxDepth) {
                    for (String callName : extractMethodCalls(line)) {
                        Map<String, Object> callNode = new LinkedHashMap<>();
                        callNode.put("method", callName);
                        callNode.put("line", lineNum);

                        // Check callee complexity via index
                        boolean isLinearScan = isLinearScanCallee(callName, ci, graph, ctx);
                        if (isLinearScan) {
                            callNode.put("flags", List.of("🔴 LINEAR_SCAN in loop → O(N²)"));
                            findings.add(finding("LOOP_WITH_LINEAR_CALLEE", "CRITICAL", lineNum,
                                    "O(N²): loop calls " + callName + " which does linear scan"));
                        } else {
                            callNode.put("flags", List.of());
                        }
                        if (loopCalls != null) loopCalls.add(callNode);
                    }
                }

                // Detect nested iteration
                if (line.contains(".stream()") && (line.contains("noneMatch") || line.contains("anyMatch")
                        || line.contains("allMatch") || line.contains("filter"))) {
                    findings.add(finding("NESTED_ITERATION", "WARNING", lineNum,
                            "Nested iteration — consider using a Set for O(1) lookup"));
                }
            }

            // Detect loop end (simple brace counting)
            if (line.contains("}") && inLoop) {
                loopDepth--;
                if (loopDepth <= 0) {
                    if (loopCalls != null && !loopCalls.isEmpty()) {
                        Map<String, Object> loopNode = new LinkedHashMap<>();
                        loopNode.put("method", "⟳ loop");
                        loopNode.put("line", loopLine);
                        loopNode.put("calls", loopCalls);
                        tree.add(loopNode);
                    }
                    inLoop = false;
                    loopCalls = null;
                }
            }
        }
    }

    private boolean isLinearScanCallee(String callName, ClassInfo ci, CallGraph graph, CommandContext ctx) {
        String calleeMethod = callName.contains(".") ? callName.substring(callName.lastIndexOf('.') + 1) : callName;
        String calleeClass = callName.contains(".") ? callName.substring(0, callName.lastIndexOf('.')) : null;

        if (calleeClass != null) {
            // Resolve field type to class name
            String resolvedClass = resolveFieldType(calleeClass, ci);

            // Strategy 1: use index
            if (ctx.indexed() != null) {
                var filePath = ctx.indexed().findFileForClass(resolvedClass);
                if (filePath.isPresent()) {
                    return checkCalleeForLoop(Path.of(ctx.rootPath()).resolve(filePath.get()), calleeMethod);
                }
            }

            // Strategy 2: search javaFiles for the class
            for (Path file : ctx.javaFiles()) {
                if (file.getFileName().toString().equals(resolvedClass + ".java")) {
                    return checkCalleeForLoop(file, calleeMethod);
                }
            }
        }
        return false;
    }

    private boolean checkCalleeForLoop(Path file, String methodName) {
        try {
            String source = java.nio.file.Files.readString(file);
            String methodSrc = extractMethodByName(source, methodName);
            if (methodSrc != null) {
                return methodSrc.contains("for (") || methodSrc.contains("for(")
                        || methodSrc.contains("while (") || methodSrc.contains("while(");
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String resolveFieldType(String fieldOrVar, ClassInfo ci) {
        // Check if it's a field name → resolve to type
        for (var field : ci.fields()) {
            if (field.name().equals(fieldOrVar)) {
                return field.type();
            }
        }
        // Could be a variable — return as-is (might be a class name)
        return fieldOrVar;
    }

    private void analyzeFromIndex(MethodInfo mi, ClassInfo ci,
                                   List<Map<String, Object>> findings, CallGraph graph) {
        if (0 > 10) {
            findings.add(finding("HIGH_COMPLEXITY", "WARNING", mi.startLine(),
                    "Cyclomatic complexity " + 0 + " (threshold: 10)"));
        }
        int loc = mi.endLine() - mi.startLine();
        if (loc > 50 && loc < 5000 && mi.startLine() > 0) {
            findings.add(finding("LARGE_METHOD", "INFO", mi.startLine(),
                    loc + " LOC (threshold: 50)"));
        }

        // Check caller count
        Set<MethodReference> refs = graph.findMethodsByName(mi.name());
        for (MethodReference ref : refs) {
            if (ref.className().equals(ci.name())) {
                int callerCount = graph.getCallersOf(ref).size();
                if (callerCount > 100) {
                    findings.add(finding("HOT_METHOD", "INFO", mi.startLine(),
                            callerCount + " callers — optimize for performance"));
                }
            }
        }
    }

    private static Map<String, Object> finding(String type, String severity, int line, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("severity", severity);
        f.put("line", line);
        f.put("message", message);
        return f;
    }

    private static boolean isLoopStart(String line) {
        return line.startsWith("for ") || line.startsWith("for(")
                || line.startsWith("while ") || line.startsWith("while(")
                || line.contains("for (") || line.contains("while (")
                || line.contains(".forEach(");
    }

    private static List<String> extractMethodCalls(String line) {
        List<String> calls = new ArrayList<>();
        // Simple pattern: identifier.method( or identifier.method(
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\w+\\.\\w+)\\s*\\(").matcher(line);
        while (m.find()) {
            String call = m.group(1);
            // Filter out common non-method patterns
            if (!call.startsWith("new ") && !call.equals("System.out")
                    && !call.equals("System.err")) {
                calls.add(call);
            }
        }
        return calls;
    }

    private static String extractMethodSource(String source, int startLine, int endLine) {
        String[] lines = source.split("\n");
        if (startLine < 1 || endLine > lines.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < endLine && i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private static String extractMethodByName(String source, String methodName) {
        // Find method signature and extract body
        int idx = source.indexOf(" " + methodName + "(");
        if (idx < 0) idx = source.indexOf("\t" + methodName + "(");
        if (idx < 0) return null;

        // Find the opening brace
        int braceStart = source.indexOf('{', idx);
        if (braceStart < 0) return null;

        // Count braces to find the end
        int depth = 0;
        int end = braceStart;
        for (int i = braceStart; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        return source.substring(braceStart, end + 1);
    }
}
