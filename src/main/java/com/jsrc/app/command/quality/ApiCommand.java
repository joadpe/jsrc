package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;
import java.util.stream.Collectors;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Lists the public API of the project: public classes + public methods,
 * grouped by package. Excludes internal/impl packages and test classes.
 */
public class ApiCommand implements Command {

    private final String moduleFilter;

    public ApiCommand(String moduleFilter) {
        this.moduleFilter = moduleFilter;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();

        // Filter: public, non-test, non-internal
        var publicClasses = allClasses.stream()
                .filter(ci -> ci.modifiers().contains("public"))
                .filter(ci -> !CommandContext.isTestPath(ci.qualifiedName().replace('.', '/')))
                .filter(ci -> !ci.packageName().contains(".internal"))
                .filter(ci -> !ci.packageName().contains(".impl"))
                .filter(ci -> moduleFilter == null || ci.packageName().contains(moduleFilter))
                .sorted(Comparator.comparing(ClassInfo::qualifiedName))
                .toList();

        // Group by package
        Map<String, List<Map<String, Object>>> byPackage = new LinkedHashMap<>();
        int totalMethods = 0;

        for (ClassInfo ci : publicClasses) {
            List<Map<String, Object>> methods = new ArrayList<>();
            List<String> deprecated = new ArrayList<>();

            for (MethodInfo mi : ci.methods()) {
                if (!mi.modifiers().contains("public")) continue;
                if (mi.name().equals(ci.name())) continue; // skip constructors

                Map<String, Object> method = new LinkedHashMap<>();
                method.put("name", mi.name());
                method.put("returnType", mi.returnType() != null ? mi.returnType() : "void");
                method.put("params", mi.parameters().size());

                boolean isDeprecated = mi.annotations().stream()
                        .anyMatch(a -> a.name().equals("Deprecated"));
                if (isDeprecated) {
                    method.put("deprecated", true);
                    deprecated.add(mi.name());
                }

                methods.add(method);
                totalMethods++;
            }

            Map<String, Object> classEntry = new LinkedHashMap<>();
            classEntry.put("name", ci.qualifiedName());
            classEntry.put("kind", ci.isInterface() ? "interface" : ci.isAbstract() ? "abstract" : "class");
            classEntry.put("publicMethods", methods.size());
            if (!deprecated.isEmpty()) classEntry.put("deprecatedMethods", deprecated);
            classEntry.put("methods", methods);

            byPackage.computeIfAbsent(ci.packageName(), k -> new ArrayList<>()).add(classEntry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalClasses", publicClasses.size());
        result.put("totalPublicMethods", totalMethods);
        result.put("packages", byPackage.size());

        // Convert to module list
        List<Map<String, Object>> modules = new ArrayList<>();
        for (var entry : byPackage.entrySet()) {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("package", entry.getKey());
            module.put("classes", entry.getValue().size());
            module.put("items", entry.getValue());
            modules.add(module);
        }
        result.put("byPackage", modules);

        ctx.formatter().printResult(result);
        return publicClasses.size();
    }
}
