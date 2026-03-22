package com.jsrc.app.command;

import java.util.*;

import com.jsrc.app.parser.model.ClassInfo;

/**
 * Generates Java code following project conventions.
 * Infers conventions from existing code (style, patterns, naming).
 */
public class ScaffoldCommand implements Command {

    private final String pattern;
    private final String name;

    public ScaffoldCommand(String pattern, String name) {
        this.pattern = pattern;
        this.name = name;
    }

    @Override
    public int execute(CommandContext ctx) {
        // Detect conventions
        var allClasses = ctx.getAllClasses();
        String basePackage = detectBasePackage(allClasses);
        boolean useSilf4j = allClasses.stream().anyMatch(c -> c.annotations().stream().anyMatch(a -> a.name().contains("Slf4j")));
        boolean useConstructorInjection = allClasses.stream().anyMatch(c ->
                c.methods().stream().anyMatch(m -> m.name().equals(c.name()) && m.parameters().size() > 0));

        String code = switch (pattern.toLowerCase()) {
            case "service" -> generateService(name, basePackage, useSilf4j, useConstructorInjection);
            case "controller" -> generateController(name, basePackage, useSilf4j);
            case "dao" -> generateDao(name, basePackage, useSilf4j);
            case "dto" -> generateDto(name, basePackage);
            case "entity" -> generateEntity(name, basePackage);
            case "test" -> generateTest(name, basePackage);
            default -> "// Unknown pattern: " + pattern + ". Use: service|controller|dao|dto|entity|test";
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", pattern);
        result.put("name", name);
        result.put("package", basePackage);
        result.put("conventions", Map.of("logging", useSilf4j ? "slf4j" : "jul", "injection", useConstructorInjection ? "constructor" : "field"));
        result.put("code", code);

        ctx.formatter().printResult(result);
        return 1;
    }

    private String detectBasePackage(List<ClassInfo> classes) {
        return classes.stream()
                .map(ClassInfo::packageName)
                .filter(p -> !p.isEmpty())
                .min(Comparator.comparingInt(String::length))
                .orElse("com.example");
    }

    private String generateService(String name, String pkg, boolean slf4j, boolean ctorInject) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".service;\n\n");
        if (slf4j) {
            sb.append("import org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\n\n");
        }
        sb.append("public class ").append(name).append(" {\n\n");
        if (slf4j) sb.append("    private static final Logger log = LoggerFactory.getLogger(").append(name).append(".class);\n\n");
        sb.append("    // TODO: add dependencies via constructor injection\n\n");
        sb.append("    // TODO: implement business methods\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String generateController(String name, String pkg, boolean slf4j) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".controller;\n\n");
        sb.append("public class ").append(name).append(" {\n\n");
        sb.append("    // TODO: inject service\n\n");
        sb.append("    // TODO: implement endpoints\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String generateDao(String name, String pkg, boolean slf4j) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".dao;\n\n");
        sb.append("public class ").append(name).append(" {\n\n");
        sb.append("    // TODO: implement data access methods\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String generateDto(String name, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".dto;\n\n");
        sb.append("public record ").append(name).append("(\n");
        sb.append("    // TODO: add fields\n");
        sb.append(") {}\n");
        return sb.toString();
    }

    private String generateEntity(String name, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".entity;\n\n");
        sb.append("public class ").append(name).append(" {\n\n");
        sb.append("    private Long id;\n\n");
        sb.append("    // TODO: add fields\n\n");
        sb.append("    public Long getId() { return id; }\n");
        sb.append("    public void setId(Long id) { this.id = id; }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String generateTest(String name, String pkg) {
        String targetClass = name.replace("Test", "").replace("Tests", "");
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\n\n");
        sb.append("class ").append(name).append(" {\n\n");
        sb.append("    @Test\n");
        sb.append("    void shouldWork() {\n");
        sb.append("        // TODO: test ").append(targetClass).append("\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
