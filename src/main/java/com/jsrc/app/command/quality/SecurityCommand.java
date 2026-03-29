package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;
import java.util.*;

import com.jsrc.app.analysis.DeepAnalyzer;
import com.jsrc.app.analysis.PatternDetector;
import com.jsrc.app.analysis.PatternDetector.PatternDef;
import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.ClassLookup;

/**
 * Static Application Security Testing (SAST).
 * Detects common security vulnerabilities in Java source code.
 * Uses the same PatternDef registry + deep search as PerfCommand.
 */
public class SecurityCommand implements Command {

    /** Security vulnerability patterns. */
    public static final List<PatternDef> SECURITY_PATTERNS = List.of(
            new PatternDef("SQL_INJECTION", "CRITICAL",
                    "SQL_INJECTION", "DEEP_SQL_INJECTION",
                    "SQL string concatenation — use PreparedStatement with bind variables (CWE-89)",
                    "SQL injection in call chain",
                    PatternDetector::hasSqlConcat),
            new PatternDef("PATH_TRAVERSAL", "CRITICAL",
                    "PATH_TRAVERSAL", "DEEP_PATH_TRAVERSAL",
                    "User input in file path without sanitization — path traversal risk (CWE-22)",
                    "Path traversal in call chain",
                    SecurityCommand::hasPathTraversal),
            new PatternDef("JNDI_INJECTION", "CRITICAL",
                    "JNDI_INJECTION", "DEEP_JNDI_INJECTION",
                    "JNDI lookup with dynamic input — remote code execution risk (CWE-917)",
                    "JNDI injection in call chain",
                    PatternDetector::hasJndiLookup),
            new PatternDef("XXE", "CRITICAL",
                    "XXE", "DEEP_XXE",
                    "XML parser without secure configuration — XML External Entity injection (CWE-611)",
                    "XXE in call chain",
                    SecurityCommand::hasXxe),
            new PatternDef("DESERIALIZATION", "CRITICAL",
                    "INSECURE_DESERIALIZATION", "DEEP_INSECURE_DESERIALIZATION",
                    "ObjectInputStream.readObject() — insecure deserialization, RCE risk (CWE-502)",
                    "Deserialization in call chain",
                    SecurityCommand::hasInsecureDeserialization),
            new PatternDef("WEAK_CRYPTO", "WARNING",
                    "WEAK_CRYPTO", "DEEP_WEAK_CRYPTO",
                    "Weak/broken cryptographic algorithm — use AES-256, SHA-256+ (CWE-327)",
                    "Weak crypto in call chain",
                    SecurityCommand::hasWeakCrypto),
            new PatternDef("HARDCODED_SECRET", "WARNING",
                    "HARDCODED_SECRET", "DEEP_HARDCODED_SECRET",
                    "Hardcoded password/secret/key in source code (CWE-798)",
                    "Hardcoded secret in call chain",
                    SecurityCommand::hasHardcodedSecret),
            new PatternDef("INSECURE_RANDOM", "WARNING",
                    "INSECURE_RANDOM", "DEEP_INSECURE_RANDOM",
                    "java.util.Random for security — use SecureRandom (CWE-330)",
                    "Insecure random in call chain",
                    SecurityCommand::hasInsecureRandom),
            new PatternDef("OPEN_REDIRECT", "WARNING",
                    "OPEN_REDIRECT", "DEEP_OPEN_REDIRECT",
                    "Redirect with user-controlled URL — open redirect risk (CWE-601)",
                    "Open redirect in call chain",
                    SecurityCommand::hasOpenRedirect),
            new PatternDef("LDAP_INJECTION", "WARNING",
                    "LDAP_INJECTION", "DEEP_LDAP_INJECTION",
                    "LDAP query with string concatenation — LDAP injection risk (CWE-90)",
                    "LDAP injection in call chain",
                    SecurityCommand::hasLdapInjection)
    );

    private final String target;
    private final boolean scanAll;

    public SecurityCommand(String target, boolean scanAll) {
        this.target = target;
        this.scanAll = scanAll;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (scanAll) {
            return scanAllClasses(ctx);
        }

        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, target);
        if (ci == null) return 0;

        String sourceCode = SourceResolver.loadClassSource(ci.name(), ctx);
        Map<String, Object> result = scanClass(ci, sourceCode, ctx);
        ctx.formatter().printResultWithHints(result, buildHints());

