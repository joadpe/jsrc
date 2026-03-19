package com.jsrc.app.command;
public class LintCommand implements Command {
    private final String arg;
    public LintCommand(String arg) { this.arg = arg; }
    @Override public int execute(CommandContext ctx) {
        ctx.formatter().printResult(java.util.Map.of("error", "Not yet implemented"));
        return 0;
    }
}
