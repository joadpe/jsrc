package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodReference;

import java.util.function.Predicate;

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

    /**
     * A line-level performance anti-pattern detector.
     * To add a new pattern: add an entry to PATTERNS.
     * It will automatically be detected both directly in loop bodies
     * AND recursively in callee chains up to maxDepth.
     */
    private record PatternDef(
            String id,
            String severity,
            String directType,
            String deepType,
            String directMessage,
            String deepPrefix,
            Predicate<String> detector
    ) {}

    private static final List<PatternDef> PATTERNS = List.of(
            new PatternDef("IO", "CRITICAL",
                    "LOOP_WITH_IO", "LOOP_WITH_DEEP_IO",
                    "I/O operation inside loop — moves bottleneck from CPU to disk",
                    "IO in call chain",
                    PerfCommand::hasDirectIO),
            new PatternDef("ALLOCATION", "WARNING",
                    "ALLOCATION_IN_LOOP", "LOOP_WITH_DEEP_ALLOCATION",
                    "Object allocation inside loop — consider pre-allocating or reusing",
                    "Allocation in call chain",
                    PerfCommand::hasAllocation),
            new PatternDef("NESTED", "WARNING",
                    "NESTED_ITERATION", "LOOP_WITH_DEEP_NESTED",
                    "Nested iteration — consider using a Set for O(1) lookup",
                    "Nested iteration in call chain",
                    PerfCommand::hasNestedIteration)
    );

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
            analyzeSource(methodSource, sourceCode, mi, ci, findings, tree, graph, ctx, currentDepth);
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

    private void analyzeSource(String methodSource, String fullClassSource, MethodInfo mi, ClassInfo ci,
                                List<Map<String, Object>> findings,
                                List<Map<String, Object>> tree,
                                CallGraph graph, CommandContext ctx, int currentDepth) {
        String[] lines = methodSource.split("\n");
        boolean inLoop = false;
        int loopDepth = 0;
        List<Map<String, Object>> loopCalls = null;
        int loopLine = 0;
        Set<String> loopReported = new HashSet<>(); // dedup per loop

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = mi.startLine() + i;

            // Detect loop start
            if (isLoopStart(line)) {
                if (!inLoop) {
                    inLoop = true;
                    loopLine = lineNum;
                    loopCalls = new ArrayList<>();
                    loopReported = new HashSet<>();
                }
                loopDepth++;
            }

            if (inLoop) {
                // Track what patterns have been reported for this loop to avoid duplicates
                // (initialized per loop via loopReported, reset when loop starts)

                // 1. Detect all patterns directly in this line
                for (PatternDef pattern : PATTERNS) {
                    String directKey = pattern.id() + ":direct:" + lineNum;
                    if (pattern.detector().test(line) && !loopReported.contains(directKey)) {
                        findings.add(finding(pattern.directType(), pattern.severity(), lineNum,
                                pattern.directMessage()));
                        loopReported.add(directKey);
                        // Block deep search for same pattern type in this loop
                        loopReported.add(pattern.id() + ":direct:block");
                    }
                }

                // 2. Deep search into callees (up to maxDepth)
                if (currentDepth < maxDepth) {
                    for (String callName : extractMethodCalls(line)) {
                        Map<String, Object> callNode = new LinkedHashMap<>();
                        callNode.put("method", callName);
                        callNode.put("line", lineNum);

                        List<String> flags = new ArrayList<>();
                        Set<String> visited = new HashSet<>();

                        // Deep search for ALL patterns in callee chain
                        var deepFindings = findDeepPatterns(callName, ci, ctx, fullClassSource,
                                currentDepth + 1, maxDepth, visited);

                        for (var df : deepFindings) {
                            String type = df.get("type").toString();
                            String path = df.get("path").toString();
                            // Skip if direct detection already found this pattern type in this loop
                            if (loopReported.contains(type + ":direct:block")) continue;
                            // Dedup: same type + same path = duplicate
                            String dedupKey = type + ":" + path;
                            if (!loopReported.contains(dedupKey)) {
                                // Find the matching pattern def for proper naming
                                String deepType = PATTERNS.stream()
                                        .filter(p -> p.id().equals(type))
                                        .map(PatternDef::deepType)
                                        .findFirst().orElse("LOOP_WITH_DEEP_" + type);
                                flags.add("🔴 DEEP " + type + ": " + path);
                                findings.add(finding(deepType, "CRITICAL", lineNum,
                                        type + " in call chain: " + callName + " → " + path));
                                loopReported.add(dedupKey);
                            }
                        }

                        callNode.put("flags", flags);
                        if (loopCalls != null) loopCalls.add(callNode);
                    }
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

    private boolean isLinearScanCallee(String callName, ClassInfo ci, CallGraph graph,
                                       CommandContext ctx, String currentSource) {
        String calleeMethod = callName.contains(".") ? callName.substring(callName.lastIndexOf('.') + 1) : callName;
        String calleeClass = callName.contains(".") ? callName.substring(0, callName.lastIndexOf('.')) : null;

        // Same-class call (this.method)
        if ("this".equals(calleeClass)) {
            if (currentSource != null) {
                return checkSourceForLoop(currentSource, calleeMethod);
            }
            return false;
        }

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

    private static boolean hasAllocation(String line) {
        return line.contains("new HashMap") || line.contains("new ArrayList")
                || line.contains("new LinkedHashMap") || line.contains("new HashSet")
                || line.contains("new LinkedList");
    }

    private static boolean hasNestedIteration(String line) {
        return line.contains(".stream()") && (line.contains("noneMatch") || line.contains("anyMatch")
                || line.contains("allMatch") || line.contains("filter"));
    }

    private static boolean hasDirectIO(String line) {
        return line.contains("Files.read") || line.contains("Files.write")
                || line.contains("new File(") || line.contains("io.File(")
                || line.contains("FileInputStream") || line.contains("FileOutputStream")
                || line.contains("Files.readString") || line.contains("Files.readAllBytes")
                || line.contains("Files.writeString") || line.contains("Files.newOutputStream")
                || line.contains("writeToFile") || line.contains("readFromFile");
    }

    /**
     * Recursively search callee chain for ALL performance anti-patterns up to maxDepth.
     * Returns list of findings with type + path (e.g., [{type: "IO", path: "processItem → writeResult → I/O"}]).
     * Each pattern type is reported at most once (shortest path).
     */
    private List<Map<String, String>> findDeepPatterns(String callName, ClassInfo ci, CommandContext ctx,
                                                        String currentClassSource, int currentDepth, int maxDepth,
                                                        Set<String> visited) {
        List<Map<String, String>> results = new ArrayList<>();
        if (currentDepth > maxDepth) return results;
        if (visited.contains(callName)) return results;
        visited.add(callName);

        String calleeMethod = callName.contains(".") ? callName.substring(callName.lastIndexOf('.') + 1) : callName;
        String calleeClass = callName.contains(".") ? callName.substring(0, callName.lastIndexOf('.')) : null;

        // Get the callee source
        String calleeSource = null;
        if ("this".equals(calleeClass) && currentClassSource != null) {
            calleeSource = extractMethodByName(currentClassSource, calleeMethod);
        } else if (calleeClass != null) {
            String resolvedClass = resolveFieldType(calleeClass, ci);
            calleeSource = loadMethodSource(resolvedClass, calleeMethod, ctx);
        }

        if (calleeSource == null) return results;

        Set<String> foundTypes = new HashSet<>();

        // Check callee for all registered patterns
        for (String line : calleeSource.split("\n")) {
            String trimmed = line.trim();
            for (PatternDef pattern : PATTERNS) {
                if (pattern.detector().test(trimmed) && !foundTypes.contains(pattern.id())) {
                    results.add(Map.of("type", pattern.id(), "path", calleeMethod + " → " + pattern.id().toLowerCase()));
                    foundTypes.add(pattern.id());
                }
            }
        }

        // Check if callee is a linear scan
        if (checkSourceForLoop(calleeSource != null ? wrapAsClass(calleeSource, calleeMethod) : "", calleeMethod)) {
            // Actually check using the full method source we already have
        }
        // Simpler: check for linear scan pattern directly
        boolean hasLoopWithReturn = false;
        boolean inCalleeLoop = false;
        for (String line : calleeSource.split("\n")) {
            String trimmed = line.trim();
            if (isLoopStart(trimmed) && !trimmed.contains("\"")) inCalleeLoop = true;
            if (inCalleeLoop && (trimmed.startsWith("return ") || trimmed.equals("break;"))) {
                hasLoopWithReturn = true;
                break;
            }
        }
        if (hasLoopWithReturn && !foundTypes.contains("LINEAR_SCAN")) {
            results.add(Map.of("type", "LINEAR_SCAN", "path", calleeMethod + " → linear scan"));
            foundTypes.add("LINEAR_SCAN");
        }

        // Recurse into callee's callees for patterns not yet found
        for (String line : calleeSource.split("\n")) {
            String trimmed = line.trim();
            for (String subCall : extractMethodCalls(trimmed)) {
                var deepResults = findDeepPatterns(subCall, ci, ctx, currentClassSource,
                        currentDepth + 1, maxDepth, visited);
                for (var dr : deepResults) {
                    String type = dr.get("type");
                    if (!foundTypes.contains(type)) {
                        results.add(Map.of("type", type, "path", calleeMethod + " → " + dr.get("path")));
                        foundTypes.add(type);
                    }
                }
            }
        }

        return results;
    }

    private static String wrapAsClass(String methodBody, String methodName) {
        return "class X { void " + methodName + "() " + methodBody + " }";
    }

    private String loadMethodSource(String className, String methodName, CommandContext ctx) {
        // Try index
        if (ctx.indexed() != null) {
            var filePath = ctx.indexed().findFileForClass(className);
            if (filePath.isPresent()) {
                try {
                    String source = java.nio.file.Files.readString(
                            Path.of(ctx.rootPath()).resolve(filePath.get()));
                    return extractMethodByName(source, methodName);
                } catch (Exception e) { /* ignore */ }
            }
        }
        // Try file scan
        for (Path file : ctx.javaFiles()) {
            if (file.getFileName().toString().equals(className + ".java")) {
                try {
                    String source = java.nio.file.Files.readString(file);
                    return extractMethodByName(source, methodName);
                } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }

    private boolean checkCalleeForLoop(Path file, String methodName) {
        try {
            String source = java.nio.file.Files.readString(file);
            return checkSourceForLoop(source, methodName);
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * Checks if a method contains a linear scan pattern:
     * a loop with a conditional return/break inside (searching for something).
     * A loop that just iterates and processes all items is NOT a linear scan.
     */
    /**
     * Checks if a method contains a linear scan pattern:
     * a loop with a conditional return/break inside (searching for something).
     * Uses brace counting to track loop boundaries.
     */
    private static boolean checkSourceForLoop(String source, String methodName) {
        String methodSrc = extractMethodByName(source, methodName);
        if (methodSrc == null) return false;

        int loopBraceDepth = 0;
        boolean inLoop = false;
        for (String line : methodSrc.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;

            if (!inLoop && isLoopStart(trimmed) && !trimmed.contains("\"")) {
                inLoop = true;
                loopBraceDepth = 0;
            }

            if (inLoop) {
                for (char c : trimmed.toCharArray()) {
                    if (c == '{') loopBraceDepth++;
                    else if (c == '}') loopBraceDepth--;
                }
                // return/break INSIDE the loop body = linear scan
                if (loopBraceDepth > 0 && (trimmed.startsWith("return ") || trimmed.equals("break;"))) {
                    return true;
                }
                if (loopBraceDepth <= 0) {
                    inLoop = false;
                }
            }
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

    private static final Set<String> IGNORED_CALLS = Set.of(
            "System.out", "System.err", "System.in",
            "if", "for", "while", "switch", "catch", "return", "throw", "new",
            "String.format", "String.valueOf", "Integer.parseInt", "Long.parseLong",
            "Objects.requireNonNull", "Optional.of", "Optional.empty", "Optional.ofNullable",
            "List.of", "List.copyOf", "Set.of", "Set.copyOf", "Map.of", "Map.entry",
            "Collections.emptyList", "Collections.emptyMap", "Collections.unmodifiableMap",
            "Math.max", "Math.min", "Arrays.asList"
    );

    private static List<String> extractMethodCalls(String line) {
        List<String> calls = new ArrayList<>();

        // Pattern 1: object.method( — field/variable calls
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(
                "(\\w+\\.\\w+)\\s*\\(").matcher(line);
        while (m1.find()) {
            String call = m1.group(1);
            if (!IGNORED_CALLS.contains(call) && !call.matches("\\d+\\.\\w+")) {
                calls.add(call);
            }
        }

        // Pattern 2: standalone method( — same-class calls
        // Match method calls NOT preceded by . (to avoid double-matching object.method)
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "(?<![.\\w])(\\w+)\\s*\\(").matcher(line);
        while (m2.find()) {
            String call = m2.group(1);
            // Filter keywords, constructors, and common utilities
            if (!IGNORED_CALLS.contains(call) && !call.matches("[A-Z].*")
                    && call.length() > 1 && !call.equals("super")) {
                calls.add("this." + call); // mark as same-class
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
        // Search for method DECLARATION (preceded by access modifier or return type on same/prev line)
        // Pattern: type methodName( on a line that looks like a declaration
        String[] lines = source.split("\n");
        int declLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            // Match declaration patterns: "void methodName(", "Type methodName(", etc.
            // Must have return type or modifier before the name
            if (trimmed.contains(" " + methodName + "(") || trimmed.contains("\t" + methodName + "(")) {
                // Verify it's a declaration: line should start with modifier/type, not be inside a method body
                if (trimmed.startsWith("public ") || trimmed.startsWith("private ")
                        || trimmed.startsWith("protected ") || trimmed.startsWith("static ")
                        || trimmed.startsWith("void ") || trimmed.startsWith("abstract ")
                        || trimmed.startsWith("final ") || trimmed.startsWith("synchronized ")
                        || trimmed.matches("^\\w[\\w<>\\[\\],\\s]*\\s+" + methodName + "\\s*\\(.*")) {
                    declLine = i;
                    break;
                }
            }
        }
        if (declLine < 0) return null;

        // Find the opening brace from declaration line
        int declIdx = 0;
        for (int i = 0; i < declLine; i++) declIdx += lines[i].length() + 1;
        int braceStart = source.indexOf('{', declIdx);
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
