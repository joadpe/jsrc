package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.reverse.ContractCommand;
@Command(name = "contract", description = "Formal contract methods params throws javadoc")
public class ContractSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<className>", description = "Interface or class")
    String className;
    @Override protected com.jsrc.app.command.Command createCommand() { return new ContractCommand(className); }
}
