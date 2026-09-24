package com.jsrc.app.command.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.BudgetPolicy;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.cli.BudgetRule;
import com.jsrc.app.cli.CommandCatalog;
import com.jsrc.app.cli.CommandCategory;
import com.jsrc.app.cli.CommandCost;
import com.jsrc.app.cli.CommandDescriptor;
import com.jsrc.app.cli.CommandOutputType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandSurfaceParityTest {

    @Test
    void describeAndSkillRenderOnlyTheInjectedCatalog() {
        CommandDescriptor synthetic = syntheticCommand();
        CommandCatalog catalog = new SingleCommandCatalog(synthetic);

        ByteArrayOutputStream describeOut = new ByteArrayOutputStream();
        int describeCount = new DescribeCommand(BudgetProfile.STANDARD, catalog)
                .execute(TestHelpers.buildContextWithJsonOutput(describeOut));

        ByteArrayOutputStream skillOut = new ByteArrayOutputStream();
        int skillCount = new SkillCommand(BudgetProfile.STANDARD, catalog)
                .execute(TestHelpers.buildContextWithJsonOutput(skillOut));

        assertEquals(1, describeCount);
        assertEquals(1, skillCount);
        assertTrue(describeOut.toString(StandardCharsets.UTF_8).contains("synthetic"));
        String skillJson = skillOut.toString(StandardCharsets.UTF_8);
        assertTrue(skillJson.contains("synthetic"));
        assertTrue(skillJson.contains("Canonical synthetic summary"));
    }

    private static CommandDescriptor syntheticCommand() {
        Map<BudgetProfile, BudgetRule> budgets = Map.of(
                BudgetProfile.TINY, rule(),
                BudgetProfile.SMALL, rule(),
                BudgetProfile.STANDARD, rule());
        return new CommandDescriptor(
                "synthetic",
                List.of(),
                "Canonical synthetic summary",
                CommandCategory.META,
                List.of(),
                List.of("--json"),
                CommandOutputType.OBJECT,
                CommandCost.LIGHT,
                budgets,
                List.of("jsrc synthetic --json"));
    }

    private static BudgetRule rule() {
        return new BudgetRule(BudgetPolicy.Action.ALLOW, true, Optional.empty(), Optional.empty());
    }

    private record SingleCommandCatalog(CommandDescriptor command) implements CommandCatalog {
        @Override
        public List<CommandDescriptor> commands() {
            return List.of(command);
        }

        @Override
        public Optional<CommandDescriptor> find(String name) {
            return command.name().equals(name) ? Optional.of(command) : Optional.empty();
        }

        @Override
        public List<CommandDescriptor> visibleCommands(BudgetProfile profile) {
            return List.of(command);
        }

        @Override
        public BudgetRule budgetRule(String name, BudgetProfile profile) {
            return find(name).orElseThrow().budgetRule(profile);
        }
    }
}
