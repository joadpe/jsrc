package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.StyleCommand;
@Command(name = "style", description = "Code style conventions")
public class StyleSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new StyleCommand(); }
}
