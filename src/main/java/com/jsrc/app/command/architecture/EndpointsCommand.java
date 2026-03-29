package com.jsrc.app.command.architecture;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.util.List;

import com.jsrc.app.architecture.EndpointMapper;
import java.util.Objects;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.output.JsonWriter;

public class EndpointsCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<String> epAnnotations = ctx.config() != null
                ? ctx.config().architecture().endpointAnnotations() : List.of();
        var mapper = new EndpointMapper(epAnnotations);
        var endpoints = mapper.findEndpoints(allClasses);

        String firstEndpoint = "CONTROLLER.ENDPOINT";
        String firstMethod = "ENDPOINT";
        if (!endpoints.isEmpty()) {
            var ep = endpoints.getFirst();
            String ctrl = Objects.toString(ep.get("controller"), "CONTROLLER");
            String mthd = Objects.toString(ep.get("method"), "ENDPOINT");
            // Use simple class name
            if (ctrl.contains(".")) ctrl = ctrl.substring(ctrl.lastIndexOf('.') + 1);
            firstEndpoint = ctrl + "." + mthd;
            firstMethod = ctrl + "." + mthd;
        }
        var hints = java.util.List.of(
            new CommandHint("read " + firstEndpoint, "Read the endpoint"),
            new CommandHint("flow " + firstMethod, "Trace execution from endpoint")
        );

        // Compact mode (default): limit to 25 endpoints + total count
        if (!ctx.fullOutput() && endpoints.size() > 25) {
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("total", endpoints.size());
            compact.put("endpoints", endpoints.subList(0, 25));
            compact.put("truncated", true);
            ctx.formatter().printResultWithHints(compact, hints);
        } else {
            ctx.formatter().printResultWithHints(endpoints, hints);
        }
        return endpoints.size();
    }
}
