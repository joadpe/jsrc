package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HintContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import com.jsrc.app.parser.model.ClassInfo;

public class OverviewCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        List<ClassInfo> allClasses = ctx.getAllClasses();
        int totalClasses = 0, totalInterfaces = 0, totalMethods = 0;
        var packages = new TreeSet<String>();

        for (ClassInfo ci : allClasses) {
            if (ci.isInterface()) totalInterfaces++;
            else totalClasses++;
            totalMethods += ci.methods().size();
            if (!ci.packageName().isEmpty()) packages.add(ci.packageName());
        }

        int fileCount = ctx.indexed() != null ? ctx.indexed().fileCount() : ctx.javaFiles().size();
        List<String> fullPackageList = List.copyOf(packages);

        // Top classes by method count (with display label)
        List<String> topClassLabels = allClasses.stream()
                .filter(ci -> !ci.isInterface())
                .sorted((a, b) -> Integer.compare(b.methods().size(), a.methods().size()))
                .limit(10)
                .map(ci -> ci.name() + " (" + ci.methods().size() + " methods)")
                .toList();

        // Top class names only (for hint resolution)
        List<String> topClassNames = allClasses.stream()
                .filter(ci -> !ci.isInterface())
                .sorted((a, b) -> Integer.compare(b.methods().size(), a.methods().size()))
                .limit(10)
                .map(ClassInfo::name)
                .toList();

        // Build output map
        var map = new LinkedHashMap<String, Object>();
        map.put("totalFiles", fileCount);
        map.put("totalClasses", totalClasses);
        map.put("totalInterfaces", totalInterfaces);
        map.put("totalMethods", totalMethods);
        map.put("totalPackages", packages.size());
        if (!fullPackageList.isEmpty()) {
            map.put("packages", fullPackageList);
        }
        if (!topClassLabels.isEmpty()) {
            map.put("topClasses", topClassLabels);
        }
        if (ctx.projectModel() != null) {
            map.put("project", projectMap(ctx.projectModel()));
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
        return totalClasses + totalInterfaces;
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
