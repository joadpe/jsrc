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
 * Supports minimal YAML subset: string values and string lists.
 */
public record ProjectConfig(
        List<String> sourceRoots,
        List<String> excludes,
        String javaVersion
) {
    private static final Logger logger = LoggerFactory.getLogger(ProjectConfig.class);
    private static final String CONFIG_FILE = ".jsrc.yaml";

    /**
     * Loads config from the given directory. Returns null if no config file exists.
     */
    public static ProjectConfig load(Path directory) {
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(configFile);
            return parse(lines);
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    /**
     * Loads config from a specific file path.
     */
    public static ProjectConfig loadFrom(Path configPath) {
        if (!Files.exists(configPath)) {
            logger.warn("Config file not found: {}", configPath);
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(configPath);
            return parse(lines);
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configPath, e.getMessage());
            return null;
        }
    }

    private static ProjectConfig parse(List<String> lines) {
        List<String> sourceRoots = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        String javaVersion = "";
        String currentKey = "";

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("- ")) {
                String value = trimmed.substring(2).trim().replaceAll("^[\"']|[\"']$", "");
                switch (currentKey) {
                    case "sourceRoots" -> sourceRoots.add(value);
                    case "excludes" -> excludes.add(value);
                }
            } else if (trimmed.contains(":")) {
                int colonIdx = trimmed.indexOf(':');
                String key = trimmed.substring(0, colonIdx).trim();
                String value = trimmed.substring(colonIdx + 1).trim();

                if (value.isEmpty()) {
                    currentKey = key;
                } else {
                    value = value.replaceAll("^[\"']|[\"']$", "");
                    switch (key) {
                        case "javaVersion" -> javaVersion = value;
                        default -> currentKey = "";
                    }
                }
            }
        }

        return new ProjectConfig(
                sourceRoots.isEmpty() ? List.of() : List.copyOf(sourceRoots),
                excludes.isEmpty() ? List.of() : List.copyOf(excludes),
                javaVersion);
    }
}
