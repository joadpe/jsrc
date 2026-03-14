package com.jsrc.app.output;

import java.util.List;

/**
 * Codebase overview: high-level stats for agent orientation.
 *
 * @param totalFiles      number of Java source files
 * @param totalClasses    number of classes
 * @param totalInterfaces number of interfaces
 * @param totalMethods    total methods across all types
 * @param packages        unique package names
 */
public record OverviewResult(
        int totalFiles,
        int totalClasses,
        int totalInterfaces,
        int totalMethods,
        List<String> packages
) {}
