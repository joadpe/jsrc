package com.jsrc.app.cli;

import com.jsrc.app.cli.adapters.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;

public final class DefaultCommandRegistry implements CommandCatalog {

    private enum Access {
        TINY,
        SMALL_TINY_ALLOWED,
        SMALL,
        SMALL_TINY_DEGRADE,
        TINY_DEGRADE,
        STANDARD,
        HEAVY
    }

    private static final DefaultCommandRegistry INSTANCE = new DefaultCommandRegistry();

    private final List<CommandRegistration> registrations;
    private final List<CommandDescriptor> commands;
    private final Map<String, CommandDescriptor> byName;

    private DefaultCommandRegistry() {
        registrations = List.of(
                command(CommandLine.HelpCommand::new, CommandCategory.META, CommandOutputType.NONE, CommandCost.LIGHT, Access.STANDARD),
                command(OverviewAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(ClassesAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.ARRAY, CommandCost.LIGHT, Access.TINY),
                command(SummaryAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.SMALL_TINY_DEGRADE),
                command(MiniAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(ReadAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY_DEGRADE),
                command(HierarchyAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL_TINY_ALLOWED),
                command(ImplementsAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DepsAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL_TINY_ALLOWED),
                command(AnnotationsAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(RelatedAdapter::new, CommandCategory.NAVIGATION, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL_TINY_ALLOWED),
                command(CallersAdapter::new, CommandCategory.CALL_GRAPH, CommandOutputType.ARRAY, CommandCost.LIGHT, Access.TINY),
                command(CalleesAdapter::new, CommandCategory.CALL_GRAPH, CommandOutputType.ARRAY, CommandCost.LIGHT, Access.SMALL_TINY_ALLOWED),
                command(CallChainAdapter::new, CommandCategory.CALL_GRAPH, CommandOutputType.ARRAY, CommandCost.HEAVY, Access.HEAVY,
                        "jsrc callers <Class.method> --json (for single-level callers)"),
                command(ImpactAdapter::new, CommandCategory.CALL_GRAPH, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(TestForAdapter::new, CommandCategory.CALL_GRAPH, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(SearchAdapter::new, CommandCategory.SEARCH, CommandOutputType.ARRAY, CommandCost.LIGHT, Access.SMALL_TINY_ALLOWED),
                command(FindAdapter::new, CommandCategory.SEARCH, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL_TINY_ALLOWED),
                command(ScopeAdapter::new, CommandCategory.SEARCH, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(UnusedAdapter::new, CommandCategory.SEARCH, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.STANDARD),
                command(SmellsAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL_TINY_ALLOWED),
                command(ComplexityAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(LintAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.SMALL),
                command(HotspotsAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(PackagesAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(StyleAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(PatternsAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(SnippetAdapter::new, CommandCategory.ANALYSIS, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(CheckAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(EndpointsAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(EntryPointsAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(ValidateAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(ImportsAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(LayerAdapter::new, CommandCategory.ARCHITECTURE, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(ContextAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.HEAVY,
                        "jsrc mini <Class> --json (for summary)"),
                command(ContextForAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(ContractAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(VerifyAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DriftAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DiffAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(ChangedAdapter::new, CommandCategory.REVERSE_ENGINEERING, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(IndexAdapter::new, CommandCategory.META, CommandOutputType.NONE, CommandCost.STANDARD, Access.TINY),
                command(MapAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.HEAVY,
                        "jsrc overview --json (for project overview)"),
                command(BatchAdapter::new, CommandCategory.META, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(WatchAdapter::new, CommandCategory.META, CommandOutputType.STREAM, CommandCost.STANDARD, Access.STANDARD),
                command(ExplainAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(SimilarAdapter::new, CommandCategory.META, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(ResolveAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(HistoryAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(StatsAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(ChecklistAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.STANDARD),
                command(TypeCheckAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.SMALL_TINY_ALLOWED),
                command(BreakingChangesAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DiffImpactAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DumpAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.HEAVY,
                        "Not available under constrained budget profiles"),
                command(PerfAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(SecurityAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(TodoAdapter::new, CommandCategory.META, CommandOutputType.ARRAY, CommandCost.STANDARD, Access.STANDARD),
                command(FlowAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DebtAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(MigrateAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(ApiAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(CompatAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(TourAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.HEAVY,
                        "jsrc overview --json (for project overview)"),
                command(DocAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(ScaffoldAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.STANDARD, Access.STANDARD),
                command(DescribeAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(SkillAdapter::new, CommandCategory.META, CommandOutputType.OBJECT, CommandCost.LIGHT, Access.TINY),
                command(RecordAdapter::new, CommandCategory.JFR, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.STANDARD),
                command(ProfileAdapter::new, CommandCategory.JFR, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.STANDARD),
                command(HeapDumpAdapter::new, CommandCategory.JFR, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.STANDARD),
                command(HeapAnalyzeAdapter::new, CommandCategory.JFR, CommandOutputType.OBJECT, CommandCost.HEAVY, Access.STANDARD));

        List<CommandDescriptor> materialized = new ArrayList<>(registrations.size());
        Map<String, CommandDescriptor> indexed = new LinkedHashMap<>();
        for (CommandRegistration registration : registrations) {
            CommandDescriptor descriptor = registration.descriptor();
            if (descriptor.name().isBlank()) {
                throw new IllegalArgumentException("Command name must not be blank");
            }
            if (indexed.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate command: " + descriptor.name());
            }
            materialized.add(descriptor);
        }
        commands = List.copyOf(materialized);
        byName = Map.copyOf(indexed);
    }

    public static DefaultCommandRegistry create() {
        return INSTANCE;
    }

    List<CommandRegistration> registrations() {
        return registrations;
    }

    @Override
    public List<CommandDescriptor> commands() {
        return commands;
    }

    @Override
    public Optional<CommandDescriptor> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<CommandDescriptor> visibleCommands(BudgetProfile profile) {
        return commands.stream().filter(command -> command.budgetRule(profile).visible()).toList();
    }

    @Override
    public BudgetRule budgetRule(String name, BudgetProfile profile) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown command: " + name))
                .budgetRule(profile);
    }

    private static CommandRegistration command(
            Supplier<?> factory,
            CommandCategory category,
            CommandOutputType outputType,
            CommandCost cost,
            Access access) {
        return command(factory, category, outputType, cost, access, null);
    }

    private static CommandRegistration command(
            Supplier<?> factory,
            CommandCategory category,
            CommandOutputType outputType,
            CommandCost cost,
            Access access,
            String alternative) {
        return new CommandRegistration(factory, category, outputType, cost, rules(access, alternative));
    }

    private static Map<BudgetProfile, BudgetRule> rules(Access access, String alternative) {
        EnumMap<BudgetProfile, BudgetRule> rules = new EnumMap<>(BudgetProfile.class);
        rules.put(BudgetProfile.STANDARD, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
        switch (access) {
            case TINY -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
            }
            case SMALL_TINY_ALLOWED -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.ALLOW, false));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
            }
            case SMALL -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.DENY, false));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
            }
            case SMALL_TINY_DEGRADE -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.DEGRADE, false));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.ALLOW, true));
            }
            case TINY_DEGRADE -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.DEGRADE, true));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.DEGRADE, true));
            }
            case STANDARD -> {
                rules.put(BudgetProfile.TINY, BudgetRule.of(BudgetPolicy.Action.DENY, false));
                rules.put(BudgetProfile.SMALL, BudgetRule.of(BudgetPolicy.Action.ALLOW, false));
            }
            case HEAVY -> {
                Optional<String> suggested = Optional.ofNullable(alternative);
                rules.put(BudgetProfile.TINY, new BudgetRule(
                        BudgetPolicy.Action.DENY, false, suggested, Optional.of("Budget limit")));
                rules.put(BudgetProfile.SMALL, new BudgetRule(
                        BudgetPolicy.Action.DENY, false, suggested, Optional.of("Budget limit")));
            }
            default -> throw new IllegalStateException("Unsupported access: " + access);
        }
        return Map.copyOf(rules);
    }
}
