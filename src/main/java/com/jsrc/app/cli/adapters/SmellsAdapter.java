package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.SmellsCommand;
@Command(name = "smells", description = "Code smell detection (9 rules)")
public class SmellsAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze", defaultValue = "")
    String className;
    @Option(names = "--all", description = "Analyze entire codebase")
    boolean all;
    @Option(names = "--trend", description = "Compare smells in changed files vs HEAD")
    boolean trend;
    @Override protected com.jsrc.app.command.Command createCommand() {
        String arg = all ? "--all" : (className.isEmpty() ? null : className);
        return new SmellsCommand(arg, trend);
    }
}
