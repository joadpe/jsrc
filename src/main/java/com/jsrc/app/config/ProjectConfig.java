package com.jsrc.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project configuration loaded from {@code .jsrc.yaml}.
 * Supports minimal YAML subset for jsrc architecture definitions.
 */
public record ProjectConfig(
        List<String> sourceRoots,
        List<String> excludes,
        String javaVersion,
        ArchitectureConfig architecture
) {
    private static final Logger logger = LoggerFactory.getLogger(ProjectConfig.class);
    private static final String CONFIG_FILE = ".jsrc.yaml";

    public static ProjectConfig load(Path directory) {
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) return null;
        try {
            return parse(Files.readAllLines(configFile));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    public static ProjectConfig loadFrom(Path configPath) {
        if (!Files.exists(configPath)) {
            logger.warn("Config file not found: {}", configPath);
            return null;
        }
        try {
            return parse(Files.readAllLines(configPath));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configPath, e.getMessage());
            return null;
        }
    }

    private static ProjectConfig parse(List<String> lines) {
        List<String> sourceRoots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        String javaVersion = "";

        List<ArchitectureConfig.LayerDef> layers = new ArrayList<>();
        List<ArchitectureConfig.RuleDef> rules = new ArrayList<>();
        List<String> endpointAnnotations = new ArrayList<>();
        List<ArchitectureConfig.InvokerDef> invokers = new ArrayList<>();

        // State machine for YAML parsing
        String section = "";      // top-level key
        String subSection = "";   // architecture sub-key
        String listContext = "";  // what list items belong to

        // Current object being built
        String layerName = "", layerPattern = "";
        List<String> layerAnnotations = new ArrayList<>();

        String ruleId = "", ruleDesc = "", ruleFrom = "", ruleLayer = "";
        String ruleDenyImport = "", ruleRequire = "", ruleDenyAnnotation = "";

        String invMethod = "";
        int invTargetArg = 0;
        String invResolveClass = "";

        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            int indent = countIndent(line);
            String trimmed = line.trim();

            // Top-level keys (indent 0)
            if (indent == 0 && trimmed.contains(":")) {
                String topKey = trimmed.split(":")[0].trim();
                String topVal = trimmed.substring(trimmed.indexOf(':') + 1).trim()
                        .replaceAll("^[\"']|[\"']$", "");
                if ("javaVersion".equals(topKey) && !topVal.isEmpty()) {
                    javaVersion = topVal;
                }
                section = topKey;
                subSection = "";
                listContext = "";
                continue;
            }

            // Architecture sub-sections (indent 2)
            if ("architecture".equals(section) && indent == 2 && !trimmed.startsWith("-")) {
                // Flush previous object
                flushLayer(layers, layerName, layerPattern, layerAnnotations);
                layerName = ""; layerPattern = ""; layerAnnotations = new ArrayList<>();
                flushRule(rules, ruleId, ruleDesc, ruleFrom, ruleLayer, ruleDenyImport, ruleRequire, ruleDenyAnnotation);
                ruleId = ""; ruleDesc = ""; ruleFrom = ""; ruleLayer = "";
                ruleDenyImport = ""; ruleRequire = ""; ruleDenyAnnotation = "";
                flushInvoker(invokers, invMethod, invTargetArg, invResolveClass);
                invMethod = ""; invTargetArg = 0; invResolveClass = "";

                subSection = trimmed.split(":")[0].trim();
                listContext = subSection;
                continue;
            }

            // List items
            if (trimmed.startsWith("- ")) {
                String value = trimmed.substring(2).trim().replaceAll("^[\"']|[\"']$", "");

                // Check if it's a list item with key:value (object start)
                if (value.contains(":")) {
                    // Flush previous object
                    if ("layers".equals(listContext)) {
                        flushLayer(layers, layerName, layerPattern, layerAnnotations);
                        layerName = ""; layerPattern = ""; layerAnnotations = new ArrayList<>();
                    } else if ("rules".equals(listContext)) {
                        flushRule(rules, ruleId, ruleDesc, ruleFrom, ruleLayer, ruleDenyImport, ruleRequire, ruleDenyAnnotation);
                        ruleId = ""; ruleDesc = ""; ruleFrom = ""; ruleLayer = "";
                        ruleDenyImport = ""; ruleRequire = ""; ruleDenyAnnotation = "";
                    } else if ("invokers".equals(listContext)) {
                        flushInvoker(invokers, invMethod, invTargetArg, invResolveClass);
                        invMethod = ""; invTargetArg = 0; invResolveClass = "";
                    }

                    String key = value.split(":")[0].trim();
                    String val = value.substring(value.indexOf(':') + 1).trim().replaceAll("^[\"']|[\"']$", "");

                    switch (listContext) {
                        case "layers" -> { if ("name".equals(key)) layerName = val; else if ("pattern".equals(key)) layerPattern = val; }
                        case "rules" -> assignRuleField(key, val, ruleId, ruleDesc, ruleFrom, ruleLayer, ruleDenyImport, ruleRequire, ruleDenyAnnotation);
                        case "invokers" -> { if ("method".equals(key)) invMethod = val; else if ("targetArg".equals(key)) invTargetArg = parseInt(val); else if ("resolveClass".equals(key)) invResolveClass = val; }
                    }
                    // Handle rule assignment via return values
                    if ("rules".equals(listContext)) {
                        switch (key) {
                            case "id" -> ruleId = val;
                            case "description" -> ruleDesc = val;
                            case "from" -> ruleFrom = val;
                            case "layer" -> ruleLayer = val;
                            case "denyImport" -> ruleDenyImport = val;
                            case "require" -> ruleRequire = val;
                            case "denyAnnotation" -> ruleDenyAnnotation = val;
                        }
                    }
                } else {
                    // Simple list item
                    switch (section + "." + listContext) {
                        case ".sourceRoots" -> sourceRoots.add(value);
                        case ".excludes" -> excludes.add(value);
                        case "architecture.endpoints" -> endpointAnnotations.add(value);
                        case "architecture.layers" -> layerAnnotations.add(value); // annotations sub-list
                    }
                    // Also handle top-level lists
                    if ("sourceRoots".equals(section)) sourceRoots.add(value);
                    else if ("excludes".equals(section)) excludes.add(value);
                }
                continue;
            }

            // Key-value pairs inside objects
            if (trimmed.contains(":")) {
                String key = trimmed.split(":")[0].trim();
                String val = trimmed.substring(trimmed.indexOf(':') + 1).trim().replaceAll("^[\"']|[\"']$", "");

                if ("javaVersion".equals(key) && section.isEmpty()) {
                    javaVersion = val;
                } else if ("javaVersion".equals(key)) {
                    javaVersion = val;
                }

                // Handle sub-keys in list objects
                if ("layers".equals(listContext)) {
                    switch (key) {
                        case "name" -> layerName = val;
                        case "pattern" -> layerPattern = val;
                        case "annotations" -> {} // list follows
                    }
                } else if ("rules".equals(listContext)) {
                    switch (key) {
                        case "id" -> ruleId = val;
                        case "description" -> ruleDesc = val;
                        case "from" -> ruleFrom = val;
                        case "layer" -> ruleLayer = val;
                        case "denyImport" -> ruleDenyImport = val;
                        case "require" -> ruleRequire = val;
                        case "denyAnnotation" -> ruleDenyAnnotation = val;
                    }
                } else if ("invokers".equals(listContext)) {
                    switch (key) {
                        case "method" -> invMethod = val;
                        case "targetArg" -> invTargetArg = parseInt(val);
                        case "resolveClass" -> invResolveClass = val;
                    }
                }
            }
        }

        // Flush last objects
        flushLayer(layers, layerName, layerPattern, layerAnnotations);
        flushRule(rules, ruleId, ruleDesc, ruleFrom, ruleLayer, ruleDenyImport, ruleRequire, ruleDenyAnnotation);
        flushInvoker(invokers, invMethod, invTargetArg, invResolveClass);

        // Deduplicate sourceRoots
        sourceRoots = new ArrayList<>(new java.util.LinkedHashSet<>(sourceRoots));

        var arch = new ArchitectureConfig(
                List.copyOf(layers), List.copyOf(rules),
                List.copyOf(endpointAnnotations), List.copyOf(invokers));

        return new ProjectConfig(
                sourceRoots.isEmpty() ? List.of() : List.copyOf(sourceRoots),
                excludes.isEmpty() ? List.of() : List.copyOf(excludes),
                javaVersion, arch);
    }

    private static void flushLayer(List<ArchitectureConfig.LayerDef> layers,
                                    String name, String pattern, List<String> annotations) {
        if (!name.isEmpty()) {
            layers.add(new ArchitectureConfig.LayerDef(name, pattern, List.copyOf(annotations)));
        }
    }

    private static void flushRule(List<ArchitectureConfig.RuleDef> rules,
                                   String id, String desc, String from, String layer,
                                   String denyImport, String require, String denyAnnotation) {
        if (!id.isEmpty()) {
            rules.add(new ArchitectureConfig.RuleDef(id, desc, from, layer, denyImport, require, denyAnnotation));
        }
    }

    private static void flushInvoker(List<ArchitectureConfig.InvokerDef> invokers,
                                      String method, int targetArg, String resolveClass) {
        if (!method.isEmpty()) {
            invokers.add(new ArchitectureConfig.InvokerDef(method, targetArg, resolveClass));
        }
    }

    private static void assignRuleField(String key, String val, String... ignored) {
        // Assignment handled by caller via switch
    }

    private static int countIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else break;
        }
        return count;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
