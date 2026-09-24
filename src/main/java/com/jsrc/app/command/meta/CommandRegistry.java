package com.jsrc.app.command.meta;

import com.jsrc.app.cli.CommandDescriptor;
import com.jsrc.app.cli.DefaultCommandRegistry;
import com.jsrc.app.output.JsonWriter;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compatibility facade over the canonical command catalog. */
public final class CommandRegistry {

    private CommandRegistry() {}

    public static String[] knownCommandNames() {
        return catalog().stream().map(CommandDescriptor::name).toArray(String[]::new);
    }

    public static void describeAll(boolean json) {
        describeAll(json, System.out);
    }

    public static void describeAll(boolean json, PrintStream out) {
        if (json) {
            out.println(JsonWriter.toJson(catalog().stream().map(CommandRegistry::toMap).toList()));
            return;
        }
        out.println("Available commands:");
        for (CommandDescriptor command : catalog()) {
            out.printf("  %-20s %s%n", command.name(), command.summary());
            if (!command.arguments().isEmpty()) {
                out.printf("    args: %s%n", String.join(" ", command.arguments()));
            }
            out.printf("    flags: %s%n", String.join(" ", command.options()));
        }
    }

    public static boolean describeCommand(String commandName, boolean json) {
        return describeCommand(commandName, json, System.out);
    }

    public static boolean describeCommand(String commandName, boolean json, PrintStream out) {
        return DefaultCommandRegistry.create().find(commandName).map(command -> {
            if (json) {
                out.println(JsonWriter.toJson(toMap(command)));
            } else {
                out.printf("Command: %s%n", command.name());
                out.printf("  Description: %s%n", command.summary());
                if (!command.arguments().isEmpty()) {
                    out.printf("  Args: %s%n", String.join(" ", command.arguments()));
                }
                out.printf("  Flags: %s%n", String.join(" ", command.options()));
                out.printf("  Output: %s%n", command.outputType().name().toLowerCase());
            }
            return true;
        }).orElse(false);
    }

    private static List<CommandDescriptor> catalog() {
        return DefaultCommandRegistry.create().commands();
    }

    private static Map<String, Object> toMap(CommandDescriptor command) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", command.name());
        map.put("description", command.summary());
        map.put("args", command.arguments());
        map.put("flags", command.options());
        map.put("outputType", command.outputType().name().toLowerCase());
        map.put("category", command.category().name().toLowerCase());
        map.put("cost", command.cost().name().toLowerCase());
        return map;
    }
}
