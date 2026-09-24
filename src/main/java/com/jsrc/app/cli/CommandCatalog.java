package com.jsrc.app.cli;

import java.util.List;
import java.util.Optional;

public interface CommandCatalog {
    List<CommandDescriptor> commands();

    Optional<CommandDescriptor> find(String name);

    List<CommandDescriptor> visibleCommands(BudgetProfile profile);

    BudgetRule budgetRule(String name, BudgetProfile profile);
}
