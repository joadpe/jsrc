package com.jsrc.app.command;

import java.util.List;

import com.jsrc.app.output.HierarchyResult;
import com.jsrc.app.parser.model.ClassInfo;

public class HierarchyCommand implements Command {
    private final String className;

    public HierarchyCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = SummaryCommand.resolveOrExit(allClasses, className);
        if (target == null) return 0;

        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name())
                        || ci.superClass().equals(target.qualifiedName()))
                .map(ClassInfo::qualifiedName).toList();

        List<String> implementors = target.isInterface()
                ? allClasses.stream()
                        .filter(ci -> ci.interfaces().contains(target.name())
                                || ci.interfaces().contains(target.qualifiedName()))
                        .map(ClassInfo::qualifiedName).toList()
                : List.of();

        ctx.formatter().printHierarchy(new HierarchyResult(
                target.qualifiedName(), target.superClass(),
                target.interfaces(), subClasses, implementors));
        return 1;
    }
}
