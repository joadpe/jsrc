package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

import com.jsrc.app.analysis.PatternDetector;
import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Checks compatibility for migration between Java versions.
 * Detects APIs removed or restricted in the target version.
 */
public class CompatCommand implements Command {

    public record CompatIssue(String id, int removedIn, String api, String replacement,
                               java.util.function.Predicate<String> detector) {}

    private static final List<CompatIssue> ISSUES = List.of(
            // Java 9 removals
            new CompatIssue("JAXB", 9, "javax.xml.bind", "Add jakarta.xml.bind dependency",
                    line -> line.contains("javax.xml.bind")),
            new CompatIssue("JAX_WS", 9, "javax.xml.ws", "Add jakarta.xml.ws dependency",
                    line -> line.contains("javax.xml.ws")),
            new CompatIssue("ACTIVATION", 9, "javax.activation", "Add jakarta.activation dependency",
                    line -> line.contains("javax.activation")),
            new CompatIssue("CORBA", 9, "org.omg.CORBA", "Remove CORBA dependency (no replacement)",
                    line -> line.contains("org.omg.CORBA") || line.contains("org.omg.PortableServer")),
            new CompatIssue("NASHORN", 11, "jdk.nashorn", "Use GraalJS or external JavaScript engine",
                    line -> line.contains("jdk.nashorn") || line.contains("ScriptEngineManager")),

            // Java 11 removals
            new CompatIssue("JAVA_EE_MODULES", 11, "java.xml.ws/bind/activation",
                    "Add Jakarta EE dependencies explicitly",
                    line -> line.contains("javax.annotation.") && !line.contains("javax.annotation.processing")),

            // Java 14 removals
            new CompatIssue("SECURITY_ACL", 14, "java.security.acl", "Use java.security.Policy",
                    line -> line.contains("java.security.acl")),

            // Java 16 restrictions
            new CompatIssue("ILLEGAL_ACCESS", 16, "setAccessible(true)",
                    "Module system blocks reflective access — add --add-opens or redesign",
                    line -> line.contains("setAccessible(true)")),

            // Java 17 removals
            new CompatIssue("RMI_ACTIVATION", 17, "java.rmi.activation",
                    "RMI Activation removed — use alternative RPC",
                    line -> line.contains("java.rmi.activation")),
            new CompatIssue("APPLET", 17, "java.applet", "Applets removed — migrate to web technology",
                    line -> line.contains("java.applet") || line.contains("extends Applet")),

            // Internal API usage
            new CompatIssue("SUN_MISC", 9, "sun.misc.Unsafe",
                    "Use VarHandle (Java 9+) or MethodHandles",
                    line -> line.contains("sun.misc.Unsafe") || line.contains("sun.misc.BASE64")),
            new CompatIssue("COM_SUN_INTERNAL", 9, "com.sun.* internal API",
                    "Use public API equivalents",
                    line -> line.contains("com.sun.") && !line.contains("com.sun.jna")
                            && !line.contains("com.sun.xml.bind")),

            // Deprecated for removal
            new CompatIssue("FINALIZE", 9, "finalize()",
                    "Use Cleaner or try-with-resources",
                    line -> line.contains("void finalize()"))
    );

    private final int targetVersion;

    public CompatCommand(int targetVersion) {
        this.targetVersion = targetVersion;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<Map<String, Object>> allIssues = new ArrayList<>();
        Map<String, Integer> byApi = new LinkedHashMap<>();

        for (ClassInfo ci : allClasses) {
            String source = SourceResolver.loadClassSource(ci.name(), ctx);
            if (source == null) continue;

            String[] lines = source.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("//") || line.startsWith("*")) continue;

                for (CompatIssue issue : ISSUES) {
                    if (issue.removedIn() > targetVersion) continue;
                    if (issue.detector().test(line)) {
                        Map<String, Object> finding = new LinkedHashMap<>();
                        finding.put("id", issue.id());
                        finding.put("api", issue.api());
                        finding.put("removedIn", "Java " + issue.removedIn());
                        finding.put("replacement", issue.replacement());
                        finding.put("file", ctx.indexed() != null
                                ? ctx.indexed().findFileForClass(ci.name()).orElse(ci.name() + ".java")
                                : ci.name() + ".java");
                        finding.put("line", i + 1);
                        finding.put("class", ci.qualifiedName());
                        allIssues.add(finding);
                        byApi.merge(issue.api(), 1, Integer::sum);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceVersion", 8); // assume Java 8 source
        result.put("targetVersion", targetVersion);
        result.put("totalIssues", allIssues.size());
        result.put("byApi", byApi);
        if (allIssues.size() > 50) {
            result.put("issues", allIssues.subList(0, 50));
            result.put("truncated", true);
        } else {
            result.put("issues", allIssues);
        }

        ctx.formatter().printResult(result);
        return allIssues.size();
    }
}
