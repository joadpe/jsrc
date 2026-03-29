package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.LinkedHashMap;
import java.util.List;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HierarchyResult;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.ClassLookup;

public class HierarchyCommand implements Command {
    private final String className;

    public HierarchyCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo target = ClassLookup.resolveOrExit(allClasses, className);
        if (target == null) return 0;

        List<String> subClasses = allClasses.stream()
                .filter(ci -> ci.superClass().equals(target.name())
                        || ci.superClass().equals(target.qualifiedName()))
                .map(ClassInfo::qualifiedName).toList();

        List<String> implementors = target.isInterface()
                ? allClasses.stream()
                        .filter(ci -> ci.interfaces().stream().anyMatch(i -> { String s = i.contains("<") ? i.substring(0, i.indexOf("<")) : i; return s.equals(target.name()); })
                                || ci.interfaces().stream().anyMatch(i -> { String s = i.contains("<") ? i.substring(0, i.indexOf("<")) : i; return s.equals(target.qualifiedName()); }))
                        .map(ClassInfo::qualifiedName).toList()
                : List.of();

        var hierarchyResult = new HierarchyResult(
                target.qualifiedName(), target.superClass(),
                target.interfaces(), subClasses, implementors);

        // Convert to Map to preserve existing JSON contract
        var result = new LinkedHashMap<String, Object>();
        result.put("target", hierarchyResult.target());
        result.put("superClass", hierarchyResult.superClass());
        result.put("interfaces", hierarchyResult.interfaces());
        result.put("subClasses", hierarchyResult.subClasses());
        result.put("implementors", hierarchyResult.implementors());

        var hints = java.util.List.of(
            new CommandHint("read " + className, "Read a subclass"),
            new CommandHint("implements " + className, "Find all implementors"),
            new CommandHint("breaking-changes " + className, "Impact of changing this class")
        );

        ctx.formatter().printResultWithHints(result, hints);
        return 1;
    }
}
