package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.architecture.EndpointsCommand;
@Command(name = "endpoints", description = "REST endpoints path HTTP method controller")
public class EndpointsSubcommand extends JsrcSubcommand {
    @Override protected com.jsrc.app.command.Command createCommand() { return new EndpointsCommand(); }
}
