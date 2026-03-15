package com.jsrc.app.command;

import java.util.List;

import com.jsrc.app.architecture.EndpointMapper;
import com.jsrc.app.output.JsonWriter;

public class EndpointsCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<String> epAnnotations = ctx.config() != null
                ? ctx.config().architecture().endpointAnnotations() : List.of();
        var mapper = new EndpointMapper(epAnnotations);
        var endpoints = mapper.findEndpoints(allClasses);
        ctx.formatter().printResult(endpoints);
        return endpoints.size();
    }
}
