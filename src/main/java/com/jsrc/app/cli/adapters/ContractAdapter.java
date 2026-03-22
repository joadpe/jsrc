package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.ContractCommand;
@Command(name = "contract", description = "Formal contract methods params throws javadoc")
public class ContractAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<className>", description = "Interface or class")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ContractCommand(className); }
}
