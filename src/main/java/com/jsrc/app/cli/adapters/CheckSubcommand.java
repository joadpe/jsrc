package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.architecture.CheckCommand;
@Command(name = "check", description = "Evaluate architecture rules from .jsrc.yaml")
public class CheckSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<ruleId>", description = "Specific rule (optional)", defaultValue = "")
    String ruleId;
    @Override protected com.jsrc.app.command.Command createCommand() {
        return new CheckCommand(ruleId.isEmpty() ? null : ruleId);
    }
}
