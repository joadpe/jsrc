package com.jsrc.app.cli;

import picocli.CommandLine;

public final class JsrcCliFactory {

    private JsrcCliFactory() {}

    public static CommandLine create() {
        return create(DefaultCommandRegistry.create());
    }

    public static CommandLine create(CommandCatalog catalog) {
        if (!(catalog instanceof DefaultCommandRegistry registry)) {
            throw new IllegalArgumentException("Catalog cannot instantiate CLI commands");
        }
        CommandLine root = new CommandLine(new JsrcCommand(catalog));
        for (CommandRegistration registration : registry.registrations()) {
            CommandDescriptor descriptor = registration.descriptor();
            if (!root.getSubcommands().containsKey(descriptor.name())) {
                root.addSubcommand(descriptor.name(), registration.createCommand());
            }
        }
        return root;
    }
}
