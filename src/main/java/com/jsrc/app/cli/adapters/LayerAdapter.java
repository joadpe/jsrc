package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.architecture.LayerCommand;
@Command(name = "layer", description = "List classes in an architectural layer")
public class LayerAdapter extends PicocliAdapter {
    @Parameters(index = "0", paramLabel = "<layerName>", description = "Layer name")
    String layerName;
    @Override protected com.jsrc.app.command.Command createCommand() { return new LayerCommand(layerName); }
}
