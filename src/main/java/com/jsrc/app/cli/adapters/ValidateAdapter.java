package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.architecture.ValidateCommand;
@Command(name = "validate", description = "Validate method exists with exact signature")
public class ValidateAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<methodRef>", description = "Method reference to validate")
    String methodRef;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ValidateCommand(methodRef); }
}
