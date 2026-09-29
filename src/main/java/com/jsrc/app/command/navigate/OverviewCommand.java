package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.CommandEngineSource;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.engine.OverviewResult;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;


public class OverviewCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        OverviewResult overview = new JsrcEngine().overview(new CommandEngineSource(ctx));
        List<String> topClassNames = overview.topClassNames();
        List<String> fullPackageList = overview.packages();

        var map = new LinkedHashMap<String, Object>();
        map.put("totalFiles", overview.totalFiles());
        map.put("totalClasses", overview.totalClasses());
        map.put("totalInterfaces", overview.totalInterfaces());
        map.put("totalMethods", overview.totalMethods());
        map.put("totalPackages", overview.totalPackages());
        if (!fullPackageList.isEmpty()) map.put("packages", fullPackageList);
        if (!overview.topClasses().isEmpty()) {
            map.put("topClasses", overview.topClasses().stream()
                    .map(top -> top.name() + " (" + top.methodCount() + " methods)")
                    .toList());
        }
        if (overview.projectModel() != null) {
            var counts = new LinkedHashMap<String, Long>();
            for (var sourceSet : com.jsrc.app.project.SourceSet.values()) {
                counts.put(sourceSet.externalName(),
                        overview.sourceSetCounts().getOrDefault(sourceSet, 0L));
            }
            map.put("sourceSets", counts);
            map.put("project", projectMap(overview.projectModel()));
        }

        // Build hints per command-hints-map.md
        var hintCtx = HintContext.forOverview(topClassNames, fullPackageList);
        var hints = new ArrayList<CommandHint>();
        hints.add(new CommandHint("find \"keyword\"", "Search for relevant classes"));
        if (!topClassNames.isEmpty()) {
            hints.add(CommandHint.resolve("read {topClass}",
                    "Read the most important class", hintCtx));
        }
        hints.add(new CommandHint("hotspots", "See most-used classes"));
        hints.add(new CommandHint("map", "Visual codebase map"));
        hints.add(new CommandHint("tour", "Guided tour of the codebase"));

        ctx.formatter().printResultWithHints(map, hints);
        return overview.totalTypes();
    }

    private static java.util.Map<String, Object> projectMap(
            com.jsrc.app.project.ProjectModel model) {
        var project = new LinkedHashMap<String, Object>();
        project.put("buildSystem", model.buildSystem().name().toLowerCase());
        project.put("javaVersion", model.javaVersion());
        project.put("modules", model.modules().stream().map(module -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("name", module.name());
            value.put("path", relativePath(model.root(), module.path()));
            value.put("sourceRoots", relativePaths(model.root(), module.mainSourceRoots()));
            value.put("testRoots", relativePaths(model.root(), module.testSourceRoots()));
            value.put("generatedRoots", relativePaths(model.root(), module.generatedSourceRoots()));
            value.put("excludedRoots", relativePaths(model.root(), module.excludedRoots()));
            value.put("internalDependencies", module.internalDependencies());
            return value;
        }).toList());
        if (!model.diagnostics().isEmpty()) {
            project.put("diagnostics", model.diagnostics().stream()
                    .map(diagnostic -> java.util.Map.of(
                            "code", diagnostic.code(),
                            "message", diagnostic.message()))
                    .toList());
        }
        return project;
    }

    private static List<String> relativePaths(
            java.nio.file.Path root, List<java.nio.file.Path> paths) {
        return paths.stream().map(path -> relativePath(root, path)).toList();
    }

    private static String relativePath(java.nio.file.Path root, java.nio.file.Path path) {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedPath).toString()
                : normalizedPath.toString();
    }
}
