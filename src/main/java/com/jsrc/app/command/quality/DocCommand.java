package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.util.ClassLookup;

/**
 * Generates Javadoc drafts for public methods without documentation.
 * Infers descriptions from method name, parameters, return type, and callers.
 */
public class DocCommand implements Command {

    private final String target;

    public DocCommand(String target) {
        this.target = target;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, target);
        if (ci == null) return 0;

        CallGraph graph = ctx.callGraph();
        List<Map<String, Object>> docs = new ArrayList<>();

        for (MethodInfo mi : ci.methods()) {
            if (!mi.modifiers().contains("public")) continue;
            if (mi.name().equals(ci.name())) continue; // skip constructors
            if (mi.javadoc() != null && !mi.javadoc().isBlank()) continue; // already documented

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("method", mi.name());
            doc.put("signature", mi.returnType() + " " + mi.name() + "(...)");

            // Generate description from method name
            String desc = inferDescription(mi.name());
            doc.put("description", desc);

            // @param
            List<String> params = new ArrayList<>();
            for (var param : mi.parameters()) {
                params.add("@param " + param.name() + " the " + humanize(param.name()));
            }
            if (!params.isEmpty()) doc.put("params", params);

            // @return
            if (mi.returnType() != null && !mi.returnType().equals("void")) {
                doc.put("returns", "@return " + inferReturnDesc(mi.returnType(), mi.name()));
            }

            // @throws
            if (!mi.thrownExceptions().isEmpty()) {
                List<String> throwDocs = new ArrayList<>();
                for (String ex : mi.thrownExceptions()) {
                    throwDocs.add("@throws " + ex + " if an error occurs");
                }
                doc.put("throws", throwDocs);
            }

            // Context: callers
            Set<MethodReference> refs = graph.findMethodsByName(mi.name());
            List<String> callers = new ArrayList<>();
            for (MethodReference ref : refs) {
                if (ref.className().equals(ci.name())) {
                    for (var call : graph.getCallersOf(ref)) {
                        callers.add(call.caller().className() + "." + call.caller().methodName());
                        if (callers.size() >= 5) break;
                    }
                }
            }
            if (!callers.isEmpty()) doc.put("calledBy", callers);

            // Generate full Javadoc
            StringBuilder javadoc = new StringBuilder();
            javadoc.append("/**\n");
            javadoc.append(" * ").append(desc).append("\n");
            javadoc.append(" *\n");
            for (String p : params) javadoc.append(" * ").append(p).append("\n");
            if (doc.containsKey("returns")) javadoc.append(" * ").append(doc.get("returns")).append("\n");
            if (doc.containsKey("throws")) {
                @SuppressWarnings("unchecked")
                var throwDocs = (List<String>) doc.get("throws");
                for (String t : throwDocs) javadoc.append(" * ").append(t).append("\n");
            }
            javadoc.append(" */");
            doc.put("javadoc", javadoc.toString());

            docs.add(doc);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", ci.qualifiedName());
        result.put("undocumentedMethods", docs.size());
        result.put("docs", docs);

        ctx.formatter().printResult(result);
        return docs.size();
    }

    private String inferDescription(String methodName) {
        // Convert camelCase to sentence
        if (methodName.startsWith("get")) return "Returns the " + humanize(methodName.substring(3)) + ".";
        if (methodName.startsWith("set")) return "Sets the " + humanize(methodName.substring(3)) + ".";
        if (methodName.startsWith("is")) return "Checks if " + humanize(methodName.substring(2)) + ".";
        if (methodName.startsWith("has")) return "Checks if has " + humanize(methodName.substring(3)) + ".";
        if (methodName.startsWith("find")) return "Finds " + humanize(methodName.substring(4)) + ".";
        if (methodName.startsWith("create")) return "Creates " + humanize(methodName.substring(6)) + ".";
        if (methodName.startsWith("delete")) return "Deletes " + humanize(methodName.substring(6)) + ".";
        if (methodName.startsWith("update")) return "Updates " + humanize(methodName.substring(6)) + ".";
        if (methodName.startsWith("process")) return "Processes " + humanize(methodName.substring(7)) + ".";
        if (methodName.startsWith("validate")) return "Validates " + humanize(methodName.substring(8)) + ".";
        if (methodName.startsWith("convert")) return "Converts " + humanize(methodName.substring(7)) + ".";
        if (methodName.startsWith("load")) return "Loads " + humanize(methodName.substring(4)) + ".";
        if (methodName.startsWith("save")) return "Saves " + humanize(methodName.substring(4)) + ".";
        if (methodName.startsWith("init")) return "Initializes " + humanize(methodName.substring(4)) + ".";
        return humanize(methodName) + ".";
    }

    private String inferReturnDesc(String returnType, String methodName) {
        if (returnType.equals("boolean")) return "true if " + humanize(methodName) + ", false otherwise";
        if (returnType.contains("List")) return "list of results";
        if (returnType.contains("Optional")) return "the result, or empty if not found";
        if (returnType.contains("Map")) return "map of results";
        return "the " + humanize(returnType);
    }

    private static String humanize(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append(' ');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().trim();
    }
}
