package com.jsrc.app.cli.commands;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.LayerCommand;
@Command(name = "layer", description = "List classes in an architectural layer")
public class LayerSubcommand extends JsrcSubcommand {
    @Parameters(index = "0", paramLabel = "<layerName>", description = "Layer name")
    String layerName;
    @Override protected com.jsrc.app.command.Command createCommand() { return new LayerCommand(layerName); }
}
