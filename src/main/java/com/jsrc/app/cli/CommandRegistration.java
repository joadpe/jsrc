package com.jsrc.app.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import picocli.CommandLine;

record CommandRegistration(
        Supplier<?> factory,
        CommandCategory category,
        CommandOutputType outputType,
        CommandCost cost,
        Map<BudgetProfile, BudgetRule> budgets) {

    Object createCommand() {
        return factory.get();
    }

    CommandDescriptor descriptor() {
        Object command = createCommand();
        CommandLine.Model.CommandSpec spec = new CommandLine(command).getCommandSpec();
        String name = spec.name();
        String summary = Arrays.stream(spec.usageMessage().description())
                .map(CommandRegistration::normalizeSummary)
                .findFirst()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Command has no summary: " + name));
        List<String> arguments = spec.positionalParameters().stream()
                .map(parameter -> parameter.paramLabel())
                .toList();
        List<String> options = spec.options().stream()
                .map(option -> String.join("|", option.names()))
                .toList();
        List<String> aliases = List.of(spec.aliases());
        String schemaId = outputType == CommandOutputType.NONE
                ? null
                : "urn:jsrc:output:" + name + ":1";
        List<Integer> protocolVersions = outputType == CommandOutputType.NONE
                ? List.of()
                : List.of(1);
        return new CommandDescriptor(name, aliases, summary, category, arguments, options,
                outputType, schemaId, protocolVersions, cost, budgets,
                List.of("jsrc " + name + " --json"));
    }

    private static String normalizeSummary(String summary) {
        return summary.replace("%n", " ").replaceAll("\\s+", " ").trim();
    }
}
