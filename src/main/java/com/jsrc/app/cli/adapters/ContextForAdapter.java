package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.ContextForCommand;
@Command(name = "context-for", description = "Find relevant context for a task")
public class ContextForAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<task>", description = "Task description")
    String task;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ContextForCommand(task); }
}
