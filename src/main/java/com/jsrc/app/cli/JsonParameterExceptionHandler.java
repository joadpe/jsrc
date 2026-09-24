package com.jsrc.app.cli;

import com.jsrc.app.output.DiagnosticCode;
import com.jsrc.app.output.VersionedJsonPrintStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/** Emits parse-time CLI failures using the requested output protocol. */
final class JsonParameterExceptionHandler implements CommandLine.IParameterExceptionHandler {

    private final CommandCatalog catalog;
    private final JsrcCommand rootCommand;

    JsonParameterExceptionHandler(CommandCatalog catalog, JsrcCommand rootCommand) {
        this.catalog = catalog;
        this.rootCommand = rootCommand;
    }

    @Override
    public int handleParseException(CommandLine.ParameterException exception, String[] args) {
        CommandLine commandLine = exception.getCommandLine();
        EarlyOutputOptions options = resolveOutputOptions(args);
        if (options.versionOne()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BudgetContext budget = new BudgetContext(
                    options.profile(), null, options.maxBytes(), false, false, null);
            new VersionedJsonPrintStream(
                    new PrintStream(bytes), commandName(args), budget)
                    .printError(DiagnosticCode.INVALID_ARGUMENT, exception.getMessage());
            commandLine.getErr().println(bytes.toString(StandardCharsets.UTF_8).trim());
            commandLine.getErr().flush();
        } else {
            commandLine.getErr().println(exception.getMessage());
            commandLine.usage(commandLine.getErr());
        }
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }

    private EarlyOutputOptions resolveOutputOptions(String[] args) {
        GlobalOptions parsed = rootCommand.globalOptions();
        String budgetValue = optionValue(args, "--budget", parsed.budget());
        String configPath = optionValue(args, "--config", parsed.configPath());
        BudgetProfile profile;
        try {
            profile = OutputModeResolver.resolveBudgetProfile(budgetValue, configPath);
        } catch (IllegalArgumentException exception) {
            profile = BudgetProfile.STANDARD;
        }
        boolean json = parsed.jsonOutput() || hasOption(args, "--json");
        boolean markdown = parsed.mdOutput() || hasOption(args, "--md");
        boolean protocolOption = false;
        boolean versionOne = false;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.startsWith("--protocol=")) {
                protocolOption = true;
                versionOne = isVersionOne(argument.substring("--protocol=".length()));
            } else if ("--protocol".equals(argument)) {
                protocolOption = true;
                if (i + 1 < args.length) {
                    versionOne = isVersionOne(args[++i]);
                }
            }
        }
        Integer maxBytes = integerOption(args, "--max-bytes", parsed.maxBytes());
        boolean effectiveJson = OutputModeResolver.effectiveJson(json, markdown, profile);
        return new EarlyOutputOptions(
                effectiveJson && (versionOne || protocolOption), profile, maxBytes);
    }

    private static boolean hasOption(String[] args, String option) {
        for (String argument : args) {
            if (option.equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static String optionValue(String[] args, String option, String fallback) {
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.startsWith(option + "=")) {
                return argument.substring(option.length() + 1);
            }
            if (option.equals(argument) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static Integer integerOption(String[] args, String option, Integer fallback) {
        String value = optionValue(args, option, fallback == null ? null : fallback.toString());
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String commandName(String[] args) {
        for (String argument : args) {
            if (catalog.find(argument).isPresent()) {
                return argument;
            }
        }
        return "jsrc";
    }

    private static boolean isVersionOne(String value) {
        return "1".equals(value) || "latest".equalsIgnoreCase(value);
    }

    private record EarlyOutputOptions(
            boolean versionOne, BudgetProfile profile, Integer maxBytes) {}
}
