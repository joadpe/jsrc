package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.ComplexityCommand;
import com.jsrc.app.command.analysis.ComplexityAllCommand;
@Command(name = "complexity", description = "Cyclomatic complexity per method")
public class ComplexityAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze", defaultValue = "")
    String className;
    @Option(names = "--all", description = "Top 30 classes by complexity")
    boolean all;
    @Override protected com.jsrc.app.command.Command createCommand() {
        if (all) return new ComplexityAllCommand();
        return className.isEmpty() ? null : new ComplexityCommand(className);
    }
}
