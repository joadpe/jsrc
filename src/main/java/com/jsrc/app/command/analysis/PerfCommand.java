package com.jsrc.app.command.analysis;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;
import java.nio.file.Path;
import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.ClassResolver;
import com.jsrc.app.analysis.DeepAnalyzer;
import com.jsrc.app.analysis.PatternDetector;
import com.jsrc.app.analysis.PatternDetector.PatternDef;
import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.ClassLookup;

/**
 * Detects performance bottlenecks in methods using pattern registry + deep search.
 * Uses shared infrastructure: PatternDetector, DeepAnalyzer, SourceResolver, ClassResolver.
 */
public class PerfCommand implements Command {

    /** Performance patterns — all search recursively via CallGraph with dedup. */
    public static final List<PatternDef> PERF_PATTERNS = List.of(
            new PatternDef("IO", "CRITICAL", "LOOP_WITH_IO", "LOOP_WITH_DEEP_IO",
                    "I/O operation inside loop — moves bottleneck from CPU to disk", "IO in call chain",
                    PatternDetector::hasDirectIO),
            new PatternDef("ALLOCATION", "WARNING", "ALLOCATION_IN_LOOP", "LOOP_WITH_DEEP_ALLOCATION",
                    "Object allocation inside loop — consider pre-allocating or reusing", "Allocation in call chain",
                    PatternDetector::hasAllocation),
            new PatternDef("NESTED", "WARNING", "NESTED_ITERATION", "LOOP_WITH_DEEP_NESTED",
                    "Nested iteration — consider using a Set for O(1) lookup", "Nested iteration in call chain",
                    PatternDetector::hasNestedIteration),
            new PatternDef("STRING_CONCAT", "WARNING", "STRING_CONCAT_IN_LOOP", "LOOP_WITH_DEEP_STRING_CONCAT",
                    "String concatenation in loop — O(N²) due to String immutability, use StringBuilder", "String concat in call chain",
                    PatternDetector::hasStringConcat),
            new PatternDef("STRING_FORMAT", "INFO", "STRING_FORMAT_IN_LOOP", "LOOP_WITH_DEEP_STRING_FORMAT",
                    "String.format() in loop — parses format string on every call, use StringBuilder", "String.format in call chain",
                    PatternDetector::hasStringFormat),
            new PatternDef("DATE_FORMAT", "WARNING", "DATE_FORMAT_IN_LOOP", "LOOP_WITH_DEEP_DATE_FORMAT",
                    "DateFormat/SimpleDateFormat created in loop — heavy object, reuse or use DateTimeFormatter", "DateFormat in call chain",
                    PatternDetector::hasDateFormat),
            new PatternDef("REGEX_COMPILE", "WARNING", "REGEX_COMPILE_IN_LOOP", "LOOP_WITH_DEEP_REGEX_COMPILE",
                    "Pattern.compile() in loop — compile once as static final, reuse", "Regex compile in call chain",
                    PatternDetector::hasRegexCompile),
            new PatternDef("REFLECTION", "CRITICAL", "REFLECTION_IN_LOOP", "LOOP_WITH_DEEP_REFLECTION",
                    "Reflection in loop — Class.forName/getMethod/invoke are slow, cache results", "Reflection in call chain",
                    PatternDetector::hasReflection),
            new PatternDef("STREAM_CREATE", "WARNING", "STREAM_IN_LOOP", "LOOP_WITH_DEEP_STREAM",
                    "Stream created in loop — creates N streams + N iterators, consider traditional loop", "Stream in call chain",
                    PatternDetector::hasStreamCreate),
            new PatternDef("LIST_REMOVE", "WARNING", "LIST_REMOVE_IN_LOOP", "LOOP_WITH_DEEP_LIST_REMOVE",
                    "List.remove() in loop — O(N²) due to element shift, use Iterator.remove() or removeIf()", "List.remove in call chain",
                    PatternDetector::hasListRemove),
            new PatternDef("DB_QUERY", "CRITICAL", "DB_QUERY_IN_LOOP", "LOOP_WITH_DEEP_DB_QUERY",
                    "Database query in loop — N+1 problem, use batch query or JOIN", "DB query in call chain",
                    PatternDetector::hasDbQuery),
            new PatternDef("BOXING", "INFO", "BOXING_IN_LOOP", "LOOP_WITH_DEEP_BOXING",
                    "Autoboxing in loop — GC pressure from temporary wrapper objects", "Boxing in call chain",
                    PatternDetector::hasBoxing),
            new PatternDef("VECTOR_HASHTABLE", "WARNING", "VECTOR_HASHTABLE_IN_LOOP", "LOOP_WITH_DEEP_VECTOR_HASHTABLE",
                    "Vector/Hashtable in loop — synchronized on every operation, use ArrayList/HashMap", "Vector/Hashtable in call chain",
                    PatternDetector::hasVectorHashtable),
            new PatternDef("TABLE_FIRE", "CRITICAL", "TABLE_FIRE_IN_LOOP", "LOOP_WITH_DEEP_TABLE_FIRE",
                    "fireTableDataChanged/fireTableRowsInserted in loop — repaints table N times", "Table fire in call chain",
                    PatternDetector::hasTableFire),
            new PatternDef("JNDI_LOOKUP", "CRITICAL", "JNDI_IN_LOOP", "LOOP_WITH_DEEP_JNDI",
                    "JNDI lookup in loop — each lookup is a server-side search, cache the result", "JNDI lookup in call chain",
                    PatternDetector::hasJndiLookup),
            new PatternDef("SELECT_STAR", "WARNING", "SELECT_STAR_IN_LOOP", "LOOP_WITH_DEEP_SELECT_STAR",
                    "SELECT * in loop — fetches unnecessary columns, especially bad with LOB/CLOB", "SELECT * in call chain",
                    PatternDetector::hasSelectStar),
            new PatternDef("SQL_CONCAT", "CRITICAL", "SQL_CONCAT_IN_LOOP", "LOOP_WITH_DEEP_SQL_CONCAT",
                    "SQL string concatenation — prevents cursor reuse in Oracle + SQL injection risk", "SQL concat in call chain",
                    PatternDetector::hasSqlConcat),
            new PatternDef("CONNECTION", "CRITICAL", "CONNECTION_IN_LOOP", "LOOP_WITH_DEEP_CONNECTION",
                    "Connection/stream opened in loop — use connection pool or batch operation", "Connection in call chain",
                    PatternDetector::hasConnection)
    );

