package com.jsrc.app.model;

/**
 * A suggested next command for agent navigation.
 * Included in JSON output as {@code nextCommands} array.
 *
 * @param command     the resolved command string (e.g., "callers findMethods")
 * @param description human-readable description (e.g., "Who calls this method?")
 */
public record CommandHint(String command, String description) {

    /**
     * Creates a hint by resolving template placeholders against a HintContext.
     * <p>
     * Supported placeholders: {class}, {method}, {topClass}, {firstMatch},
     * {callerClass}, {callerMethod}, {calleeClass}, {calleeMethod}.
     * Unresolved placeholders pass through as-is (useful for pattern hints like METHOD).
     *
     * @param commandTemplate     command with placeholders
     * @param descriptionTemplate description with placeholders
     * @param ctx                 context with resolved values
     * @return resolved CommandHint
     */
    public static CommandHint resolve(String commandTemplate, String descriptionTemplate,
                                       HintContext ctx) {
        String cmd = resolvePlaceholders(commandTemplate, ctx);
        String desc = resolvePlaceholders(descriptionTemplate, ctx);
        return new CommandHint(cmd, desc);
    }

    private static String resolvePlaceholders(String template, HintContext ctx) {
        String result = template;
        if (ctx.className() != null) {
            result = result.replace("{class}", ctx.className());
        }
        if (ctx.methodName() != null) {
            result = result.replace("{method}", ctx.methodName());
        }
        if (ctx.topClass() != null) {
            result = result.replace("{topClass}", ctx.topClass());
        }
        if (ctx.firstMatch() != null) {
            result = result.replace("{firstMatch}", ctx.firstMatch());
        }
        if (ctx.callerClass() != null) {
            result = result.replace("{callerClass}", ctx.callerClass());
        }
        if (ctx.callerMethod() != null) {
            result = result.replace("{callerMethod}", ctx.callerMethod());
        }
        if (ctx.calleeClass() != null) {
            result = result.replace("{calleeClass}", ctx.calleeClass());
        }
        if (ctx.calleeMethod() != null) {
            result = result.replace("{calleeMethod}", ctx.calleeMethod());
        }
        return result;
    }
}
