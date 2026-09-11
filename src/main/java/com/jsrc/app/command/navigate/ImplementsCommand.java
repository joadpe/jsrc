package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.LinkedHashMap;
import java.util.List;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.model.HierarchyResult;
import com.jsrc.app.parser.model.ClassInfo;

public class ImplementsCommand implements Command {
    private final String ifaceName;

    public ImplementsCommand(String ifaceName) {
        this.ifaceName = ifaceName;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<String> implementors = allClasses.stream()
                .filter(ci -> ci.interfaces().stream().anyMatch(i -> {
                    String stripped = i.contains("<") ? i.substring(0, i.indexOf('<')) : i;
                    // Match both simple name and qualified name (like HierarchyCommand does)
                    return stripped.equals(ifaceName) || stripped.endsWith("." + ifaceName);
                }))
                .map(ClassInfo::qualifiedName).toList();

        var hierarchyResult = new HierarchyResult(
                ifaceName, "", List.of(), List.of(), implementors);

        // Convert to Map to preserve existing JSON contract
        var result = new LinkedHashMap<String, Object>();
        result.put("target", hierarchyResult.target());
        result.put("superClass", hierarchyResult.superClass());
        result.put("interfaces", hierarchyResult.interfaces());
        result.put("subClasses", hierarchyResult.subClasses());
        result.put("implementors", hierarchyResult.implementors());

        // Build hints: prefer simple name from implementors list
        var hints = new java.util.ArrayList<CommandHint>();
        if (!implementors.isEmpty()) {
            String firstImplementor = implementors.get(0);
            String simpleName = firstImplementor.substring(firstImplementor.lastIndexOf('.') + 1);
            hints.add(new CommandHint("read " + simpleName, "Read an implementor"));
        }
        hints.add(new CommandHint("hierarchy " + ifaceName, "See full inheritance tree"));

        ctx.formatter().printResultWithHints(result, hints);
        return implementors.size();
    }
}
