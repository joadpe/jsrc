package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.DebtCommand;

@Command(name = "debt", description = "Technical debt score with ranking")
public class DebtSubcommand extends JsrcSubcommand {

    @Option(names = "--rank", description = "Show top 20 classes by debt score")
    boolean rank;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DebtCommand(rank);
    }
}