        @SuppressWarnings("unchecked")
        var findings = (List<?>) result.get("findings");
        return findings != null ? findings.size() : 0;
    }

    private int scanAllClasses(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> allFindings = new ArrayList<>();
        int totalFindings = 0;

        for (ClassInfo ci : allClasses) {
            String sourceCode = SourceResolver.loadClassSource(ci.name(), ctx);
            if (sourceCode == null) continue;

            var findings = scanSource(sourceCode, ci, ctx);
            if (!findings.isEmpty()) {
                Map<String, Object> classResult = new LinkedHashMap<>();
                classResult.put("class", ci.qualifiedName());
                classResult.put("findings", findings);
                allFindings.add(classResult);
                totalFindings += findings.size();
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalFindings", totalFindings);
        result.put("classesScanned", allClasses.size());
        result.put("classesWithIssues", allFindings.size());

        // Summary by severity
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (var cr : allFindings) {
            @SuppressWarnings("unchecked")
            var findings = (List<Map<String, Object>>) cr.get("findings");
            for (var f : findings) {
                String sev = f.get("severity").toString();
                String type = f.get("type").toString();
                bySeverity.merge(sev, 1, Integer::sum);
                byType.merge(type, 1, Integer::sum);
            }
        }
        result.put("bySeverity", bySeverity);
        result.put("byType", byType);

        // Top findings
        if (allFindings.size() > 20) {
            result.put("classes", allFindings.subList(0, 20));
            result.put("truncated", true);
        } else {
            result.put("classes", allFindings);
        }

        ctx.formatter().printResult(result);
        return totalFindings;
    }

    private Map<String, Object> scanClass(ClassInfo ci, String sourceCode, CommandContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", ci.qualifiedName());

        var filePath = ctx.indexed() != null ? ctx.indexed().findFileForClass(ci.name()) : Optional.<String>empty();
        filePath.ifPresent(p -> result.put("file", p));

        var findings = sourceCode != null ? scanSource(sourceCode, ci, ctx) : List.<Map<String, Object>>of();
        result.put("findings", findings);
        result.put("totalFindings", findings.size());

        // Summary
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (var f : findings) {
            bySeverity.merge(f.get("severity").toString(), 1, Integer::sum);
        }
        result.put("bySeverity", bySeverity);

        return result;
    }

    private List<Map<String, Object>> scanSource(String sourceCode, ClassInfo ci, CommandContext ctx) {
        List<Map<String, Object>> findings = new ArrayList<>();
        String[] lines = sourceCode.split("\n");
        Set<String> reported = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNum = i + 1;

            // Skip comments
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;

            // Direct pattern detection
            for (PatternDef pattern : SECURITY_PATTERNS) {
                String key = pattern.id() + ":" + lineNum;
                if (pattern.detector().test(line) && !reported.contains(key)) {
                    findings.add(PatternDetector.finding(pattern.directType(), pattern.severity(),
                            lineNum, pattern.directMessage()));
                    reported.add(key);
                }
            }
        }

        return findings;
    }

    // ─── Security-specific detectors ───

    static boolean hasPathTraversal(String line) {
        return (line.contains("new File(") || line.contains("Paths.get(")
                || line.contains("Path.of("))
                && (line.contains("request.") || line.contains("param")
                || line.contains("input") || line.contains("getParameter"));
    }

    static boolean hasXxe(String line) {
        return line.contains("DocumentBuilderFactory") || line.contains("SAXParserFactory")
                || line.contains("XMLInputFactory") || line.contains("TransformerFactory")
                || line.contains("SchemaFactory");
    }

    static boolean hasInsecureDeserialization(String line) {
        return line.contains("ObjectInputStream") || line.contains(".readObject(")
                || line.contains("readUnshared(");
    }

    static boolean hasWeakCrypto(String line) {
        return (line.contains("\"MD5\"") || line.contains("\"SHA1\"") || line.contains("\"SHA-1\"")
                || line.contains("\"DES\"") || line.contains("\"RC4\"") || line.contains("\"RC2\"")
                || line.contains("\"Blowfish\"") || line.contains("\"DESede\"")
                || line.contains("MessageDigest.getInstance(\"MD5\")")
                || line.contains("MessageDigest.getInstance(\"SHA-1\")"));
    }

    static boolean hasHardcodedSecret(String line) {
        String lower = line.toLowerCase();
        return (lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("apikey") || lower.contains("api_key") || lower.contains("token"))
                && line.contains("= \"") && !line.contains("// ") && !line.startsWith("*")
                && !lower.contains("example") && !lower.contains("test") && !lower.contains("mock");
    }

    static boolean hasInsecureRandom(String line) {
        return line.contains("new Random(") || line.contains("new Random()")
                || line.contains("util.Random(") || line.contains("util.Random()");
    }

    static boolean hasOpenRedirect(String line) {
        return (line.contains("sendRedirect(") || line.contains("setHeader(\"Location\""))
                && (line.contains("request.") || line.contains("param")
                || line.contains("getParameter") || line.contains("url"));
    }

    static boolean hasLdapInjection(String line) {
        return (line.contains(".search(") || line.contains("SearchControls"))
                && (line.contains("\" +") || line.contains("+ \""));
    }

    private List<CommandHint> buildHints() {
        return java.util.List.of(
            new CommandHint("read " + target + ".METHOD", "Read the vulnerable method"),
            new CommandHint("callers " + target, "Who calls this vulnerable code?"),
            new CommandHint("impact " + target, "Change risk for fixing this")
        );
    }
}
