package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.jsrc.app.command.meta.CommandRegistry;
import org.junit.jupiter.api.Test;

class CommandRegistryContractTest {

    @Test
    void registryIsTheSingleCompleteSourceForPicocliAndStandardBudget() {
        CommandCatalog catalog = DefaultCommandRegistry.create();

        List<String> registeredNames = catalog.commands().stream()
                .map(CommandDescriptor::name)
                .toList();
        List<String> picocliNames = List.copyOf(
                JsrcCliFactory.create(catalog).getSubcommands().keySet());
        List<String> standardNames = catalog.visibleCommands(BudgetProfile.STANDARD).stream()
                .map(CommandDescriptor::name)
                .toList();

        assertEquals(new LinkedHashSet<>(registeredNames), new LinkedHashSet<>(picocliNames));
        assertEquals(registeredNames, standardNames);
        assertEquals(registeredNames.size(), new LinkedHashSet<>(registeredNames).size());
        assertEquals(72, registeredNames.size());
    }

    @Test
    void everyRegisteredCommandHasCompleteMetadataAndBudgetRules() {
        CommandCatalog catalog = DefaultCommandRegistry.create();

        for (CommandDescriptor command : catalog.commands()) {
            assertFalse(command.name().isBlank(), "name");
            assertFalse(command.summary().isBlank(), command.name() + " summary");
            assertNotNull(command.category(), command.name() + " category");
            assertNotNull(command.outputType(), command.name() + " output type");
            assertNotNull(command.cost(), command.name() + " cost");
            if (command.outputType() != CommandOutputType.NONE) {
                assertEquals("urn:jsrc:output:" + command.name() + ":1", command.schemaId(),
                        command.name() + " schema");
                assertTrue(command.protocolVersions().contains(1),
                        command.name() + " protocol versions");
            }
            for (BudgetProfile profile : BudgetProfile.values()) {
                assertNotNull(command.budgetRule(profile),
                        command.name() + " budget rule for " + profile);
            }
        }
    }

    @Test
    void describeAndBudgetSurfacesAreDerivedFromTheCatalog() {
        CommandCatalog catalog = DefaultCommandRegistry.create();
        List<String> catalogNames = catalog.commands().stream()
                .map(CommandDescriptor::name)
                .toList();

        assertEquals(catalogNames, List.of(CommandRegistry.knownCommandNames()));
        for (BudgetProfile profile : BudgetProfile.values()) {
            Set<String> expected = new LinkedHashSet<>(catalog.visibleCommands(profile).stream()
                    .map(CommandDescriptor::name)
                    .toList());
            assertEquals(expected, BudgetPolicy.budgetSurface(profile));
        }
    }

    @Test
    void deniedCommandsDeclareTheirAlternativeInTheCatalog() {
        CommandCatalog catalog = DefaultCommandRegistry.create();

        for (String command : List.of("context", "call-chain", "dump", "tour", "map")) {
            BudgetRule rule = catalog.budgetRule(command, BudgetProfile.TINY);
            assertEquals(BudgetPolicy.Action.DENY, rule.action());
            assertFalse(rule.alternative().orElseThrow().isBlank());
        }
    }
}
