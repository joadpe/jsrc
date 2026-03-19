package com.jsrc.app.command;
public class FindCommand implements Command {
    private final String arg;
    public FindCommand(String arg) { this.arg = arg; }
    @Override public int execute(CommandContext ctx) {
        ctx.formatter().printResult(java.util.Map.of("error", "Not yet implemented"));
        return 0;
    }
}
