package com.jsrc.app.command;

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
        ctx.formatter().printOverview(new OverviewResult(
                fileCount, totalClasses, totalInterfaces,
                totalMethods, List.copyOf(packages)));
        return totalClasses + totalInterfaces;
    }
}
