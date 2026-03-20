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
        // Compact mode (default): limit to 25 endpoints + total count
        if (!ctx.fullOutput() && endpoints.size() > 25) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("total", endpoints.size());
            compact.put("endpoints", endpoints.subList(0, 25));
            compact.put("truncated", true);
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printResult(endpoints);
        }
        return endpoints.size();
    }
}
