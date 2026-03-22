# jsrc

A Java source code navigator built for AI agents. Uses [Tree-sitter](https://tree-sitter.github.io/) for speed and [JavaParser](https://javaparser.org/) for semantic depth to let agents explore large Java codebases without filling their context window with source code.

## Why jsrc?

An agent with ~200K tokens of context can't read a 10,000-file codebase. jsrc gives the agent structured navigation:

- **"What classes are in this codebase?"** → `jsrc overview --json` (77ms for 8,323 files)
- **"Show me OrderService"** → `jsrc summary OrderService --json`
- **"Who calls validate()?"** → `jsrc callers validate --json`
- **"Find God classes"** → `jsrc lint --all --json`

All responses are compact JSON optimized for token efficiency.

## Installation

### macOS / Linux (recommended)

```bash
brew install jsrc
```

Or install the native binary directly:

```bash
curl -fsSL https://github.com/joadpe/jsrc/releases/latest/download/jsrc-macos-arm64 \
  -o ~/bin/jsrc
chmod +x ~/bin/jsrc
```

### From source

```bash
git clone https://github.com/joadpe/jsrc.git
cd jsrc
mvn clean package -DskipTests
java -jar target/jsrc.jar [command]
```

### Native binary (fastest)

```bash
# Build native binary (requires GraalVM CE 25+)
mvn clean package -DskipTests
native-image -jar target/jsrc.jar -o target/jsrc

# Or install via SDKMAN
sdk install java 25.0.2-graalce
mvn clean package -DskipTests
native-image -jar target/jsrc.jar -o target/jsrc
```

Requirements for source build:
- Java 22+ ([SDKMAN](https://sdkman.io/) recommended)
- Maven 3.6.3+
- Tree-sitter native libraries (auto-compiled on first run)

## Quick Start

```bash
# 1. Index the codebase (one-time, auto-refreshes after edits)
jsrc index

# 2. Explore
jsrc overview --json          # Codebase stats
jsrc classes --json           # All classes/interfaces
jsrc summary MyService --json # Class metadata
jsrc callers validate --json  # Who calls this method?
```

## Commands

### Navigation

| Command | Description |
|---------|-------------|
| `overview` | Stats: files, classes, interfaces, methods, packages |
| `classes` | List all classes/interfaces/enums/records (ranked by callers) |
| `--summary <Class>` | Class metadata + method signatures (no bodies) |
| `--mini <Class>` | Quick class overview (~120 tokens, prefer over --summary) |
| `--read <Class>` | Full source code of a class |
| `--read <Class.method>` | Source code of a specific method |
| `--hierarchy <Class>` | Inheritance tree: extends, implements, subclasses |
| `--implements <Interface>` | Find all implementors of an interface |
| `--deps <Class>` | Dependencies: imports, fields, constructor params |
| `--annotations <Name>` | Find all elements with a specific annotation |
| `--related <Class>` | Related classes by coupling (shared imports/callers) |

### Call Graph

| Command | Description |
|---------|-------------|
| `--callers <method>` | Who calls this method? (includes reflective calls) |
| `--callers <Class.method> --full` | Full signature + caller count |
| `--callees <method>` | What does this method call? |
| `--call-chain <method>` | Full call chains from roots to target |
| `--impact <method>` | Change risk: callers + transitive callers + depth |
| `--test-for <method>` | Find tests that cover this method |

### Search

| Command | Description |
|---------|-------------|
| `--search <pattern>` | Text search (supports OR: `TODO\|FIXME`) |
| `--find <keywords>` | Semantic search by keywords |
| `--scope <task>` | Find relevant classes for a task |
| `unused` | Dead code: classes/methods never called |

### Analysis

| Command | Description |
|---------|-------------|
| `--smells <Class>` | Code smells for a class (9 rules) |
| `--smells --all` | All smells in codebase (with topFindings) |
| `--complexity <Class>` | Cyclomatic complexity per method |
| `--complexity --all` | Top 30 classes by complexity |
| `--lint <Class>` | Pre-compile checks + architecture rules |
| `--lint --all` | All lint issues: God classes, mutable statics, high-param methods |
| `hotspots` | Top classes by callers + imports + test coverage |
| `packages` | Package stats: import counts, circular deps |
| `style` | Code style conventions (~75 tokens) |
| `patterns` | Naming patterns and layer conventions |
| `--snippet <type>` | Code template (service, controller, repo) |

### Architecture

| Command | Description |
|---------|-------------|
| `check` | Evaluate all architecture rules from `.jsrc.yaml` |
| `--check <ruleId>` | Evaluate a specific rule |
| `endpoints` | REST endpoints (path, HTTP method, controller) |
| `entry-points` | Main methods and entry points |
| `--validate <Method(Type1,Type2)>` | Validate method exists with exact signature |

### Reverse Engineering

| Command | Description |
|---------|-------------|
| `--context <Class> --json` | Full context: summary + deps + hierarchy + call graph + smells + source |
| `--context <Class> --md` | Markdown spec draft for the class |
| `--contract <Interface>` | Formal contract: methods, params, throws, javadoc |
| `--verify <Class> --spec spec.md` | Compare implementation against spec |
| `drift` | Architecture check + changed file detection |
| `diff` | Files changed since last index (by content hash) |
| `changed` | Java files changed in git (vs HEAD) |

### Meta

| Command | Description |
|---------|-------------|
| `index` | Build/refresh persistent index |
| `describe` | List all commands with args and flags |
| `--describe <command>` | Detail of a specific command |

## Global Flags

| Flag | Description |
|------|-------------|
| `json` | Machine-readable JSON (always use for agents) |
| `md` | Markdown output (for `--context`) |
| `metrics` | Append execution metrics to stderr |
| `full` | Verbose output (full signatures, all details) |
| `all` | Include everything (all smells, all complexity) |
| `--fields f1,f2` | Limit JSON to specific fields (saves tokens) |
| `json` | Output format |

## Configuration

Create `.jsrc.yaml` in your project root:

```yaml
sourceRoots:
  - src/main/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "22"

architecture:
  layers:
    - name: controller
      pattern: "**/*Controller"
    - name: service
      pattern: "**/*Service"
    - name: repository
      pattern: "**/*Repository"

  rules:
    - id: no-repo-in-controller
      from: controller
      denyImport: repository
    - id: constructor-injection
      layer: service
      require: constructor-injection

  invokers:
    - method: invoke
      targetArg: 0
      resolveClass: adapter
      callerSuffixes: [Detail, View, Form]
```

## Persistent Index

The index makes all commands instant:

```bash
jsrc index                    # First run: parses all files (~14min for 8K files)
jsrc overview --json          # Uses index: 77ms
# Edit files...
jsrc overview --json          # Auto-refreshes: only re-parses changed files
```

Index is stored in `.jsrc/index.json` with SHA-256 content hashes.

## Code Smell Detection

9 built-in rules:

| Rule | Severity | Description |
|------|----------|-------------|
| `SWITCH_WITHOUT_DEFAULT` | WARNING | Switch without default case |
| `EMPTY_CATCH_BLOCK` | WARNING | Exception silently swallowed |
| `CATCH_GENERIC_EXCEPTION` | WARNING | Catching Exception/Throwable |
| `EMPTY_IF_BODY` | WARNING | Empty if statement body |
| `METHOD_TOO_LONG` | INFO | Method exceeds 30 lines |
| `TOO_MANY_PARAMETERS` | INFO | Method has more than 5 parameters |
| `DEEP_NESTING` | WARNING | Nesting depth exceeds 4 levels |
| `MAGIC_NUMBER` | INFO | Numeric literal (not 0, 1, -1) |
| `UNUSED_PARAMETER` | INFO | Parameter never used |

## Benchmarks

### vs grep on Spring Boot (8,323 files)

**Model: Claude Sonnet 4 (200K context)**

| Metric | grep | jsrc |
|--------|------|------|
| Tasks completed | 14/30 | **25/30** |
| Time | 4m49s | **3m24s** |
| Tool calls | 78 | **59** |

jsrc completes **78% more tasks** than grep, 24% faster.

**Detailed Results (30 questions):**

| # | Category | Question | grep | jsrc |
|---|----------|----------|------|------|
| **Precision** |
| Q1 | Find | All HealthIndicator implementations | ❌ | ✅ |
| Q2 | Hierarchy | Superclass chain of a class | ✅ | ✅ |
| Q3 | Search | Classes in config package | ✅ | ✅ |
| Q4 | Annotation | @AutoConfiguration with dependencies | ❌ | ✅ |
| Q5 | Search | Classes in configuration package | ✅ | ❌ |
| Q6 | Aggregate | Top 5 classes by method count | ✅ | ✅ |
| Q7 | Search | ConditionMessage source location | ❌ | ✅ |
| Q8 | Code smell | Mutable static fields | ✅ | ✅ |
| **Investigation** |
| Q9 | Impact | Binder.bind callers and impact | ❌ | ✅ |
| Q10 | Filter | Methods with >5 parameters | ❌ | ✅ |
| Q11 | Code smell | Swallowed exception catch blocks | ✅ | ✅ |
| Q12 | Callers | ConfigurationPropertyName.of callers | ❌ | ✅ |
| Q13 | Pipeline | Auto-config processing pipeline | ✅ | ✅ |
| **Code Generation** |
| Q17 | Write | DnsHealthIndicator from pattern | ❌ | ✅ |
| Q18 | Write | Exception wrapper for HTTP client | ❌ | ✅ |
| Q19 | Write | Add retry logic to service | ❌ | ✅ |
| Q20 | Refactor | Extract interface from class | ❌ | ✅ |
| Q21 | Fix | NPE in null check | ✅ | ✅ |
| Q22 | Fix | Thread-safety issue | ✅ | ✅ |
| **Comparative** |
| Q23 | Compare | Logback vs Log4J2 methods | ❌ | ✅ |
| Q24 | Compare | Two actuator endpoints | ❌ | ✅ |
| Q25 | Compare | Two data classes | ✅ | ✅ |
| Q26 | Compare | Request vs Response objects | ✅ | ✅ |
| **Architecture** |
| Q27 | Layers | Layer violations | ✅ | ✅ |
| Q28 | Deps | Circular dependencies | ❌ | ✅ |
| Q29 | Architecture | Package coupling | ❌ | ✅ |
| Q30 | Architecture | God classes (>500 LOC, >20 methods) | ❌ | ✅ |

### Model Comparison

| Model | grep | jsrc | jsrc Advantage |
|-------|------|------|----------------|
| Sonnet (200K ctx) | 14/30 | **25/30** | 1.8x more tasks |
| Qwen 9B (32K ctx) | 0.5/12 | **8/12** | 10x more tasks |

**Key insight:** On small models (9B params), raw grep output overwhelms the context window. jsrc's structured JSON is pre-digested — the model just parses it.

### Native Binary Performance

| Command | Native | JVM | Speedup |
|---------|--------|-----|---------|
| `help` | 3ms | 52ms | **17x** |
| `overview` | 133ms | 383ms | **2.9x** |
| `classes` | 165ms | 499ms | **3x** |
| `--smells --all` | 149ms | 528ms | **3.5x** |

## Test

```bash
mvn test    # 477 tests
```

## License

MIT
