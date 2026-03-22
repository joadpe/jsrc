package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.navigate.MiniCommand;

@Command(name = "mini", description = "Quick class overview (~120 tokens)")
public class MiniSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to inspect")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new MiniCommand(className);
    }
}
