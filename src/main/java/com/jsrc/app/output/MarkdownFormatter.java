package com.jsrc.app.output;

import java.util.List;
import java.util.Map;

/**
 * Formats a context map as a structured Markdown spec draft.
 * The output is designed to be:
 * - Parseable by SpecParser for --verify
 * - Readable by humans in Obsidian
 * - Refineable by agents
 */
public final class MarkdownFormatter {

    private MarkdownFormatter() {}

    /**
     * Converts a context map (from ContextAssembler) to Markdown spec format.
     */
    @SuppressWarnings("unchecked")
    public static String toMarkdown(Map<String, Object> ctx) {
        StringBuilder md = new StringBuilder();

        // Header
        Map<String, Object> cls = (Map<String, Object>) ctx.get("class");
        String name = (String) cls.get("name");
        String pkg = (String) cls.getOrDefault("packageName", "");
        boolean isInterface = Boolean.TRUE.equals(cls.get("isInterface"));

        md.append("# ").append(name).append("\n\n");
        if (!pkg.isEmpty()) md.append("**Package:** ").append(pkg).append("  \n");

        // Layer
        if (ctx.containsKey("layer")) {
            md.append("**Layer:** ").append(ctx.get("layer")).append("  \n");
        }

        // Hierarchy
        if (cls.containsKey("superClass")) {
            md.append("**Extends:** ").append(cls.get("superClass")).append("  \n");
        }
        if (cls.containsKey("interfaces")) {
            List<String> ifaces = (List<String>) cls.get("interfaces");
            if (!ifaces.isEmpty()) {
                md.append("**Implements:** ").append(String.join(", ", ifaces)).append("  \n");
            }
        }
        if (cls.containsKey("annotations")) {
            List<String> anns = (List<String>) cls.get("annotations");
            if (!anns.isEmpty()) {
                md.append("**Annotations:** ").append(String.join(", ", anns)).append("  \n");
            }
        }

        md.append("\n");

        // Description placeholder
        md.append("## Description\n\n");
        md.append("<!-- TODO: Describe the purpose and responsibilities of this ")
          .append(isInterface ? "interface" : "class").append(" -->\n\n");

        // Methods
        List<Map<String, Object>> methods = (List<Map<String, Object>>) ctx.get("methods");
        if (methods != null && !methods.isEmpty()) {
            md.append("## Methods\n\n");
            for (Map<String, Object> m : methods) {
                String sig = (String) m.get("signature");
                String retType = (String) m.getOrDefault("returnType", "void");
                md.append("### ").append(sig).append("\n\n");

                if (m.containsKey("annotations")) {
                    List<String> anns = (List<String>) m.get("annotations");
                    for (String ann : anns) {
                        md.append("- **Annotation:** ").append(ann).append("\n");
                    }
                }
                if (m.containsKey("throws")) {
                    List<String> thrw = (List<String>) m.get("throws");
                    md.append("- **Throws:** ").append(String.join(", ", thrw)).append("\n");
                }

                // Call graph info
                addCallGraphInfo(md, (String) m.get("name"),
                        (List<Map<String, Object>>) ctx.get("callGraph"));

                md.append("\n");
            }
        }

        // Dependencies
        Map<String, Object> deps = (Map<String, Object>) ctx.get("dependencies");
        if (deps != null) {
            md.append("## Dependencies\n\n");
            List<String> fields = (List<String>) deps.get("fields");
            List<String> ctorParams = (List<String>) deps.get("constructorParams");

            if (ctorParams != null && !ctorParams.isEmpty()) {
                for (String p : ctorParams) {
                    md.append("- ").append(p).append(" (constructor injection)\n");
                }
            }
            if (fields != null && !fields.isEmpty()) {
                for (String f : fields) {
                    md.append("- ").append(f).append(" (field)\n");
                }
            }
            md.append("\n");
        }

        // Smells
        List<Map<String, Object>> smells = (List<Map<String, Object>>) ctx.get("smells");
        if (smells != null && !smells.isEmpty()) {
            md.append("## Known Issues\n\n");
            for (Map<String, Object> s : smells) {
                md.append("- [").append(s.get("severity")).append("] ")
                  .append(s.get("message")).append(" (line ").append(s.get("line")).append(")\n");
            }
            md.append("\n");
        }

        // Invariants placeholder
        md.append("## Invariants\n\n");
        md.append("<!-- TODO: Define business rules and invariants -->\n\n");

        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static void addCallGraphInfo(StringBuilder md, String methodName,
                                          List<Map<String, Object>> callGraph) {
        if (callGraph == null) return;
        for (Map<String, Object> cg : callGraph) {
            if (methodName.equals(cg.get("method"))) {
                List<String> callers = (List<String>) cg.get("callers");
                List<String> callees = (List<String>) cg.get("callees");
                if (callees != null && !callees.isEmpty()) {
                    md.append("- **Calls:** ").append(String.join(", ", callees)).append("\n");
                }
                if (callers != null && !callers.isEmpty()) {
                    md.append("- **Called by:** ").append(String.join(", ", callers)).append("\n");
                }
                break;
            }
        }
    }
}
