package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.nio.file.Path;

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
        ctx.formatter().printClassSummary(ci, Path.of(""));
        return 1;
    }
}
