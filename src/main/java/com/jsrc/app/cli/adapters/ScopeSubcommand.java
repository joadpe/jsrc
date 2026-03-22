package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.search.ScopeCommand;
@Command(name = "scope", description = "Find relevant classes for a task")
public class ScopeSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<task>", description = "Task description")
    String task;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ScopeCommand(task); }
}
