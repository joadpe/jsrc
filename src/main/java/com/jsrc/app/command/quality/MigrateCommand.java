package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.ClassLookup;

/**
 * Detects Java 8 code patterns that can be modernized for a target Java version.
 * Categories: syntax, API modernization, Java EE → Jakarta, Java 14+ features.
 */
public class MigrateCommand implements Command {

    /**
     * A migration suggestion with before/after code.
     */
    public record MigrationDef(
            String id,
            String category,
            int minTarget,
            String message,
            java.util.function.Predicate<String> detector
    ) {}

    public static final List<MigrationDef> MIGRATIONS = List.of(
            // ─── Syntax (Java 7+) ───
            new MigrationDef("DIAMOND_OPERATOR", "syntax", 7,
                    "Use diamond operator: new HashMap<>() instead of explicit type args",
                    MigrateCommand::hasMissingDiamond),
            new MigrationDef("MULTICATCH", "syntax", 7,
                    "Use multi-catch: catch (A | B e) instead of separate catch blocks",
                    MigrateCommand::hasMultiCatchCandidate),
            new MigrationDef("TRY_WITH_RESOURCES", "syntax", 7,
                    "Use try-with-resources instead of try/finally/close",
                    MigrateCommand::hasTryFinallyClose),

            // ─── API modernization (Java 8+) ───
            new MigrationDef("DATE_LEGACY", "api", 8,
                    "Replace java.util.Date/Calendar/SimpleDateFormat with java.time API",
                    MigrateCommand::hasLegacyDate),
            new MigrationDef("VECTOR_USAGE", "api", 8,
                    "Replace Vector with ArrayList (or List.of() for immutable)",
                    line -> line.contains("new Vector") || line.contains("Vector<")),
            new MigrationDef("HASHTABLE_USAGE", "api", 8,
                    "Replace Hashtable with HashMap (or Map.of() for immutable)",
                    line -> line.contains("new Hashtable") || line.contains("Hashtable<")),
            new MigrationDef("STRINGBUFFER_USAGE", "api", 8,
                    "Replace StringBuffer with StringBuilder (unless shared across threads)",
                    line -> line.contains("new StringBuffer") || line.contains("StringBuffer ")),
            new MigrationDef("URL_CONSTRUCTOR", "api", 20,
                    "new URL(string) is deprecated — use URI.create(string).toURL()",
                    line -> line.contains("new URL(") && !line.contains("new URL(url")),

            // ─── Java EE → Jakarta (Java 9+) ───
            new MigrationDef("JAVAX_SERVLET", "jakarta", 9,
                    "javax.servlet.* → jakarta.servlet.* (Jakarta EE migration)",
                    line -> line.contains("javax.servlet")),
            new MigrationDef("JAVAX_PERSISTENCE", "jakarta", 9,
                    "javax.persistence.* → jakarta.persistence.* (JPA migration)",
                    line -> line.contains("javax.persistence")),
            new MigrationDef("JAVAX_EJB", "jakarta", 9,
                    "javax.ejb.* → jakarta.ejb.* (EJB migration)",
                    line -> line.contains("javax.ejb")),
            new MigrationDef("JAVAX_INJECT", "jakarta", 9,
                    "javax.inject.* → jakarta.inject.* (CDI migration)",
                    line -> line.contains("javax.inject")),
            new MigrationDef("JAVAX_WS", "jakarta", 9,
                    "javax.ws.rs.* → jakarta.ws.rs.* (JAX-RS migration)",
                    line -> line.contains("javax.ws.rs")),
            new MigrationDef("JAVAX_VALIDATION", "jakarta", 9,
                    "javax.validation.* → jakarta.validation.* (Bean Validation migration)",
                    line -> line.contains("javax.validation")),

            // ─── Java 9+ features ───
            new MigrationDef("COLLECTIONS_UNMODIFIABLE", "feature", 9,
                    "Collections.unmodifiableList/Map/Set → List.of()/Map.of()/Set.of()",
                    line -> line.contains("Collections.unmodifiableList") || line.contains("Collections.unmodifiableMap")
                            || line.contains("Collections.unmodifiableSet")),

            // ─── Java 10+ features ───
            new MigrationDef("LOCAL_VAR_TYPE", "feature", 10,
                    "Explicit local type can be replaced with var",
                    MigrateCommand::hasExplicitLocalType),

            // ─── Java 14+ features ───
            new MigrationDef("INSTANCEOF_CAST", "feature", 16,
                    "instanceof + cast can use pattern matching: if (x instanceof Type t)",
                    MigrateCommand::hasInstanceofCast),

            // ─── Java 9+ collections (from Oracle Guide) ───
            new MigrationDef("COLLECTIONS_EMPTY", "feature", 9,
                    "Use List.of()/Map.of()/Set.of() instead of Collections.empty*() — same semantics, more concise",
                    MigrateCommand::hasCollectionsEmpty),
            new MigrationDef("SYNCHRONIZED_MAP", "feature", 5,
                    "Consider ConcurrentHashMap instead of Collections.synchronizedMap()",
                    line -> line.contains("Collections.synchronizedMap(")),
            new MigrationDef("SYNCHRONIZED_LIST", "feature", 5,
                    "Consider CopyOnWriteArrayList instead of Collections.synchronizedList()",
                    line -> line.contains("Collections.synchronizedList(")),
            new MigrationDef("SYNCHRONIZED_SET", "feature", 5,
                    "Consider ConcurrentHashMap.newKeySet() or CopyOnWriteArraySet instead of Collections.synchronizedSet()",
                    line -> line.contains("Collections.synchronizedSet(")),

            // ─── Java 11+ lambda ───
            new MigrationDef("LAMBDA_VAR", "feature", 11,
                    "Use (var x, var y) -> to enable type annotations on lambda parameters",
                    MigrateCommand::hasLambdaVar),

            // ─── Java 15+ features ───
            new MigrationDef("TEXT_BLOCK", "feature", 15,
                    "Multi-line string concatenation can be a text block (triple quotes)",
                    MigrateCommand::hasTextBlockCandidate),

            // ─── Java 11+ API replacements ───
            new MigrationDef("HTTP_CLIENT", "api", 11,
                    "HttpURLConnection is legacy — use java.net.http.HttpClient (Java 11+)",
                    line -> line.contains("HttpURLConnection") || line.contains("URLConnection")),
            new MigrationDef("NASHORN_ENGINE", "deprecated", 15,
                    "Nashorn JavaScript engine removed in Java 15 — use GraalJS or another engine",
                    line -> line.contains("\"nashorn\"") || line.contains("jdk.nashorn")),

            // ─── Deprecated/removed APIs ───
            new MigrationDef("SECURITY_MANAGER", "deprecated", 17,
                    "SecurityManager is deprecated for removal — no direct replacement",
                    line -> line.contains("SecurityManager") && !line.startsWith("//") && !line.startsWith("*")),
            new MigrationDef("APPLET_REMOVED", "deprecated", 17,
                    "java.applet.* removed in Java 17 — use web technologies instead",
                    line -> line.contains("java.applet") || line.contains("extends Applet")),
            new MigrationDef("FINALIZE_OVERRIDE", "deprecated", 9,
                    "finalize() is deprecated — use Cleaner or try-with-resources",
                    line -> line.contains("void finalize()") || line.contains("void finalize ()")),
            new MigrationDef("THREAD_STOP", "deprecated", 1,
                    "Thread.stop()/suspend()/resume() are deprecated — use interrupt()",
                    line -> line.contains(".stop()") || line.contains(".suspend()") || line.contains(".resume()")),

            // ─── Java 9-16 improvements (from Oracle Migration Guide) ───
            new MigrationDef("MAP_GET_OR_DEFAULT", "feature", 9,
                    "Use map.getOrDefault(key, default) instead of containsKey + get",
                    MigrateCommand::hasMapGetOrDefault),
            new MigrationDef("OPTIONAL_OR_ELSE", "feature", 9,
                    "Use opt.orElse(default) instead of isPresent() ? get() : default",
                    MigrateCommand::hasOptionalOrElse),
            new MigrationDef("STRING_STRIP", "feature", 11,
                    "Consider String.strip() instead of trim() — strip() handles Unicode whitespace (U+2000..U+200A, etc.)",
                    MigrateCommand::hasStringStrip),
            new MigrationDef("STREAM_TO_LIST", "feature", 16,
                    "Use stream.toList() instead of stream.collect(Collectors.toList())",
                    MigrateCommand::hasStreamToList)
    );

