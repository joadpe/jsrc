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
            new MigrationDef("RECORD_CANDIDATE", "feature", 14,
                    "Class with only fields + getters could be a record",
                    MigrateCommand::hasRecordCandidate),
            new MigrationDef("INSTANCEOF_CAST", "feature", 16,
                    "instanceof + cast can use pattern matching: if (x instanceof Type t)",
                    MigrateCommand::hasInstanceofCast),

            // ─── Java 15+ features ───
            new MigrationDef("TEXT_BLOCK", "feature", 15,
                    "Multi-line string concatenation can be a text block (triple quotes)",
                    MigrateCommand::hasTextBlockCandidate),

            // ─── Deprecated APIs ───
            new MigrationDef("FINALIZE_OVERRIDE", "deprecated", 9,
                    "finalize() is deprecated — use Cleaner or try-with-resources",
                    line -> line.contains("void finalize()") || line.contains("void finalize ()")),
            new MigrationDef("THREAD_STOP", "deprecated", 1,
                    "Thread.stop()/suspend()/resume() are deprecated — use interrupt()",
                    line -> line.contains(".stop()") || line.contains(".suspend()") || line.contains(".resume()"))
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

    private int scanAllClasses(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> allResults = new ArrayList<>();
        int totalSuggestions = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byId = new LinkedHashMap<>();

        for (ClassInfo ci : allClasses) {
            // Skip test classes for speed in --all mode
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetVersion", targetVersion);
        result.put("totalSuggestions", totalSuggestions);
        result.put("classesScanned", allClasses.size());
        result.put("classesWithSuggestions", allResults.size());
        result.put("byCategory", byCategory);
        result.put("byId", byId);

        // Sort by suggestion count
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

    static boolean hasRecordCandidate(String line) {
        // Heuristic: class with "private final" fields — potential record
        // Full detection would need to check: only fields + getters + equals/hashCode
        return false; // disabled for now — needs multi-line analysis at class level
    }

    static boolean hasInstanceofCast(String line) {
        // if (x instanceof Type) { Type t = (Type) x; ...}
        return line.contains("instanceof ") && !line.contains("instanceof " + "var");
    }

    static boolean hasTextBlockCandidate(String line) {
        // String with \n concatenation
        return line.contains("\"\\n\" +") || line.contains("+ \"\\n\"")
                || (line.contains("\" +") && line.endsWith("\\n\";"));
    }
}
