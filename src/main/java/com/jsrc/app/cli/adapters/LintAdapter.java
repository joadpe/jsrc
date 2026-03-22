package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.analysis.LintCommand;
import com.jsrc.app.command.analysis.LintAllCommand;
@Command(name = "lint", description = "Pre-compile checks + architecture rules")
public class LintAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to lint", defaultValue = "")
    String className;
    @Option(names = "--all", description = "All issues: God classes, mutable statics, high-param methods")
    boolean all;
    @Override protected com.jsrc.app.command.Command createCommand() {
        if (all) return new LintAllCommand();
        return className.isEmpty() ? null : new LintCommand(className);
    }
}
