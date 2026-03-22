package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.ContextForCommand;
@Command(name = "context-for", description = "Find relevant context for a task")
public class ContextForSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<task>", description = "Task description")
    String task;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ContextForCommand(task); }
}
