# Command Hints Map

Each command lists its `nextCommands` — the most useful follow-up commands given the result context.

Rules:
- Max 3-5 hints per command output
- Hints are resolved with real data from the result (className, methodName, etc.)
- One pattern hint is allowed when referencing a list (e.g., siblingMethods)
- Hints guide workflow, not enumerate possibilities

## Navigation Commands

### overview
Context: just landed on the codebase, need orientation.
```
nextCommands:
  - "find {keyword}"           → "Search for relevant classes"
  - "read {topClass}"          → "Read the most important class" (pick from topClasses[0])
  - "hotspots"                 → "See most-used classes"
  - "map"                      → "Visual codebase map"
  - "tour"                     → "Guided tour of the codebase"
```

### find
Context: searched by keyword, got a list of matching classes.
```
nextCommands:
  - "read {firstMatch}"       → "Read this class"
  - "mini {firstMatch}"       → "Quick overview of this class"
  - "callers {firstMatch}"    → "Who uses this class?"
```

### read (class)
Context: reading a full class, has methods list.
```
nextCommands:
  - "read {class}.METHOD"     → "Read a specific method (see methods list above)"
  - "mini {class}"            → "Compact class summary"
  - "smells {class}"          → "Check for code smells"
  - "deps {class}"            → "See dependencies"
```

### read (method)
Context: reading a specific method, has siblingMethods.
```
nextCommands:
  - "callers {method}"        → "Who calls this method?"
  - "impact {method}"         → "Change risk assessment"
  - "test-for {class}.{method}" → "Find tests for this method"
  - "callees {method}"        → "What does this method call?"
  - "read {class}.METHOD"     → "Read a sibling method (see siblingMethods above)"
```

### mini
Context: got compact class overview with key methods.
```
nextCommands:
  - "read {class}.{firstKeyMethod}" → "Read the most important method"
  - "read {class}"            → "Read full class source"
  - "hierarchy {class}"       → "See inheritance tree"
  - "deps {class}"            → "See dependencies"
```

### summary
Context: got class metadata + method signatures.
```
nextCommands:
  - "read {class}.{method}"   → "Read a specific method"
  - "smells {class}"          → "Check for code smells"
  - "hierarchy {class}"       → "See inheritance tree"
```

## Call Graph Commands

### callers
Context: found who calls a method.
```
nextCommands:
  - "read {callerClass}.{callerMethod}" → "Read the calling method"
  - "impact {method}"         → "Full change risk assessment"
  - "call-chain {method}"     → "Trace full call chain to roots"
  - "breaking-changes {class}" → "Impact of changing this class"
```

### callees
Context: found what a method calls.
```
nextCommands:
  - "read {calleeClass}.{calleeMethod}" → "Read the called method"
  - "callers {method}"        → "Who calls the original method?"
  - "flow {method}"           → "Trace execution flow downward"
```

### call-chain
Context: got full call chain from roots to target.
```
nextCommands:
  - "read {rootClass}.{rootMethod}" → "Read a root caller"
  - "impact {method}"         → "Change risk assessment"
  - "test-for {class}.{method}" → "Find tests covering this chain"
```

### impact
Context: got risk level + caller counts (already shows callers).
```
nextCommands:
  - "test-for {class}.{method}" → "Check test coverage"
  - "breaking-changes {class}" → "Full breaking change analysis"
  - "read {class}.{method}"   → "Read the method source"
```

### test-for
Context: found tests that cover a method.
```
nextCommands:
  - "read {testClass}.{testMethod}" → "Read the test"
  - "read {class}.{method}"        → "Read the method being tested"
```

### flow
Context: traced execution path.
```
nextCommands:
  - "read {class}.{method}"   → "Read a method in the flow"
  - "callers {method}"        → "Who triggers this flow?"
  - "callees {method}"        → "What does this method call?"
```

## Analysis Commands

### smells
Context: found code smells in a class.
```
nextCommands:
  - "read {class}.{smellMethod}" → "Read the problematic method"
  - "complexity {class}"      → "Check cyclomatic complexity"
  - "lint {class}"            → "Pre-compile checks"
```

### complexity
Context: got cyclomatic complexity per method.
```
nextCommands:
  - "read {class}.{highComplexityMethod}" → "Read the most complex method"
  - "smells {class}"          → "Check for code smells"
```

### lint
Context: got pre-compile warnings/errors.
```
nextCommands:
  - "read {class}.{errorMethod}" → "Read the problematic method"
  - "validate {method}(types)" → "Validate method reference"
```

### debt
Context: got technical debt score.
```
nextCommands:
  - "smells {topDebtClass}"   → "Analyze the highest-debt class"
  - "read {topDebtClass}"     → "Read the highest-debt class"
  - "complexity {topDebtClass}" → "Check complexity of highest-debt class"
```

### perf
Context: found performance bottlenecks.
```
nextCommands:
  - "read {class}.{perfMethod}" → "Read the bottleneck method"
  - "callers {perfMethod}"    → "Who triggers this bottleneck?"
```

### security
Context: found security issues.
```
nextCommands:
  - "read {class}.{vulnMethod}" → "Read the vulnerable method"
  - "callers {vulnMethod}"    → "Who calls this vulnerable code?"
  - "impact {vulnMethod}"     → "Change risk for fixing this"
```

## Structure Commands

