package com.jsrc.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Project configuration loaded from {@code .jsrc.yaml}.
 * Uses SnakeYAML for robust parsing.
 */
public record ProjectConfig(
        List<String> sourceRoots,
        List<String> excludes,
        String javaVersion,
        ArchitectureConfig architecture,
        PerformanceConfig performance,
        SecurityConfig security,
        MigrationConfig migration,
        DebtConfig debt,
        ProjectInfo project
) {
    /** Backward-compatible constructor for configs without new sections. */
    public ProjectConfig(List<String> sourceRoots, List<String> excludes,
                          String javaVersion, ArchitectureConfig architecture) {
        this(sourceRoots, excludes, javaVersion, architecture,
                PerformanceConfig.empty(), SecurityConfig.empty(),
                MigrationConfig.empty(), DebtConfig.empty(), ProjectInfo.empty());
    }

    // ─── New config records ───

    public record PerformanceConfig(
            List<String> daoBaseClasses,
            List<String> heavyClasses,
            List<IgnorePattern> ignorePatterns
    ) {
        public record IgnorePattern(String pattern, String className, String method) {}
        public static PerformanceConfig empty() { return new PerformanceConfig(List.of(), List.of(), List.of()); }
    }

    public record SecurityConfig(
            List<String> trustedInputs,
            List<String> ignoreClasses,
            List<CustomSink> customSinks
    ) {
        public record CustomSink(String method, String type) {}
        public static SecurityConfig empty() { return new SecurityConfig(List.of(), List.of(), List.of()); }
    }

    public record MigrationConfig(
            int target,
            List<String> ignore,
            List<Replacement> customReplacements
    ) {
        public record Replacement(String from, String to) {}
        public static MigrationConfig empty() { return new MigrationConfig(17, List.of(), List.of()); }
    }

    public record DebtConfig(
            Map<String, Integer> weights,
            Map<String, Integer> thresholds,
            List<String> exclude
    ) {
        public static DebtConfig empty() {
            return new DebtConfig(
                    Map.of("smells", 2, "complexity", 1, "perfIssues", 5, "coupling", 1, "missingTests", 15),
                    Map.of("maxComplexity", 15, "maxLoc", 300, "maxParams", 5),
                    List.of());
        }
    }

    public record ProjectInfo(
            String name,
            String team,
            Map<String, String> conventions,
            List<Domain> domains
    ) {
        public record Domain(String name, List<String> packages) {}
        public static ProjectInfo empty() { return new ProjectInfo("", "", Map.of(), List.of()); }
    }

    private static final Logger logger = LoggerFactory.getLogger(ProjectConfig.class);
    private static final String CONFIG_FILE = ".jsrc.yaml";

    public static Optional<ProjectConfig> load(Path directory) {
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) return Optional.empty();
        try {
            return Optional.of(parse(Files.readString(configFile)));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configFile, e.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<ProjectConfig> loadFrom(Path configPath) {
        if (!Files.exists(configPath)) {
            logger.warn("Config file not found: {}", configPath);
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(configPath)));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configPath, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static ProjectConfig parse(String yamlContent) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlContent);
        if (root == null) root = Map.of();

        List<String> sourceRoots = getStringList(root, "sourceRoots");
        List<String> excludes = getStringList(root, "excludes");
        String javaVersion = getString(root, "javaVersion", "");
        ArchitectureConfig arch = parseArchitecture((Map<String, Object>) root.get("architecture"));

        // New sections — all optional
        PerformanceConfig perf = parsePerformance((Map<String, Object>) root.get("performance"));
        SecurityConfig sec = parseSecurity((Map<String, Object>) root.get("security"));
        MigrationConfig mig = parseMigration((Map<String, Object>) root.get("migration"));
        DebtConfig debt = parseDebt((Map<String, Object>) root.get("debt"));
        ProjectInfo proj = parseProject((Map<String, Object>) root.get("project"));

        return new ProjectConfig(sourceRoots, excludes, javaVersion, arch, perf, sec, mig, debt, proj);
    }

    @SuppressWarnings("unchecked")
    private static ArchitectureConfig parseArchitecture(Map<String, Object> archMap) {
        if (archMap == null) return ArchitectureConfig.empty();

        List<ArchitectureConfig.LayerDef> layers = new ArrayList<>();
        List<Map<String, Object>> layersList = (List<Map<String, Object>>) archMap.get("layers");
        if (layersList != null) {
            for (Map<String, Object> lm : layersList) {
                layers.add(new ArchitectureConfig.LayerDef(
                        getString(lm, "name", ""),
                        getString(lm, "pattern", ""),
                        getStringList(lm, "annotations")));
            }
        }

        List<ArchitectureConfig.RuleDef> rules = new ArrayList<>();
        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) archMap.get("rules");
        if (rulesList != null) {
            for (Map<String, Object> rm : rulesList) {
                rules.add(new ArchitectureConfig.RuleDef(
                        getString(rm, "id", ""),
                        getString(rm, "description", ""),
                        getString(rm, "from", ""),
                        getString(rm, "layer", ""),
                        getString(rm, "denyImport", ""),
                        getString(rm, "require", ""),
                        getString(rm, "denyAnnotation", "")));
            }
        }

        List<String> endpoints = getStringList(archMap, "endpoints");

        List<ArchitectureConfig.InvokerDef> invokers = new ArrayList<>();
        List<Map<String, Object>> invokersList = (List<Map<String, Object>>) archMap.get("invokers");
        if (invokersList != null) {
            for (Map<String, Object> im : invokersList) {
                // Always use 4-arg constructor to respect explicit callerSuffixes: []
                List<String> suffixes = im.containsKey("callerSuffixes")
                        ? getStringList(im, "callerSuffixes")
                        : List.of("Detalle", "Vista", "View", "Form", "Panel", "Dialog");
                invokers.add(new ArchitectureConfig.InvokerDef(
                        getString(im, "method", ""),
                        getInt(im, "targetArg", 0),
                        getString(im, "resolveClass", ""),
                        suffixes));
            }
        }

        List<String> chainStopMethods = getStringList(archMap, "chainStopMethods");

        return new ArchitectureConfig(
                List.copyOf(layers), List.copyOf(rules),
                List.copyOf(endpoints), List.copyOf(invokers),
                List.copyOf(chainStopMethods));
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    // ─── New section parsers ───

    @SuppressWarnings("unchecked")
    private static PerformanceConfig parsePerformance(Map<String, Object> map) {
        if (map == null) return PerformanceConfig.empty();
        return new PerformanceConfig(
                getStringList(map, "daoBaseClasses"),
                getStringList(map, "heavyClasses"),
                List.of() // ignorePatterns parsed separately if needed
        );
    }

    @SuppressWarnings("unchecked")
    private static SecurityConfig parseSecurity(Map<String, Object> map) {
        if (map == null) return SecurityConfig.empty();
        return new SecurityConfig(
                getStringList(map, "trustedInputs"),
                getStringList(map, "ignoreClasses"),
                List.of() // customSinks parsed separately if needed
        );
    }

    @SuppressWarnings("unchecked")
    private static MigrationConfig parseMigration(Map<String, Object> map) {
        if (map == null) return MigrationConfig.empty();
        return new MigrationConfig(
                getInt(map, "target", 17),
                getStringList(map, "ignore"),
                List.of() // customReplacements parsed separately if needed
        );
    }

    @SuppressWarnings("unchecked")
    private static DebtConfig parseDebt(Map<String, Object> map) {
        if (map == null) return DebtConfig.empty();
        Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        Map<String, Integer> thresholds = new java.util.LinkedHashMap<>();
        if (map.get("weights") instanceof Map<?, ?> w) {
            w.forEach((k, v) -> weights.put(k.toString(), v instanceof Number n ? n.intValue() : 0));
        }
        if (map.get("thresholds") instanceof Map<?, ?> t) {
            t.forEach((k, v) -> thresholds.put(k.toString(), v instanceof Number n ? n.intValue() : 0));
        }
        if (weights.isEmpty()) weights.putAll(DebtConfig.empty().weights());
        if (thresholds.isEmpty()) thresholds.putAll(DebtConfig.empty().thresholds());
        return new DebtConfig(weights, thresholds, getStringList(map, "exclude"));
    }

    @SuppressWarnings("unchecked")
    private static ProjectInfo parseProject(Map<String, Object> map) {
        if (map == null) return ProjectInfo.empty();
        Map<String, String> conventions = new java.util.LinkedHashMap<>();
        if (map.get("conventions") instanceof Map<?, ?> c) {
            c.forEach((k, v) -> conventions.put(k.toString(), v.toString()));
        }
        List<ProjectInfo.Domain> domains = new ArrayList<>();
        if (map.get("domains") instanceof List<?> dl) {
            for (Object d : dl) {
                if (d instanceof Map<?, ?> dm) {
                    String name = dm.get("name") != null ? dm.get("name").toString() : "";
                    List<String> pkgs = dm.get("packages") instanceof List<?> pl
                            ? pl.stream().map(Object::toString).toList() : List.of();
                    domains.add(new ProjectInfo.Domain(name, pkgs));
                }
            }
        }
        return new ProjectInfo(
                getString(map, "name", ""),
                getString(map, "team", ""),
                conventions,
                domains
        );
    }
}
