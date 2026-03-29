package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import com.jsrc.app.model.CommandHint;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.util.ClassLookup;

public class ContractCommand implements Command {
    private final String className;

    public ContractCommand(String className) {
        this.className = className;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ClassInfo ci = ClassLookup.resolveOrExit(allClasses, className);
        if (ci == null) return 0;

        var result = new LinkedHashMap<String, Object>();
        result.put("name", ci.name());
        result.put("packageName", ci.packageName());
        result.put("file", "");
        result.put("line", ci.startLine());
        result.put("modifiers", ci.modifiers());
        result.put("isInterface", ci.isInterface());
        result.put("isAbstract", ci.isAbstract());
        result.put("methods", ci.methods().stream()
                .map(m -> {
                    var methodMap = new LinkedHashMap<String, Object>();
                    methodMap.put("name", m.name());
                    methodMap.put("signature", m.signature());
                    methodMap.put("line", m.startLine());
                    methodMap.put("returnType", m.returnType());
                    return methodMap;
                })
                .collect(Collectors.toList()));

        var hints = java.util.List.of(
            new CommandHint("read " + className + ".METHOD", "Read the method source"),
            new CommandHint("test-for " + className + ".METHOD", "Find tests")
        );

        ctx.formatter().printResultWithHints(result, hints);
        return 1;
    }
}