### hierarchy
Context: got inheritance tree.
```
nextCommands:
  - "read {subclass}"         → "Read a subclass"
  - "implements {interface}"  → "Find all implementors"
  - "breaking-changes {class}" → "Impact of changing this class"
```

### implements
Context: found implementors of an interface.
```
nextCommands:
  - "read {implementor}"      → "Read an implementor"
  - "hierarchy {interface}"   → "See full inheritance tree"
```

### deps
Context: got class dependencies.
```
nextCommands:
  - "read {dependency}"       → "Read a dependency"
  - "related {class}"         → "Find coupled classes"
  - "imports {class}"         → "Who imports this class?"
```

### related
Context: found coupled classes.
```
nextCommands:
  - "read {relatedClass}"     → "Read a related class"
  - "deps {class}"            → "See dependencies"
```

### imports
Context: found who imports a class.
```
nextCommands:
  - "read {importer}"         → "Read the importing class"
  - "breaking-changes {class}" → "Impact of changing this class"
```

## Search Commands

### search
Context: found text matches.
```
nextCommands:
  - "read {matchClass}"       → "Read the matching class"
  - "find {keyword}"          → "Semantic search instead"
```

### scope
Context: found relevant classes for a task.
```
nextCommands:
  - "read {scopeClass}"       → "Read a relevant class"
  - "mini {scopeClass}"       → "Quick overview"
```

## Quality Commands

### hotspots
Context: got top classes by usage.
```
nextCommands:
  - "read {hotspotClass}"     → "Read the most-used class"
  - "smells {hotspotClass}"   → "Check for code smells"
  - "debt"                    → "Technical debt overview"
```

### unused
Context: found dead code.
```
nextCommands:
  - "callers {unusedMethod}"  → "Verify it's truly unused"
  - "read {unusedClass}"      → "Read the unused class"
```

### todo
Context: found TODO/FIXME items.
```
nextCommands:
  - "read {todoClass}.{todoMethod}" → "Read the method with TODO"
```

## Architecture Commands

### check
Context: evaluated architecture rules.
```
nextCommands:
  - "read {violationClass}"   → "Read the violating class"
  - "layer {layerName}"       → "List classes in a layer"
```

### endpoints
Context: found REST endpoints.
```
nextCommands:
  - "read {controllerClass}.{endpointMethod}" → "Read the endpoint"
  - "flow {endpointMethod}"   → "Trace execution from endpoint"
```

### breaking-changes
Context: got impact of changes.
```
nextCommands:
  - "read {affectedClass}"    → "Read an affected class"
  - "test-for {class}.{method}" → "Check test coverage"
```

### diff-impact
Context: analyzed changed files.
```
nextCommands:
  - "read {changedClass}"     → "Read a changed class"
  - "test-for {changedClass}.{method}" → "Find tests for changes"
  - "breaking-changes {changedClass}" → "Check breaking changes"
```

## Documentation Commands

### explain
Context: got detailed class explanation.
```
nextCommands:
  - "read {class}"            → "Read the source code"
  - "hierarchy {class}"       → "See inheritance"
```

### contract
Context: got formal method contracts.
```
nextCommands:
  - "read {class}.{method}"   → "Read the method source"
  - "test-for {class}.{method}" → "Find tests"
```

### tour
Context: onboarding guided tour.
```
nextCommands:
  - "read {tourClass}"        → "Read a class from the tour"
  - "map"                     → "Visual codebase map"
```

## Additional Commands

### map
Context: visual codebase map.
```
nextCommands:
  - "read {class}"            → "Read a class from the map"
  - "hotspots"                → "See most-used classes"
```

### api
Context: listed public API by package.
```
nextCommands:
  - "read {class}.{method}"   → "Read an API method"
  - "contract {class}"        → "Formal contracts for this class"
```

### similar
Context: found similar classes.
```
nextCommands:
  - "read {similarClass}"     → "Read the similar class"
  - "related {class}"         → "Find coupled classes"
```

### checklist
Context: got review checklist for a class.
```
nextCommands:
  - "read {class}.{method}"   → "Read a method to review"
  - "smells {class}"          → "Check for code smells"
  - "lint {class}"            → "Pre-compile checks"
```

### type-check
Context: type analysis results.
```
nextCommands:
  - "read {class}.{method}"   → "Read the problematic method"
  - "lint {class}"            → "Pre-compile checks"
```

### migrate
Context: found Java modernization opportunities.
```
nextCommands:
  - "read {class}.{method}"   → "Read the method to modernize"
  - "compat {version}"        → "Check compatibility for target version"
```

### compat
Context: checked compatibility for version migration.
```
nextCommands:
  - "migrate"                 → "Find modernization opportunities"
  - "read {class}"            → "Read an affected class"
```

### profile
Context: analyzed JFR recording.
```
nextCommands:
  - "read {class}.{hotMethod}" → "Read the hot method"
  - "perf {class}"            → "Static performance analysis"
```

## Commands with no hints (terminal/meta)

- `index` — infra, no follow-up needed
- `batch` — meta, runs other commands
- `watch` — daemon mode
- `dump` — debugging
- `record` — JFR recording (produces data for `profile`)
- `scaffold` — code generation
- `doc` — doc generation
- `style` — conventions reference
- `patterns` — patterns reference
- `validate` — boolean check
- `resolve` — name resolution
- `history` — git history
- `stats` — standalone metrics
