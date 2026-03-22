package com.jsrc.app.analysis;

import java.util.*;
import java.util.function.Predicate;

/**
 * Registry-based line-level pattern detector.
 * Used by perf, security, and migrate commands.
 *
 * <p>To add a new pattern, add an entry to a patterns list.
 * Detection, deep search, and dedup are handled automatically.</p>
 */
public class PatternDetector {

    /**
     * A line-level pattern definition.
     */
    public record PatternDef(
            String id,
            String severity,
            String directType,
            String deepType,
            String directMessage,
            String deepPrefix,
            Predicate<String> detector
    ) {}

    // ─── Performance pattern detectors ───

    public static boolean hasDirectIO(String line) {
        return line.contains("Files.read") || line.contains("Files.write")
                || line.contains("new File(") || line.contains("io.File(")
                || line.contains("FileInputStream") || line.contains("FileOutputStream")
                || line.contains("Files.readString") || line.contains("Files.readAllBytes")
                || line.contains("Files.writeString") || line.contains("Files.newOutputStream")
                || line.contains("writeToFile") || line.contains("readFromFile");
    }

    public static boolean hasAllocation(String line) {
        return line.contains("new HashMap") || line.contains("new ArrayList")
                || line.contains("new LinkedHashMap") || line.contains("new HashSet")
                || line.contains("new LinkedList");
    }

    public static boolean hasNestedIteration(String line) {
        return line.contains(".stream()") && (line.contains("noneMatch") || line.contains("anyMatch")
                || line.contains("allMatch") || line.contains("filter"));
    }

    public static boolean hasStringConcat(String line) {
        return (line.contains("+=") && !line.contains("++") && !line.contains("+=\\s*\\d")
                && (line.contains("\"") || line.contains("String")))
                || (line.matches(".*\\w+\\s*=\\s*\\w+\\s*\\+\\s*\".*"));
    }

    public static boolean hasStringFormat(String line) {
        return line.contains("String.format(") || line.contains("String.format (");
    }

    public static boolean hasDateFormat(String line) {
        return line.contains("new SimpleDateFormat") || line.contains("new DateFormat")
                || line.contains("DateTimeFormatter.ofPattern(");
    }

    public static boolean hasRegexCompile(String line) {
        return line.contains("Pattern.compile(") || line.contains("Pattern.compile (");
    }

    public static boolean hasReflection(String line) {
        return line.contains("Class.forName(") || line.contains(".getMethod(")
                || line.contains(".getDeclaredMethod(") || line.contains(".invoke(")
                || line.contains(".newInstance(") || line.contains(".getField(")
                || line.contains(".getDeclaredField(");
    }

    public static boolean hasStreamCreate(String line) {
        return line.contains(".stream()") || line.contains(".parallelStream()");
    }

    public static boolean hasListRemove(String line) {
        return line.contains(".remove(") && !line.contains("Iterator");
    }

    public static boolean hasDbQuery(String line) {
        if (line.contains("prepareStatement(") || line.contains("executeQuery(")
                || line.contains("executeUpdate(") || line.contains("createStatement(")
                || line.contains("executeBatch(")) return true;
        if (line.contains("entityManager.find(") || line.contains("entityManager.persist(")
                || line.contains("entityManager.merge(") || line.contains("entityManager.remove(")
                || line.contains("entityManager.createQuery(") || line.contains("entityManager.createNativeQuery(")
                || line.contains(".createNamedQuery(")) return true;
        if (line.contains("repository.find") || line.contains("repository.save")
                || line.contains("repository.delete") || line.contains("Repository.find")
                || line.contains("Repo.find") || line.contains("repo.find")
                || line.contains("dao.find") || line.contains("Dao.find")) return true;
        if (line.contains(".selectFrom(") || line.contains(".insertInto(")
                || line.contains("sqlSession.select") || line.contains("sqlSession.insert")
                || line.contains("jdbcTemplate.query") || line.contains("jdbcTemplate.update")) return true;
        return false;
    }

    public static boolean hasConnection(String line) {
        return line.contains("getConnection(") || line.contains("openConnection(")
                || line.contains("openStream(") || line.contains("new Socket(")
                || line.contains("new URL(") || line.contains("DriverManager.getConnection(");
    }