    /** Fix suggestions for each perf pattern type. */
    private static final Map<String, String> FIX_SUGGESTIONS = Map.ofEntries(
            Map.entry("IO", "Move I/O outside the loop: read/write in bulk, then process in-memory"),
            Map.entry("ALLOCATION", "Pre-allocate before loop: `var list = new ArrayList<>(size);`"),
            Map.entry("NESTED", "Replace inner list with Set/Map for O(1) lookup: `var set = new HashSet<>(list);`"),
            Map.entry("STRING_CONCAT", "Replace `+=` with StringBuilder: `var sb = new StringBuilder(); sb.append(x);`"),
            Map.entry("STRING_FORMAT", "Replace String.format() with StringBuilder or MessageFormat (pre-parsed)"),
            Map.entry("DATE_FORMAT", "Create DateTimeFormatter once as static final: `private static final DateTimeFormatter FMT = ...;`"),
            Map.entry("REGEX_COMPILE", "Compile Pattern once as static final: `private static final Pattern PAT = Pattern.compile(...);`"),
            Map.entry("REFLECTION", "Cache Method/Field references: `private static final Method M = Foo.class.getMethod(...);`"),
            Map.entry("STREAM_CREATE", "Replace stream with for-loop when inside another loop"),
            Map.entry("LIST_REMOVE", "Use `list.removeIf(predicate)` or Iterator: `var it = list.iterator(); while (it.hasNext()) { if (...) it.remove(); }`"),
            Map.entry("DB_QUERY", "Batch query: replace N queries with IN clause or JOIN: `WHERE id IN (:ids)`"),
            Map.entry("BOXING", "Use primitive arrays/collections (IntStream, int[]) to avoid autoboxing"),
            Map.entry("VECTOR_HASHTABLE", "Replace Vector with ArrayList, Hashtable with HashMap"),
            Map.entry("TABLE_FIRE", "Batch UI updates: call fireTableDataChanged() once after all mutations"),
            Map.entry("JNDI_LOOKUP", "Cache JNDI result: `private final DataSource ds = (DataSource) ctx.lookup(...);`"),
            Map.entry("SELECT_STAR", "Specify columns: `SELECT col1, col2 FROM ...` instead of `SELECT *`"),
            Map.entry("SQL_CONCAT", "Use PreparedStatement with `?` parameters instead of string concatenation"),
            Map.entry("CONNECTION", "Use connection pool (HikariCP) and try-with-resources: `try (var conn = pool.getConnection()) {}`")
    );

