package com.jsrc.app.cli.adapters;
import picocli.CommandLine.Command;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.architecture.EndpointsCommand;
@Command(name = "endpoints", description = "REST endpoints path HTTP method controller")
public class EndpointsAdapter extends PicocliAdapter {
    @Override protected com.jsrc.app.command.Command createCommand() { return new EndpointsCommand(); }
}
