package com.jsrc.app.engine;

import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.SourceSet;
import java.util.List;
import java.util.Map;

/** Project summary independent of any output format. */
public record OverviewResult(
        int totalFiles,
        int totalClasses,
        int totalInterfaces,
        int totalMethods,
        List<String> packages,
        List<TopClass> topClasses,
        Map<SourceSet, Long> sourceSetCounts,
        ProjectModel projectModel) {
    public OverviewResult {
        packages = List.copyOf(packages);
        topClasses = List.copyOf(topClasses);
        sourceSetCounts = Map.copyOf(sourceSetCounts);
    }

    public int totalPackages() { return packages.size(); }
    public int totalTypes() { return totalClasses + totalInterfaces; }
    public List<String> topClassNames() {
        return topClasses.stream().map(TopClass::name).toList();
    }

    public record TopClass(String name, int methodCount) {}
}
