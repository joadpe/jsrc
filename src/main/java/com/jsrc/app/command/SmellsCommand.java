package com.jsrc.app.command;

import java.nio.file.Path;

public class SmellsCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        int totalSmells = 0;
        for (Path file : ctx.javaFiles()) {
            var smells = ctx.parser().detectSmells(file);
            totalSmells += smells.size();
            ctx.formatter().printSmells(smells, file);
        }
        return totalSmells;
    }
}
