package com.jsrc.app.command;

import java.nio.file.Path;

public class ClassesCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        ctx.formatter().printClasses(allClasses, Path.of(ctx.rootPath()));
        return allClasses.size();
    }
}
