# jsrc

A Java source code navigator built for AI agents. Uses [Tree-sitter](https://tree-sitter.github.io/) for speed and [JavaParser](https://javaparser.org/) for semantic depth to let agents explore large Java codebases without filling their context window with source code.

## Why jsrc?

An agent with ~200K tokens of context can't read a 10,000-file codebase. jsrc gives the agent structured navigation:

- **"What classes are in this codebase?"** → `jsrc overview --json`
- **"Show me OrderService"** → `jsrc summary OrderService --json`
- **"Who calls validate()?"** → `jsrc callers validate --json`
- **"Find God classes"** → `jsrc lint --all --json`

All responses are compact JSON optimized for token efficiency.

## Installation

Native release bundles include the executable and the Tree-sitter libraries required at runtime. Verify downloads with `checksums.txt` from the same release.

### Linux x64

```bash
curl -fsSL https://github.com/joadpe/jsrc/releases/latest/download/jsrc-linux-x64.tar.gz -o /tmp/jsrc.tar.gz
tar -xzf /tmp/jsrc.tar.gz -C /tmp
mkdir -p ~/.local/bin ~/lib
install /tmp/jsrc-linux-x64/jsrc ~/.local/bin/jsrc
install /tmp/jsrc-linux-x64/lib/*.so ~/lib/
export PATH="$HOME/.local/bin:$PATH"
jsrc describe --json
```

Persist `~/.local/bin` in `PATH` through your shell profile.

### macOS

Both Apple Silicon and Intel are published:

```bash
case "$(uname -m)" in
  arm64) asset="jsrc-macos-arm64" ;;
  x86_64) asset="jsrc-macos-x64" ;;
  *) echo "Unsupported architecture"; exit 1 ;;
esac

curl -fsSL "https://github.com/joadpe/jsrc/releases/latest/download/$asset.tar.gz" -o /tmp/jsrc.tar.gz
tar -xzf /tmp/jsrc.tar.gz -C /tmp
mkdir -p ~/.local/bin ~/lib
install "/tmp/$asset/jsrc" ~/.local/bin/jsrc
install "/tmp/$asset/lib/"*.dylib ~/lib/
export PATH="$HOME/.local/bin:$PATH"
jsrc describe --json
```

Persist `~/.local/bin` in `PATH` through your shell profile.

### Windows x64

Run in PowerShell:

```powershell
$archive = "$env:TEMP\jsrc-windows-x64.zip"
$extract = "$env:TEMP\jsrc-install"
$bin = "$env:LOCALAPPDATA\jsrc\bin"
$lib = "$env:USERPROFILE\lib"

Invoke-WebRequest "https://github.com/joadpe/jsrc/releases/latest/download/jsrc-windows-x64.zip" -OutFile $archive
Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive $archive -DestinationPath $extract
New-Item -ItemType Directory -Force -Path $bin, $lib | Out-Null
Copy-Item "$extract\jsrc-windows-x64\jsrc.exe" "$bin\jsrc.exe"
Copy-Item "$extract\jsrc-windows-x64\lib\*.dll" $lib

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (($userPath -split ";") -notcontains $bin) {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$bin", "User")
}
$env:Path += ";$bin"
jsrc describe --json
```

## Build native binaries from source

All platforms require Git, Maven, GraalVM Community 25+, and the repository:

```bash
git clone https://github.com/joadpe/jsrc.git
cd jsrc
mvn -B -DskipTests package
```

The build scripts compile Tree-sitter from pinned immutable commits, build the native image, run functional index, overview, and read smoke tests, verify that an invalid command fails, and create the distribution archive.

### Linux

Install a C compiler first (for Debian/Ubuntu: `sudo apt install build-essential zlib1g-dev`), then run:

```bash
scripts/build-native-unix.sh linux-x64
```

Output: `dist/jsrc-linux-x64.tar.gz`.

### macOS from source

Install Xcode Command Line Tools with `xcode-select --install`, then run the command matching the machine:

```bash
scripts/build-native-unix.sh macos-arm64  # Apple Silicon
scripts/build-native-unix.sh macos-x64    # Intel
```

Output: `dist/jsrc-macos-arm64.tar.gz` or `dist/jsrc-macos-x64.tar.gz`.

### Windows from source

Install:

- Visual Studio 2022 Build Tools with **Desktop development with C++**
- Git
- Maven
- GraalVM Community 25+, with `JAVA_HOME` and `native-image.cmd` in `PATH`

The Windows build is implemented by `scripts/build-native-windows.ps1`.

Open PowerShell and run (the script imports the Visual Studio build environment through `vswhere.exe`):

```powershell
git clone https://github.com/joadpe/jsrc.git
cd jsrc
mvn -B -DskipTests package
.\scripts\build-native-windows.ps1
```

Output: `dist\jsrc-windows-x64.zip`.

### Run the JAR

The JAR requires Java 22+ and the Tree-sitter native libraries from the matching bundle:

This is the **runtime requirement for jsrc**, not the source version of the project
being analyzed. jsrc recognizes declared Java source levels 8–22 per module.
Java 22 sources are parsed with the Java 21 grammar; unsupported newer syntax
remains quarantined.

```bash
java --enable-native-access=ALL-UNNAMED \
  -Djava.library.path="$HOME/lib" \
  -jar target/jsrc.jar describe --json
```

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

## Global Flags

Flags work before or after the subcommand: `jsrc --json overview` = `jsrc overview --json`.

| Flag | Description |
|------|-------------|
| `--json` | Machine-readable JSON (always use for agents) |
| `--protocol legacy\|1\|latest` | JSON protocol version (default: legacy) |
| `--md` | Markdown output (for context command) |
| `--metrics` | Append execution metrics to stderr |
| `--full` | Verbose output (full signatures, all details) |
| `--no-test` | Exclude test classes from results |
| `--fields f1,f2` | Limit JSON to specific fields (saves tokens) |
| `-d, --dir <path>` | Source root directory (default: current dir) |
| `--budget <profile>` | Budget profile: tiny\|small\|standard (default: standard) |
| `--limit N` | Maximum number of items in output lists |
| `--max-bytes N` | Maximum output size in bytes |
| `--no-budget-meta` | Omit _budget metadata from JSON output |

## Versioned JSON protocol

The existing JSON shapes remain the default under `--protocol legacy`. Agents that need a
stable envelope can opt into protocol v1:

```bash
jsrc overview --json --protocol 1
```

Every v1 document contains `schema`, `protocolVersion`, `command`, `status`, `data`,
`diagnostics`, and `meta`. Status is one of `ok`, `empty`, `partial`, or `error`.
`watch` emits one complete v1 envelope per line. In v1, output limits remove complete
payload fields or items and preserve valid JSON plus truncation diagnostics.

## Budget Profiles

Budget profiles enforce hard limits on output size and command complexity for small/local agents (4-8K context windows).

### Profiles

- **tiny** (~4K context): 10-item limit, core commands only, aggressive degradations
- **small** (~8K context): 30-item limit, most commands, moderate degradations  
- **standard** (default): No restrictions, full command surface

### Usage

```bash
# CLI flag (highest priority)
jsrc --budget tiny overview --json

# Environment variable
export JSRC_BUDGET=small
jsrc overview --json

# Config file .jsrc.yaml (lowest priority)
# budget: tiny
```

Precedence: CLI flag > env var > config file > standard

### Budget Behavior

Under **tiny** budget:
- Forces `--json` output (unless `--md` set)
- Limits list outputs to 10 items  
- **Degrades**: `summary` → executes as `mini` instead
- **Denies**: `read Class` (without method) → exits with structured error suggesting `read Class.method`
- Denies: `context`, `call-chain`, `dump`, `tour`, `map`
- Visible commands (via `describe --budget tiny`): `index`, `overview`, `mini`, `read`, `scope`, `callers`, `validate`, `describe`, `skill`, `classes`

Under **small** budget:
- Forces `--json` output (unless `--md` set)
- Limits list outputs to 30 items
- Allows `summary` and most navigation/analysis commands
- Denies heavy commands (same as tiny)

Denied commands exit with code 2 and structured error JSON:
```json
{"error":"budget_denied","command":"read","budget":"tiny","suggestion":"jsrc read Class.method --json (see jsrc mini Class for method list)"}
```

All JSON output under budget includes `_budget` metadata (opt-out: `--no-budget-meta`):
```json
{"_budget":{"profile":"tiny","degradedFrom":"summary","applied":["limit:10"],"truncated":true},"name":"OrderService",...}
```

**Legacy protocol:** Array-shaped responses preserve their root contract and `_budget`
metadata is only added to object roots. Protocol v1 always uses the stable envelope.

## CLI Dialect

### Canonical Syntax (Picocli Subcommands)

**jsrc uses Picocli subcommands as the canonical CLI syntax.** All commands follow the pattern:

```bash
jsrc <subcommand> [arguments] [--flags]
```

Examples:
- `jsrc overview --json`
- `jsrc summary MyClass --json`
- `jsrc callers myMethod --json`

### Legacy Flag Syntax (Deprecated)

The old flag-based syntax (`jsrc --overview --json`) is **not supported** in the current Picocli implementation. If you see documentation or code references using this syntax, they refer to an older version.

**Migration:**
- Old: `jsrc --overview --json` → New: `jsrc overview --json`
- Old: `jsrc --summary MyClass --json` → New: `jsrc summary MyClass --json`
- Old: `jsrc --callers myMethod --json` → New: `jsrc callers myMethod --json`

If a command fails with exit code 2, verify you are using the subcommand syntax, not the legacy flag syntax.

## Configuration

Create `.jsrc.yaml` in your project root:

When no source level can be verified from Maven/Gradle or this file, JSON v1
reports `SOURCE_LEVEL_UNKNOWN` with partial confidence. Files with unsupported
syntax are excluded from exact semantic results. A frozen index records the
effective source level; after changing build settings or overrides, run
`jsrc index` again before using `--frozen-index`. jsrc does not replace
`javac --release` or verify dependency/API compatibility.
JavaParser 3.27.0 cannot parse Java 11 `var` lambda parameters; jsrc reports
`SOURCE_PARSER_LIMITATION` and quarantines those files instead of presenting
incomplete semantic results as exact.

```yaml
sourceRoots:
  - src/main/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "21"  # Optional source-language override (8–22), not jsrc's runtime JDK
moduleJavaVersions:  # Optional overrides for modules with dynamic/undeclared build settings
  legacy: "8"       # Key is the module path relative to the project root
budget: small  # Optional: tiny|small|standard (default: standard)

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
```

## Persistent Index

The index publishes immutable binary generations under `.jsrc/generations/`.
The small `.jsrc/current` manifest selects the complete generation used by readers;
writers serialize publication with `.jsrc/index.lock` and replace the manifest
atomically. The previous generation is retained for recovery while older ones
are cleaned up. Legacy `.jsrc/index.bin`/JSON indexes are rebuilt on the next
normal index access or explicit `jsrc index`, never reused as trusted snapshots.
Publication checks source hashes, the complete discovered file set, source
levels and build configuration before switching the manifest. Query filters
such as `--no-test` select a result view; they do not shrink the canonical index.

```bash
jsrc index                    # Build the index
jsrc overview --json          # Query the indexed project
# Edit files...
jsrc callers MyMethod --json  # Auto-refreshes changed files + edges
```

Index uses SHA-256 content hashes. Auto-refresh re-extracts call edges for
modified files, so the call graph stays fresh after edits. A normal query after
edits performs the refresh before answering; group related file edits before
querying to pay that cost once. `jsrc batch` executes several queries in one JVM
after that refresh. `--frozen-index` skips the refresh but can return stale
results for uncommitted edits.

After a Git branch switch, normal commands compare source hashes and refresh
changed files. `--frozen-index` does not scan sources: it rejects a snapshot
whose recorded Git tree differs from the current committed tree. For an
uncommitted working tree or a non-Git project, frozen freshness is not verified;
run `jsrc index` after edits before relying on frozen results. Frozen mode does
not migrate or repair legacy/corrupt indexes.

### Watch Mode Session Cache

Watch mode (`jsrc watch`) maintains an in-memory cache of the indexed codebase across multiple commands:

- **First command**: Loads the generation named by `.jsrc/current`
- **Subsequent commands**: Reuses cached index if no file changes detected
- **Automatic refresh**: Compares source content hashes and reloads only when necessary, including equal-size edits with preserved timestamps

This eliminates redundant index loads during interactive sessions, making back-to-back queries instant even without filesystem changes.

#### Watch Envelope Format (Breaking Change)

**IMPORTANT:** As of PR #22, all watch command responses are wrapped in a standard envelope:

```json
{"exit": 0, "result": {...}}
```

- **`exit`**: Integer exit code (0 = success, non-zero = error)
- **`result`**: The actual command output (object, array, or string)

**Migration required:** Clients must parse the envelope structure instead of reading the raw response body directly.

**Examples:**

```json
// Success: overview command
{"exit": 0, "result": {"files": 123, "classes": 456, ...}}

// Success: ambiguous callers (exit 0 with flag)
{"exit": 0, "result": {"ambiguous": true, "candidates": [...]}}

// Error: unknown command
{"exit": 1, "result": {"error": "Unknown command: xyz"}}
```

**Protocol unchanged for:**
- Input format: `{"command": "...", "arg": "..."}`
- Quit: `{"command": "quit"}`

## Test

```bash
mvn test
```

## License

MIT
