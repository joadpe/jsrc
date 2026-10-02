# jsrc — Java Source Code Navigator for Agents

This skill describes the current `master` checkout. The published v2.5.0 native
release supports the core commands and budget profiles but not JSON protocol v1,
`--source-set`, per-module Java source-level detection, or atomic index
generations. Check your binary with `jsrc --version`, `jsrc describe --json`,
and `jsrc help <command>`; the version string alone cannot distinguish a
tagged release from a later build of `master`. See the
[release/master capability matrix](README.md#documentation-scope) before using
a `master`-only feature.

## What is jsrc?

A CLI tool that lets you navigate and inspect large Java codebases without reading source files. It parses code structure (classes, methods, annotations, inheritance, dependencies) and returns compact JSON optimized for LLM context windows.

**For small/local agents (4-8K context):** Use `jsrc skill --budget tiny` or `jsrc skill --budget small` to get a slim command guide optimized for your budget. This SKILL.md provides comprehensive documentation for all use cases.

## When to use jsrc

- You need to understand a Java codebase structure without reading every file
- You need to find a specific class, method, or annotation across thousands of files
- You need to trace call chains or understand class hierarchies
- You need to check dependencies or detect code smells

## When NOT to use jsrc

- Don't read `.java` files directly if jsrc can answer your question
- Don't parse jsrc text output — always use `--json`

## Required setup

Native release bundles include the executable and its Tree-sitter libraries;
they do not need a JDK at runtime. For a JAR or source build, use Java 22+ and
Maven (the compiler target is 22 in `pom.xml`), plus the matching native
libraries when running the JAR. See [installation and build instructions](README.md#installation).

```bash
jsrc --version
jsrc describe --json
```

## Critical workflow

### Step 1: Index the codebase (do this FIRST)

```bash
cd /path/to/codebase
jsrc index
```

This parses the project and saves a persistent index under `.jsrc/`. Subsequent runs auto-refresh changed files.

- All query commands use the index automatically

### Step 2: Orient yourself

```bash
cd /path/to/codebase
jsrc overview --json
```

Returns: total files, classes, interfaces, methods, and package list.

### Step 3: Query as needed

Always use `--json`. All commands work with or without explicit source root (defaults to `.`).

## Commands reference

<!-- BEGIN GENERATED COMMAND CATALOG -->
| Command | Category | Summary |
|---|---|---|
| `help` | meta | When no COMMAND is given, the usage help for the main command is displayed. |
| `overview` | navigation | Codebase overview: files, classes, methods, packages |
| `classes` | navigation | List all classes/interfaces/enums/records (ranked by callers) |
| `summary` | navigation | Class metadata + method signatures |
| `mini` | navigation | Quick class overview (~120 tokens) |
| `read` | navigation | Source code of a class or method |
| `hierarchy` | navigation | Inheritance tree: extends, implements, subclasses |
| `implements` | navigation | Find all implementors of an interface |
| `deps` | navigation | Dependencies: imports, fields, constructor params |
| `annotations` | navigation | Find all elements with a specific annotation |
| `related` | navigation | Related classes by coupling (shared imports/callers) |
| `callers` | call-graph | Find all methods that call a given method |
| `callees` | call-graph | Find all methods called by a given method |
| `call-chain` | call-graph | Full call chains from roots to target |
| `impact` | call-graph | Change risk: callers + transitive callers + depth |
| `test-for` | call-graph | Find tests that cover a method |
| `search` | search | Text search (supports OR: TODO\|FIXME) |
| `find` | search | Semantic search by keywords |
| `scope` | search | Find relevant classes for a task |
| `unused` | search | Dead code: classes/methods never called |
| `smells` | analysis | Code smell detection (9 rules) |
| `complexity` | analysis | Cyclomatic complexity per method |
| `lint` | analysis | Pre-compile checks + architecture rules |
| `hotspots` | analysis | Top classes by callers + imports + test coverage |
| `packages` | analysis | Package stats import counts circular deps |
| `style` | analysis | Code style conventions |
| `patterns` | analysis | Naming patterns and layer conventions |
| `snippet` | analysis | Code template service controller repo |
| `check` | architecture | Evaluate architecture rules from .jsrc.yaml |
| `endpoints` | architecture | REST endpoints path HTTP method controller |
| `entry-points` | architecture | Main methods and entry points |
| `validate` | architecture | Validate method exists with exact signature |
| `imports` | architecture | Who imports this class |
| `layer` | architecture | List classes in an architectural layer |
| `context` | reverse-engineering | Full context: summary + deps + hierarchy + call graph + smells + source |
| `context-for` | reverse-engineering | Find relevant context for a task |
| `contract` | reverse-engineering | Formal contract methods params throws javadoc |
| `verify` | reverse-engineering | Compare implementation against Markdown spec |
| `drift` | reverse-engineering | Architecture check + changed file detection |
| `diff` | reverse-engineering | Files changed since last index by content hash |
| `changed` | reverse-engineering | Java files changed in git vs HEAD |
| `index` | meta | Build or refresh persistent codebase index |
| `map` | meta | Visual codebase map |
| `batch` | meta | Execute multiple queries from stdin |
| `watch` | meta | Daemon mode send queries via stdin |
| `explain` | meta | Detailed explanation of a class |
| `similar` | meta | Find similar classes |
| `resolve` | meta | Resolve a simple name to fully qualified |
| `history` | meta | Change history for a class |
| `stats` | meta | Metrics for a class |
| `checklist` | meta | Review checklist for a class |
| `type-check` | meta | Type check a class |
| `breaking-changes` | meta | Impact of breaking changes to a class |
| `diff-impact` | meta | Impact analysis of changed files |
| `dump` | meta | Dump binary index as JSON to stdout (debugging) |
| `perf` | meta | Detect performance bottlenecks (loops with linear scan, I/O, allocations) |
| `security` | meta | Static security analysis — SQL injection, path traversal, XXE, secrets |
| `todo` | meta | Extract TODO/FIXME/HACK/XXX with git blame context |
| `flow` | meta | Trace execution flow downward (happy path) |
| `debt` | meta | Technical debt score with ranking |
| `migrate` | meta | Detect Java modernization opportunities (Java 8→17/21) |
| `api` | meta | List public API: classes + methods grouped by package |
| `compat` | meta | Check compatibility for Java version migration |
| `tour` | meta | Guided tour of the codebase for onboarding |
| `doc` | meta | Generate Javadoc drafts for undocumented methods |
| `scaffold` | meta | Generate code following project conventions |
| `describe` | meta | List available commands (budget-aware) |
| `skill` | meta | Compact skill guide for agents (budget-aware) |
| `record` | jfr | Record JFR data from a running JVM |
| `profile` | jfr | Profile a JFR recording file |
| `heap-dump` | jfr | Generate heap dump from a running JVM |
| `heap-analyze` | jfr | Live memory analysis of a running JVM |
<!-- END GENERATED COMMAND CATALOG -->

## Global flags

These flags describe `master`. Verify options on an installed release with
`jsrc help <command>`; `--protocol` and `--source-set` are absent in v2.5.0.

- `--json` — machine-readable JSON output (always use this)
- `--protocol legacy|1|latest` — select JSON protocol (default: legacy; `master` only)
- `--source-set main,test,...` — include selected project source sets (`master` only)
- `--metrics` — append execution metrics to stderr
- `--signature-only` — compact method output (1 line per method)
- `--fields name,packageName` — limit JSON to specific fields (saves tokens)
- `--config path` — use custom config file instead of `.jsrc.yaml`
- `--budget <profile>` — budget profile: tiny|small|standard (default: standard)
- `--limit N` — maximum items in output lists
- `--no-budget-meta` — omit _budget metadata from JSON output

## Versioned JSON protocol (`master` only)

Use `--json --protocol 1` for a stable agent-facing envelope. Every response contains
`schema`, `protocolVersion`, `command`, `status`, `data`, `diagnostics`, and `meta`.
`status` is `ok`, `empty`, `partial`, or `error`; diagnostic codes are stable API.
The `watch` command emits one complete envelope per line. Omit `--protocol` only when
compatibility with the legacy object/array roots is required.

## Budget Profiles for Small/Local Agents

For models with limited context (4-8K tokens), use budget profiles to enforce hard output limits:

```bash
# Get slim agent guide for your budget (recommended)
jsrc skill --budget tiny       # ~2KB guide for 4K context
jsrc skill --budget small      # ~3KB guide for 8K context
jsrc skill --budget tiny --json  # Machine-readable version

# Set budget via flag (highest priority)
jsrc --budget tiny overview --json

# Or via environment variable
export JSRC_BUDGET=small

# Or in .jsrc.yaml
# budget: tiny
```

**Profiles:**
- `tiny`: ~4K context, 10-item limit, core commands only (index, overview, mini, read, scope, callers, validate)
- `small`: ~8K context, 30-item limit, most commands except heavy ones (context, call-chain, dump)
- `standard`: No restrictions (default)

**Behavior:**
- Tiny/small force `--json` output automatically
- **Tiny degrades:** `summary` → runs as `mini`, `read Class` → denies with suggestion to read specific method
- Denied commands exit with code 2 + structured error JSON
- Legacy object-shaped output includes `_budget` metadata (legacy arrays preserve their root contract)
- Protocol v1 carries budget and truncation information in the envelope `meta` and `diagnostics`

**Quick start for tiny budget:**
```bash
export JSRC_BUDGET=tiny
jsrc skill --json              # Get slim command guide for tiny budget
jsrc overview --json
jsrc mini ClassName --json     # summary auto-degrades to this under tiny
jsrc read ClassName.methodName --json  # whole-class reads denied under tiny
```

## Exit codes

- `0` — OK, results found
- `1` — OK, but no results matched
- `2` — Bad arguments (invalid input, unknown command)
- `3` — I/O error

## Invariants

1. **Always use `--json`** — text output is for humans, not agents
2. **Run `jsrc index` before repeated queries** — this builds the persistent index
3. **Index auto-refreshes** — if files changed since indexing, jsrc re-parses only those files automatically
4. **stdout = data, stderr = diagnostics** — parse stdout only
5. **`--signature-only`** saves tokens — use it when you don't need full method metadata
6. **`--metrics`** reports timing — use it to inspect command costs

## Output format (JSON)

JSON output is compact. The examples below are illustrative legacy shapes,
not measured corpus results. On `master`, use `--protocol 1` for the versioned envelope.

### overview
```json
{"totalFiles":3,"totalClasses":2,"totalInterfaces":1,"totalMethods":4,"totalPackages":1,"packages":["com.app"]}
```

### classes
```json
[{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","startLine":10,"endLine":50,"isInterface":false,"isAbstract":false,"methodCount":5}]
```

### summary ClassName
```json
{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","file":"src/main/java/com/app/OrderService.java","modifiers":["public"],"isInterface":false,"methods":[{"name":"create","signature":"public Order create(String name)","startLine":15,"returnType":"Order"}]}
```

### search
```json
[{"name":"process","className":"Service","file":"Service.java","startLine":10,"endLine":25,"signature":"public void process(String input)","returnType":"void","modifiers":["public"],"parameters":[{"type":"String","name":"input"}]}]
```

## Playbooks — What command to use when

### Decision tree

```
What do you need to do?
│
├─ FIX A BUG (have stacktrace/error)
│  1. jsrc read Class.method --json     ← read the failing method
│  2. jsrc mini Class --json            ← understand the class (compact)
│  3. jsrc impact Class.method --json   ← who else is affected?
│  4. jsrc validate Class.fix --json    ← verify fix before writing
│
├─ ADD/EXTEND A FEATURE
│  1. jsrc scope "keywords" --json      ← find WHERE the feature lives
│  2. jsrc mini TopMatch --json         ← understand the class (compact)
│  3. jsrc read Class.existingMethod --json  ← see the PATTERN to follow
│  4. jsrc related Class --json         ← what else to read?
│  5. jsrc checklist Class.method --json ← plan the change
│  6. jsrc validate Class.newMethod --json  ← verify names before writing
│
├─ UNDERSTAND A CODEBASE (new to you)
│  1. jsrc overview --json              ← how big? how many packages?
│  2. jsrc classes --json               ← list all types
│  3. jsrc scope "keyword" --json       ← find area of interest
│  4. jsrc mini Class --json            ← quick summary of key classes
│  5. jsrc related Class --json         ← explore neighborhood
│
├─ REVIEW/AUDIT CODE
│  1. jsrc smells Class --json          ← code smells
│  2. jsrc deps Class --json            ← dependency analysis
│  3. jsrc hierarchy Class --json       ← inheritance tree
│  4. jsrc check --json                 ← architecture rule violations
│
├─ CHANGE A METHOD SIGNATURE
│  1. jsrc impact Class.method --json   ← how many callers?
│  2. jsrc callers Class.method --json  ← exact caller list
│  3. jsrc checklist Class.method --json ← step-by-step plan
│
└─ VERIFY BEFORE WRITING CODE
   1. jsrc validate Class.method --json ← does it exist?
   2. jsrc type-check Class.method --json ← return type correct?
```

### Token budget guide (for small models)

| Context size | Budget profile | Strategy |
|------------|--------|----------|
| Small context | `tiny` | Use `mini` and method-level `read`; limit calls as needed |
| Medium context | `small` | Use `summary` and `related` when they fit |
| Larger context | `standard` | Use full command surface as needed |

### Rules for small models (≤8K)

**For 4K context (use --budget tiny or export JSRC_BUDGET=tiny):**
1. NEVER `cat` a Java file — use `jsrc read Class.method` for specific methods
2. Prefer `jsrc mini` to `jsrc summary` when a compact answer is enough
3. NEVER `jsrc context`, `call-chain`, `dump`, `tour`, or `map` — denied under tiny budget
4. ALWAYS start with `jsrc scope` when you don't know where code is
5. ALWAYS validate method names before generating code
6. Read methods, not classes — `read Class.method` not `read Class`
7. All list outputs automatically limited to 10 items

**For 8K context (use --budget small or export JSRC_BUDGET=small):**
1. Can use `jsrc summary` for moderate-sized classes
2. List outputs limited to 30 items
3. Heavy commands still denied (context, call-chain, etc.)
4. Use `jsrc skill --json` to see available commands for your budget

## AI Agent Commands

Commands designed specifically for AI agent workflows:

```bash
# Anti-hallucination: verify method exists, suggest closest if not
jsrc validate Class.method --json
jsrc validate 'Class.method(Type1,Type2)' --json

# Compact summary for small context windows
jsrc mini ClassName --json

# Related classes ranked by coupling score
jsrc related ClassName --json

# Change impact: transitive callers + risk level
jsrc impact Class.method --json

# Task planner: find relevant classes by keywords
jsrc scope "keyword1 keyword2" --json

# Step-by-step change guide
jsrc checklist Class.method --json

# Return type verification
jsrc type-check Class.method --json
```

## Configuration (.jsrc.yaml)

The `javaVersion` override sets the analyzed project's source language level,
not the Java version required to run jsrc. On `master`, per-module detection
and `moduleJavaVersions` add finer control. Optional; place in the project root:

```yaml
sourceRoots:
  - src/main/java
  - src/generated/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "21"
```

With config, source root argument is optional — jsrc uses `sourceRoots[0]` or pwd.
