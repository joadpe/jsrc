package com.jsrc.app.command.navigate;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.CommandEngineSource;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.engine.ResolutionResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves the type of a receiver variable using project metadata. */
public class ResolveCommand implements Command {

    private final String expression;

    public ResolveCommand(String expression) {
        this.expression = expression;
    }

    @Override
    public int execute(CommandContext ctx) {
        ResolutionResult result = new JsrcEngine().resolve(
                new CommandEngineSource(ctx), expression);
        if (result.status() == ResolutionResult.Status.INVALID
                || result.status() == ResolutionResult.Status.CONTEXT_NOT_FOUND) {
            ctx.formatter().printResult(Map.of("error", result.error()));
            return 0;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("expression", result.expression());
        if (result.contextClass() != null) output.put("context", result.contextClass());
        output.put("variable", result.variable());
        output.put("method", result.method());
        output.put("resolvedType", result.resolvedType());
        if (result.status() == ResolutionResult.Status.FOUND) {
            output.put("resolvedVia", result.resolvedVia());
            if (result.signature() != null) {
                output.put("signature", result.signature());
                output.put("returnType", result.returnType());
            }
        } else {
            output.put("error", result.error());
        }
        ctx.formatter().printResult(output);
        return result.status() == ResolutionResult.Status.FOUND ? 1 : 0;
    }
}