    public static boolean hasBoxing(String line) {
        return line.contains("Integer.valueOf(") || line.contains("Integer.parseInt(")
                || line.contains("Long.parseLong(") || line.contains("Long.valueOf(")
                || line.contains("Double.parseDouble(") || line.contains("Double.valueOf(")
                || line.contains("Float.parseFloat(") || line.contains("Boolean.valueOf(");
    }

    public static boolean hasVectorHashtable(String line) {
        return line.contains("new Vector") || line.contains("new Hashtable")
                || line.contains("Vector<") || line.contains("Hashtable<");
    }

    public static boolean hasTableFire(String line) {
        return line.contains("fireTableDataChanged") || line.contains("fireTableRowsInserted")
                || line.contains("fireTableRowsDeleted") || line.contains("fireTableCellUpdated")
                || line.contains("fireTableStructureChanged");
    }

    public static boolean hasJndiLookup(String line) {
        return line.contains("InitialContext(") || line.contains(".lookup(")
                || line.contains("new InitialContext");
    }

    public static boolean hasSelectStar(String line) {
        return (line.contains("\"SELECT *") || line.contains("\"select *")
                || line.contains("'SELECT *") || line.contains("'select *"));
    }

    public static boolean hasSqlConcat(String line) {
        return (line.contains("\"SELECT ") || line.contains("\"INSERT ")
                || line.contains("\"UPDATE ") || line.contains("\"DELETE ")
                || line.contains("\"select ") || line.contains("\"insert ")
                || line.contains("\"update ") || line.contains("\"delete "))
                && line.contains("\" +");
    }

    public static boolean isLoopStart(String line) {
        return line.startsWith("for ") || line.startsWith("for(")
                || line.startsWith("while ") || line.startsWith("while(")
                || line.contains("for (") || line.contains("while (")
                || line.contains(".forEach(");
    }

    public static boolean hasLinearScanPattern(String methodSource) {
        boolean inLoop = false;
        int braceDepth = 0;
        for (String line : methodSource.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
            if (!inLoop && isLoopStart(trimmed) && !trimmed.contains("\"")) {
                inLoop = true;
                braceDepth = 0;
            }
            if (inLoop) {
                for (char c : trimmed.toCharArray()) {
                    if (c == '{') braceDepth++;
                    else if (c == '}') braceDepth--;
                }
                if (braceDepth > 0 && (trimmed.startsWith("return ") || trimmed.equals("break;"))) {
                    return true;
                }
                if (braceDepth <= 0) inLoop = false;
            }
        }
        return false;
    }

    // ─── Utility ───

    public static Map<String, Object> finding(String type, String severity, int line, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("severity", severity);
        f.put("line", line);
        f.put("message", message);
        return f;
    }

    public static final Set<String> IGNORED_CALLS = Set.of(
            "System.out", "System.err", "System.in",
            "if", "for", "while", "switch", "catch", "return", "throw", "new",
            "String.format", "String.valueOf", "Integer.parseInt", "Long.parseLong",
            "Objects.requireNonNull", "Optional.of", "Optional.empty", "Optional.ofNullable",
            "List.of", "List.copyOf", "Set.of", "Set.copyOf", "Map.of", "Map.entry",
            "Collections.emptyList", "Collections.emptyMap", "Collections.unmodifiableMap",
            "Math.max", "Math.min", "Arrays.asList"
    );

    public static List<String> extractMethodCalls(String line) {
        List<String> calls = new ArrayList<>();
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(
                "(\\w+\\.\\w+)\\s*\\(").matcher(line);
        while (m1.find()) {
            String call = m1.group(1);
            if (!IGNORED_CALLS.contains(call) && !call.matches("\\d+\\.\\w+")) {
                calls.add(call);
            }
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "(?<![.\\w])(\\w+)\\s*\\(").matcher(line);
        while (m2.find()) {
            String call = m2.group(1);
            if (!IGNORED_CALLS.contains(call) && !call.matches("[A-Z].*")
                    && call.length() > 1 && !call.equals("super")) {
                calls.add("this." + call);
            }
        }
        return calls;
    }
}