    private final int targetVersion;
    private final boolean scanAll;
    private final String targetClass;

    public MigrateCommand(String targetClass, int targetVersion, boolean scanAll) {
        this.targetClass = targetClass;
        this.targetVersion = targetVersion;
        this.scanAll = scanAll;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (scanAll) {
            return scanAllClasses(ctx);
        }

        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, targetClass);
        if (ci == null) return 0;

        String source = SourceResolver.loadClassSource(ci.name(), ctx);
        var result = scanClass(ci, source);
        ctx.formatter().printResult(result);

        @SuppressWarnings("unchecked")
        var suggestions = (List<?>) result.get("suggestions");
        return suggestions != null ? suggestions.size() : 0;
    }

    /**
     * Computes migration suggestions for all classes and returns as map path → suggestions.
     * Used by IndexCommand to pre-compute and cache in index.bin.
     */
    public Map<String, List<int[]>> computeAllForIndex(CommandContext ctx) {
        Map<String, List<int[]>> result = new LinkedHashMap<>();
        for (ClassInfo ci : ctx.getAllClasses()) {
            String source = SourceResolver.loadClassSource(ci.name(), ctx);
            if (source == null) continue;
            var suggestions = scanSource(source, ci);
            if (!suggestions.isEmpty()) {
                String path = ctx.indexed() != null
                        ? ctx.indexed().findFileForClass(ci.name()).orElse(ci.name())
                        : ci.name();
                List<int[]> compact = suggestions.stream()
                        .map(s -> new int[]{patternIndex(s.get("id").toString()), (int) s.get("line")})
                        .toList();
                result.put(path, compact);
            }
        }
        return result;
    }

    private int patternIndex(String id) {
        for (int i = 0; i < MIGRATIONS.size(); i++) {
            if (MIGRATIONS.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private int scanAllClasses(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> allResults = new ArrayList<>();
        int totalSuggestions = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byId = new LinkedHashMap<>();

        // Try cache first
        if (ctx.indexed() != null && ctx.indexed().hasCachedMigrations()) {
            for (var entry : ctx.indexed().getAllMigrations().entrySet()) {
                List<Map<String, Object>> suggestions = new ArrayList<>();
                for (var mig : entry.getValue()) {
                    if (mig.patternId() >= 0 && mig.patternId() < MIGRATIONS.size()) {
                        MigrationDef def = MIGRATIONS.get(mig.patternId());
                        if (def.minTarget() > targetVersion) continue;
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("id", def.id());
                        s.put("category", def.category());
                        s.put("line", mig.line());
                        s.put("message", def.message());
                        s.put("minVersion", def.minTarget());
                        suggestions.add(s);
                    }
                }
                if (!suggestions.isEmpty()) {
                    Map<String, Object> classResult = new LinkedHashMap<>();
                    classResult.put("class", entry.getKey());
                    classResult.put("suggestions", suggestions);
                    allResults.add(classResult);
                    totalSuggestions += suggestions.size();
                    for (var s : suggestions) {
                        byCategory.merge(s.get("category").toString(), 1, Integer::sum);
                        byId.merge(s.get("id").toString(), 1, Integer::sum);
                    }
                }
            }
            return finishAllResult(allResults, totalSuggestions, byCategory, byId, allClasses.size(), ctx);
        }

        // Fallback: source scan
        for (ClassInfo ci : allClasses) {
            if (scanAll && (ci.name().endsWith("Test") || ci.name().endsWith("Tests")
                    || ci.qualifiedName().contains(".test."))) continue;

            String source = SourceResolver.loadClassSource(ci.name(), ctx);
            if (source == null) continue;
            var suggestions = scanSource(source, ci);
            if (!suggestions.isEmpty()) {
                Map<String, Object> classResult = new LinkedHashMap<>();
                classResult.put("class", ci.qualifiedName());
                classResult.put("suggestions", suggestions);
                allResults.add(classResult);
                totalSuggestions += suggestions.size();

                for (var s : suggestions) {
                    byCategory.merge(s.get("category").toString(), 1, Integer::sum);
                    byId.merge(s.get("id").toString(), 1, Integer::sum);
                }
            }
        }

        return finishAllResult(allResults, totalSuggestions, byCategory, byId, allClasses.size(), ctx);
    }

    private int finishAllResult(List<Map<String, Object>> allResults, int totalSuggestions,
                                 Map<String, Integer> byCategory, Map<String, Integer> byId,
                                 int classesScanned, CommandContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetVersion", targetVersion);
        result.put("totalSuggestions", totalSuggestions);
        result.put("classesScanned", classesScanned);
        result.put("classesWithSuggestions", allResults.size());
        result.put("byCategory", byCategory);
        result.put("byId", byId);

        allResults.sort((a, b) -> {
            @SuppressWarnings("unchecked")
            var sa = (List<?>) a.get("suggestions");
            @SuppressWarnings("unchecked")
            var sb = (List<?>) b.get("suggestions");
            return Integer.compare(sb.size(), sa.size());
        });

        if (allResults.size() > 30) {
            result.put("classes", allResults.subList(0, 30));
            result.put("truncated", true);
        } else {
            result.put("classes", allResults);
        }

        ctx.formatter().printResult(result);
        return totalSuggestions;
    }

    private Map<String, Object> scanClass(ClassInfo ci, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", ci.qualifiedName());
        result.put("targetVersion", targetVersion);

        var suggestions = source != null ? scanSource(source, ci) : List.<Map<String, Object>>of();
        result.put("suggestions", suggestions);
        result.put("totalSuggestions", suggestions.size());

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (var s : suggestions) byCategory.merge(s.get("category").toString(), 1, Integer::sum);
        result.put("byCategory", byCategory);

        return result;
    }

    /** Index-based migrations that don't need source (fast). */
    private static final Set<String> INDEX_BASED = Set.of(
            "JAVAX_SERVLET", "JAVAX_PERSISTENCE", "JAVAX_EJB", "JAVAX_INJECT",
            "JAVAX_WS", "JAVAX_VALIDATION", "FINALIZE_OVERRIDE", "VECTOR_USAGE",
            "HASHTABLE_USAGE", "STRINGBUFFER_USAGE", "COLLECTIONS_UNMODIFIABLE"
    );

    private List<Map<String, Object>> scanFromIndex(ClassInfo ci) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        for (MigrationDef mig : MIGRATIONS) {
            if (mig.minTarget() > targetVersion) continue;
            if (!INDEX_BASED.contains(mig.id())) continue;

            // Check imports for javax.* patterns
            if (mig.id().startsWith("JAVAX_")) {
                String prefix = switch (mig.id()) {
                    case "JAVAX_SERVLET" -> "javax.servlet";
                    case "JAVAX_PERSISTENCE" -> "javax.persistence";
                    case "JAVAX_EJB" -> "javax.ejb";
                    case "JAVAX_INJECT" -> "javax.inject";
                    case "JAVAX_WS" -> "javax.ws.rs";
                    case "JAVAX_VALIDATION" -> "javax.validation";
                    default -> "";
                };
                if (!prefix.isEmpty()) {
                    for (var field : ci.fields()) {
                        // fields won't have imports, but we check other signals
                    }
                    // Use the detector as fallback — it checks line content
                    // For index mode, check class name/package for javax references
                    if (ci.qualifiedName().contains("javax.") || 
                        ci.superClass().contains("javax.")) {
                        suggestions.add(indexSuggestion(mig));
                    }
                }
            }

            // Check fields for Vector/Hashtable/StringBuffer
            for (var field : ci.fields()) {
                if (mig.id().equals("VECTOR_USAGE") && 
                    (field.type().equals("Vector") || field.type().startsWith("Vector<"))) {
                    suggestions.add(indexSuggestion(mig));
                    break;
                }
                if (mig.id().equals("HASHTABLE_USAGE") &&
                    (field.type().equals("Hashtable") || field.type().startsWith("Hashtable<"))) {
                    suggestions.add(indexSuggestion(mig));
                    break;
                }
                if (mig.id().equals("STRINGBUFFER_USAGE") &&
                    (field.type().equals("StringBuffer") || field.type().startsWith("StringBuffer"))) {
                    suggestions.add(indexSuggestion(mig));
                    break;
                }
            }

            // Check methods for finalize
            if (mig.id().equals("FINALIZE_OVERRIDE")) {
                for (var method : ci.methods()) {
                    if (method.name().equals("finalize") && method.parameters().isEmpty()) {
                        suggestions.add(indexSuggestion(mig));
                        break;
                    }
                }
            }
        }
        return suggestions;
    }

    private Map<String, Object> indexSuggestion(MigrationDef mig) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", mig.id());
        s.put("category", mig.category());
        s.put("line", 0);
        s.put("message", mig.message());
        s.put("minVersion", mig.minTarget());
        return s;
    }

    private List<Map<String, Object>> scanSource(String source, ClassInfo ci) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        String[] lines = source.split("\n");
        Set<String> reported = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = i + 1;

            if (line.startsWith("//") || line.startsWith("*")) continue;

            for (MigrationDef mig : MIGRATIONS) {
                if (mig.minTarget() > targetVersion) continue;
                // all patterns checked via source scan
                String key = mig.id() + ":" + lineNum;
                if (mig.detector().test(line) && !reported.contains(key)) {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    suggestion.put("id", mig.id());
                    suggestion.put("category", mig.category());
                    suggestion.put("line", lineNum);
                    suggestion.put("message", mig.message());
                    suggestion.put("minVersion", mig.minTarget());
                    suggestions.add(suggestion);
                    reported.add(key);
                }
            }
        }

        return suggestions;
    }

    // ─── Detectors ───

    static boolean hasMissingDiamond(String line) {
        // new HashMap<String, List<Integer>>() → should be new HashMap<>()
        return line.contains("new ") && !line.contains("<>")
                && line.matches(".*new\\s+\\w+<.+>\\s*\\(.*")
                && (line.contains("HashMap") || line.contains("ArrayList") || line.contains("HashSet")
                || line.contains("LinkedHashMap") || line.contains("TreeMap") || line.contains("LinkedList")
                || line.contains("Vector") || line.contains("Hashtable"));
    }

    static boolean hasMultiCatchCandidate(String line) {
        // Detects "} catch (" which could be merged with adjacent catches
        // This is a heuristic — full detection needs multi-line analysis
        return line.startsWith("} catch (") || line.startsWith("catch (");
    }

    static boolean hasTryFinallyClose(String line) {
        return line.contains("} finally {") || (line.contains("finally") && line.contains("{"));
    }

    static boolean hasLegacyDate(String line) {
        return line.contains("new Date()") || line.contains("new Date(")
                || line.contains("Calendar.getInstance") || line.contains("new GregorianCalendar")
                || line.contains("new SimpleDateFormat") || line.contains("DateFormat.get");
    }

    static boolean hasExplicitLocalType(String line) {
        // Very conservative: only flag obvious cases like "String x = ..."
        // where the type is on both sides
        return line.matches("^\\s*(String|Integer|Long|Double|Boolean|List|Map|Set)\\s+\\w+\\s*=\\s*new\\s+.*")
                || line.matches("^\\s*(String|Integer|Long|Double|Boolean)\\s+\\w+\\s*=\\s*\\w+\\..*");
    }

    static boolean hasInstanceofCast(String line) {
        // if (x instanceof Type) { Type t = (Type) x; ...}
        // Exclude lines that already use pattern matching: instanceof Type varName
        if (!line.contains("instanceof ")) return false;
        // Pattern matching: "instanceof TypeName variableName" (two words after instanceof)
        // Old style: "instanceof TypeName)" or "instanceof TypeName &&"
        return line.matches(".*instanceof\\s+\\w+\\s*[)&|{].*");
    }

    static boolean hasTextBlockCandidate(String line) {
        // String with \n concatenation
        return line.contains("\"\\n\" +") || line.contains("+ \"\\n\"")
                || (line.contains("\" +") && line.endsWith("\\n\";"));
    }

    static boolean hasMapGetOrDefault(String line) {
        // map.containsKey(k) ? map.get(k) : default → map.getOrDefault(k, default)
        // Only match ternary pattern: containsKey + ? + .get( on same line
        return line.contains("containsKey") && line.contains("?") && line.contains(".get(");
    }

    static boolean hasOptionalOrElse(String line) {
        // opt.isPresent() ? opt.get() : default → opt.orElse(default)
        // Only match ternary pattern: isPresent() + ? + .get() on same line
        return line.contains(".isPresent()") && line.contains("?") && line.contains(".get()");
    }

    static boolean hasStringStrip(String line) {
        // .trim() → .strip() (Unicode-aware since Java 11)
        return line.contains(".trim()");
    }

    static boolean hasStreamToList(String line) {
        // stream.collect(Collectors.toList()) → stream.toList()
        return line.contains("Collectors.toList()") || line.contains("Collectors.toSet()");
    }

    static boolean hasCollectionsEmpty(String line) {
        return line.contains("Collections.emptyList()")
                || line.contains("Collections.emptyMap()")
                || line.contains("Collections.emptySet()");
    }

    static boolean hasLambdaVar(String line) {
        // (x, y) -> without type declarations — can use (var x, var y) ->
        // Untyped params start with lowercase. Exclude typed lambdas (String x, int y) ->
        if (!line.contains("->")) return false;
        return line.matches(".*\\([a-z_][a-zA-Z0-9_]*\\s*,\\s*[a-z_][a-zA-Z0-9_]*\\)\\s*->.*");
    }
}
