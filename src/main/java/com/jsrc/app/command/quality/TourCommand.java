package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.*;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Generates a structured tour of the codebase for onboarding.
 * Combines data from overview, entry-points, hotspots, style, patterns.
 */
public class TourCommand implements Command {

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        CallGraph graph = ctx.callGraph();

        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Summary
        int files = ctx.indexed() != null ? ctx.indexed().fileCount() : ctx.javaFiles().size();
        long interfaces = allClasses.stream().filter(ClassInfo::isInterface).count();
        int methods = allClasses.stream().mapToInt(c -> c.methods().size()).sum();
        var packages = new TreeSet<String>();
        allClasses.forEach(c -> { if (!c.packageName().isEmpty()) packages.add(c.packageName()); });

        result.put("summary", Map.of(
                "files", files,
                "classes", allClasses.size() - interfaces,
                "interfaces", interfaces,
                "methods", methods,
                "packages", packages.size()
        ));

        // 2. Entry points
        List<String> entryPoints = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            for (var mi : ci.methods()) {
                if (mi.name().equals("main") && mi.modifiers().contains("public")
                        && mi.modifiers().contains("static")) {
                    entryPoints.add(ci.qualifiedName() + ".main");
                }
            }
        }
        // Also detect controllers
        List<String> controllers = allClasses.stream()
                .filter(c -> c.name().endsWith("Controller"))
                .map(ClassInfo::qualifiedName)
                .limit(10).toList();
        result.put("entryPoints", entryPoints.size() > 10 ? entryPoints.subList(0, 10) : entryPoints);
        if (!controllers.isEmpty()) result.put("controllers", controllers);

        // 3. Core classes (top by callers)
        List<Map<String, Object>> coreClasses = new ArrayList<>();
        Map<String, Integer> callerCounts = new HashMap<>();
        for (ClassInfo ci : allClasses) {
            int total = 0;
            for (var mi : ci.methods()) {
                for (MethodReference ref : graph.findMethodsByName(mi.name())) {
                    if (ref.className().equals(ci.name())) {
                        total += graph.getCallersOf(ref).size();
                    }
                }
            }
            if (total > 0) callerCounts.put(ci.qualifiedName(), total);
        }
        callerCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> coreClasses.add(Map.of("class", e.getKey(), "callers", e.getValue())));
        result.put("coreClasses", coreClasses);

        // 4. Architecture layers (inferred from naming)
        Map<String, Integer> layers = new LinkedHashMap<>();
        for (ClassInfo ci : allClasses) {
            String layer = inferLayer(ci.name());
            if (layer != null) layers.merge(layer, 1, Integer::sum);
        }
        result.put("layers", layers);

        // 5. Conventions
        Map<String, Object> conventions = new LinkedHashMap<>();
        // Logging — detect from annotations and class names
        long slf4j = allClasses.stream().filter(c -> c.annotations().stream().anyMatch(a -> a.name().contains("Slf4j"))).count();
        conventions.put("logging", slf4j > 0 ? "slf4j" : "unknown");
        // Injection
        long ctorInjection = allClasses.stream().filter(c -> !c.methods().isEmpty()
                && c.methods().stream().anyMatch(m -> m.name().equals(c.name()) && m.parameters().size() > 0)).count();
        conventions.put("injection", ctorInjection > 5 ? "constructor" : "unknown");
        result.put("conventions", conventions);

        // 6. Tech stack detection (from annotations and naming)
        List<String> stack = new ArrayList<>();
        if (allClasses.stream().anyMatch(c -> c.annotations().stream().anyMatch(a -> a.name().contains("Controller") || a.name().contains("Service") || a.name().contains("Component")))) stack.add("Spring");
        if (allClasses.stream().anyMatch(c -> c.name().contains("JPanel") || c.name().contains("JFrame") || c.superClass().contains("JPanel"))) stack.add("Swing");
        if (allClasses.stream().anyMatch(c -> c.annotations().stream().anyMatch(a -> a.name().contains("Stateless") || a.name().contains("Stateful") || a.name().contains("EJB")))) stack.add("EJB");
        if (allClasses.stream().anyMatch(c -> c.annotations().stream().anyMatch(a -> a.name().contains("Entity") || a.name().contains("Table")))) stack.add("JPA");
        if (!stack.isEmpty()) result.put("techStack", stack);

        ctx.formatter().printResult(result);
        return allClasses.size();
    }

    private String inferLayer(String className) {
        if (className.endsWith("Controller")) return "controller";
        if (className.endsWith("Service")) return "service";
        if (className.endsWith("Dao") || className.endsWith("DAO")) return "dao";
        if (className.endsWith("Repository")) return "repository";
        if (className.endsWith("Mapper")) return "mapper";
        if (className.endsWith("DTO") || className.endsWith("Dto")) return "dto";
        if (className.endsWith("Entity")) return "entity";
        if (className.endsWith("Config") || className.endsWith("Configuration")) return "config";
        if (className.endsWith("Filter")) return "filter";
        if (className.endsWith("Interceptor")) return "interceptor";
        if (className.endsWith("Listener")) return "listener";
        return null;
    }
}