    private final String target;
    private final int maxDepth;
    private final boolean fix;

    public PerfCommand(String target, int maxDepth) {
        this(target, maxDepth, false);
    }

    public PerfCommand(String target, int maxDepth, boolean fix) {
        this.target = target;
        this.maxDepth = maxDepth;
        this.fix = fix;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

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

        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        String sourceCode = SourceResolver.loadClassSource(ci.name(), ctx);

        CallGraph graph = ctx.callGraph();

        if (methodName != null) {
            MethodInfo mi = ci.methods().stream()
                    .filter(m -> m.name().equals(methodName))
                    .findFirst().orElse(null);
            if (mi == null) {
                System.err.println("Method '" + methodName + "' not found in " + ci.name());
                return 0;
            }
            Map<String, Object> result = analyzeMethod(ci, mi, sourceCode, graph, ctx, 0);
            ctx.formatter().printResultWithHints(result, buildHints());
            return 1;
        } else {
            Map<String, Object> result = analyzeClass(ci, sourceCode, graph, ctx);
            ctx.formatter().printResultWithHints(result, buildHints());
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

        // Structural issues (class-level)
        List<Map<String, Object>> structuralFindings = detectStructuralIssues(ci, sourceCode);
        totalFindings += structuralFindings.size();

        // Enrich findings with fix suggestions when --fix is enabled
        if (fix) {
            for (var mResult2 : perMethod) {
                @SuppressWarnings("unchecked")
                var fs = (List<Map<String, Object>>) mResult2.get("findings");
                if (fs != null) {
                    for (var f : fs) {
                        String type = String.valueOf(f.get("type"));
                        for (var pat : PERF_PATTERNS) {
                            if (type.equals(pat.directType()) || type.equals(pat.deepType())) {
                                String fixSuggestion = FIX_SUGGESTIONS.get(pat.id());
                                if (fixSuggestion != null) f.put("fix", fixSuggestion);
                                break;
                            }
                        }
                    }
                }
            }
            for (var sf : structuralFindings) {
                String type = String.valueOf(sf.get("type"));
                for (var pat : PERF_PATTERNS) {
                    if (type.equals(pat.directType()) || type.equals(pat.deepType())
                            || type.equals(pat.id())) {
                        String fixSuggestion = FIX_SUGGESTIONS.get(pat.id());
                        if (fixSuggestion != null) sf.put("fix", fixSuggestion);
                        break;
                    }
                }
            }
        }

        result.put("methods", ci.methods().size());
        result.put("totalFindings", totalFindings);
        if (worstMethod != null) result.put("worstMethod", worstMethod);
        result.put("perMethod", perMethod);
        if (!structuralFindings.isEmpty()) {
            result.put("structuralIssues", structuralFindings);
        }
        return result;
    }

    private Map<String, Object> analyzeMethod(ClassInfo ci, MethodInfo mi, String sourceCode,
                                               CallGraph graph, CommandContext ctx, int currentDepth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", ci.name() + "." + mi.name());
        result.put("line", mi.startLine());
        int loc = (mi.startLine() > 0 && mi.endLine() > mi.startLine())
                ? mi.endLine() - mi.startLine() : 0;
        result.put("complexity", 0);
        result.put("loc", loc);

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> tree = new ArrayList<>();

        String methodSource = null;
        if (sourceCode != null && currentDepth <= 1) {
            methodSource = SourceResolver.extractMethodSource(sourceCode, mi.startLine(), mi.endLine());
        }

        if (methodSource != null) {
            analyzeSource(methodSource, sourceCode, mi, ci, findings, tree, graph, ctx, currentDepth);
            result.put("analysis", "source");
        } else {
            analyzeFromIndex(mi, ci, findings, graph);
            result.put("analysis", "index-only");
        }

        result.put("findings", findings);
        result.put("tree", tree);
        return result;
    }

    private void analyzeSource(String methodSource, String fullClassSource, MethodInfo mi, ClassInfo ci,
                                List<Map<String, Object>> findings, List<Map<String, Object>> tree,
                                CallGraph graph, CommandContext ctx, int currentDepth) {
        String[] lines = methodSource.split("\n");
        boolean inLoop = false;
        int loopDepth = 0;
        List<Map<String, Object>> loopCalls = null;
        int loopLine = 0;
        Set<String> loopReported = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = mi.startLine() + i;

            if (PatternDetector.isLoopStart(line)) {
                if (!inLoop) {
                    inLoop = true;
                    loopLine = lineNum;
                    loopCalls = new ArrayList<>();
                    loopReported = new HashSet<>();
                }
                loopDepth++;
            }

            if (inLoop) {
                // Direct pattern detection
                for (PatternDef pattern : PERF_PATTERNS) {
                    String directKey = pattern.id() + ":direct:" + lineNum;
                    if (pattern.detector().test(line) && !loopReported.contains(directKey)) {
                        findings.add(PatternDetector.finding(pattern.directType(), pattern.severity(), lineNum,
                                pattern.directMessage()));
                        loopReported.add(directKey);
                        loopReported.add(pattern.id() + ":direct:block");
                    }
                }

                // Deep search into callees
                if (currentDepth < maxDepth) {
                    for (String callName : PatternDetector.extractMethodCalls(line)) {
                        Map<String, Object> callNode = new LinkedHashMap<>();
                        callNode.put("method", callName);
                        callNode.put("line", lineNum);

                        List<String> flags = new ArrayList<>();
                        Set<String> visited = new HashSet<>();

                        var deepFindings = DeepAnalyzer.findDeepPatterns(callName, ci, ctx, fullClassSource,
                                currentDepth + 1, maxDepth, visited, PERF_PATTERNS);

                        for (var df : deepFindings) {
                            String type = df.get("type").toString();
                            String path = df.get("path").toString();
                            if (loopReported.contains(type + ":direct:block")) continue;
                            String dedupKey = type + ":" + path;
                            if (!loopReported.contains(dedupKey)) {
                                String deepType = PERF_PATTERNS.stream()
                                        .filter(p -> p.id().equals(type))
                                        .map(PatternDef::deepType)
                                        .findFirst().orElse("LOOP_WITH_DEEP_" + type);
                                flags.add("🔴 DEEP " + type + ": " + path);
                                findings.add(PatternDetector.finding(deepType, "CRITICAL", lineNum,
                                        type + " in call chain: " + callName + " → " + path));
                                loopReported.add(dedupKey);
                            }
                        }

                        callNode.put("flags", flags);
                        if (loopCalls != null) loopCalls.add(callNode);
                    }
                }
            }

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

    private List<Map<String, Object>> detectStructuralIssues(ClassInfo ci, String sourceCode) {
        List<Map<String, Object>> findings = new ArrayList<>();
        if (sourceCode == null) return findings;
        String[] lines = sourceCode.split("\n");
        boolean hasThreadLocal = false;
        boolean hasThreadLocalRemove = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = i + 1;

            if ((line.contains("static") && !line.contains("final"))
                    && (line.contains("List<") || line.contains("Map<") || line.contains("Set<")
                    || line.contains("ArrayList") || line.contains("HashMap") || line.contains("HashSet"))
                    && (line.contains("new ") || line.contains("= new"))) {
                findings.add(PatternDetector.finding("STATIC_MUTABLE_COLLECTION", "CRITICAL", lineNum,
                        "Static mutable collection without bound — potential memory leak"));
            }

            if (line.contains("static") && line.contains("Map")
                    && (line.contains("new HashMap") || line.contains("new ConcurrentHashMap")
                    || line.contains("new LinkedHashMap")) && !line.contains("final")) {
                findings.add(PatternDetector.finding("UNBOUNDED_CACHE", "WARNING", lineNum,
                        "Static Map as cache without eviction — memory grows unbounded"));
            }

            if (line.contains("ThreadLocal")) hasThreadLocal = true;
            if (line.contains(".remove()")) hasThreadLocalRemove = true;

            if (line.contains("void finalize()") || line.contains("void finalize ()")) {
                findings.add(PatternDetector.finding("FINALIZE_METHOD", "CRITICAL", lineNum,
                        "finalize() overridden — delays GC, deprecated since Java 9, use Cleaner or try-with-resources"));
            }

            if (line.contains("new StringBuffer")) {
                findings.add(PatternDetector.finding("STRINGBUFFER_SINGLE", "INFO", lineNum,
                        "StringBuffer used — prefer StringBuilder (not synchronized, faster)"));
            }

            if (line.contains("new Vector") || line.contains("new Hashtable")) {
                findings.add(PatternDetector.finding("VECTOR_HASHTABLE", "WARNING", lineNum,
                        "Vector/Hashtable — synchronized on every operation, use ArrayList/HashMap"));
            }

            if (line.contains("actionPerformed") || line.contains("mouseClicked")
                    || line.contains("keyPressed") || line.contains("keyReleased")
                    || line.contains("windowOpened") || line.contains("stateChanged")) {
                for (int j = i + 1; j < Math.min(i + 50, lines.length); j++) {
                    String innerLine = lines[j].trim();
                    if (innerLine.contains("}") && !innerLine.contains("{")) break;
                    if (PatternDetector.hasDbQuery(innerLine) || PatternDetector.hasDirectIO(innerLine)
                            || innerLine.contains("Thread.sleep")) {
                        findings.add(PatternDetector.finding("EDT_BLOCKING", "CRITICAL", j + 1,
                                "Blocking operation in Swing listener — freezes UI. Use SwingWorker"));
                        break;
                    }
                }
            }

            if (line.contains("prepareStatement(") || line.contains("createStatement(")) {
                boolean hasTryWithResources = false;
                boolean hasClose = false;
                for (int j = Math.max(0, i - 3); j <= i; j++) {
                    if (lines[j].trim().contains("try (")) hasTryWithResources = true;
                }
                for (int j = i + 1; j < Math.min(i + 30, lines.length); j++) {
                    if (lines[j].trim().contains(".close()")) { hasClose = true; break; }
                    if (lines[j].trim().contains("} finally")) { hasClose = true; break; }
                }
                if (!hasTryWithResources && !hasClose) {
                    findings.add(PatternDetector.finding("CURSOR_LEAK", "CRITICAL", lineNum,
                            "PreparedStatement without close or try-with-resources — Oracle ORA-01000 cursor leak"));
                }
            }

            if (PatternDetector.hasSqlConcat(line)) {
                findings.add(PatternDetector.finding("SQL_CONCAT", "CRITICAL", lineNum,
                        "SQL string concatenation — prevents cursor reuse in Oracle + SQL injection risk"));
            }
        }

        if (hasThreadLocal && !hasThreadLocalRemove) {
            findings.add(PatternDetector.finding("THREADLOCAL_LEAK", "CRITICAL", 0,
                    "ThreadLocal without remove() — classloader leak in JBoss/Tomcat"));
        }

        return findings;
    }

    private void analyzeFromIndex(MethodInfo mi, ClassInfo ci,
                                   List<Map<String, Object>> findings, CallGraph graph) {
        int loc = mi.endLine() - mi.startLine();
        if (loc > 50 && loc < 5000 && mi.startLine() > 0) {
            findings.add(PatternDetector.finding("LARGE_METHOD", "INFO", mi.startLine(),
                    loc + " LOC (threshold: 50)"));
        }

        Set<MethodReference> refs = graph.findMethodsByName(mi.name());
        for (MethodReference ref : refs) {
            if (ref.className().equals(ci.name())) {
                int callerCount = graph.getCallersOf(ref).size();
                if (callerCount > 100) {
                    findings.add(PatternDetector.finding("HOT_METHOD", "INFO", mi.startLine(),
                            callerCount + " callers — optimize for performance"));
                }
            }
        }
    }

    private List<CommandHint> buildHints() {
        return java.util.List.of(
            new CommandHint("read " + target + ".METHOD", "Read the bottleneck method"),
            new CommandHint("callers " + target, "Who triggers this bottleneck?")
        );
    }
}
