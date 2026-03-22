package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.architecture.TypeCheckCommand;
@Command(name = "type-check", description = "Type check a class")
public class TypeCheckAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Class to type-check")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new TypeCheckCommand(className); }
}
