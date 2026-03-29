package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import com.jsrc.app.model.OverviewResult;
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
        // Always include packages (small metadata, high navigation value)
        List<String> fullPackageList = List.copyOf(packages);

        // Extract top classes by method count for navigation entry points
        List<String> topClasses = allClasses.stream()
                .filter(ci -> !ci.isInterface())
                .sorted((a, b) -> Integer.compare(b.methods().size(), a.methods().size()))
                .limit(10)
                .map(ci -> ci.name() + " (" + ci.methods().size() + " methods)")
                .toList();

        ctx.formatter().printOverview(new OverviewResult(
                fileCount, totalClasses, totalInterfaces,
                totalMethods, fullPackageList), packages.size(), topClasses);
        return totalClasses + totalInterfaces;
    }
}
