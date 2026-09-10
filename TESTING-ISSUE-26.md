# Testing Watch Per-Request Budget (Issue #26)

## Test Environment Requirements

The watch budget feature tests require:
- Java 22 (or compatible JDK)
- Maven 3.8+
- Tree-sitter native libraries (included in jar after `mvn package`)
- A working jsrc index (created with `jsrc index`)

## Contract Tests

Located in: `src/test/java/com/jsrc/app/command/WatchBudgetContractTest.java`

### Test Cases (A1-A8)

- **A1**: `budget tiny` → result ≤2048 bytes OR `_budget.truncated:true`
- **A2**: omit budget → STANDARD (no limits; no forced `_budget`)
- **A3**: `budget "invalid"` → exit ≠ 0 + error mentioning valid values
- **A4**: callers + budget tiny still returns refs correctly under constraints
- **A5**: warm cache still works; budget applied per-request independently
- **A6**: CLI overview `--budget tiny` regression (unchanged) — **PASSES**
- **A7**: `budget small` → SMALL maxBytes 8192 path
- **A8**: invalid type/null mishandling → exit ≠ 0 + error

### Running Contract Tests

```bash
# Run all contract tests
mvn test -Dtest=WatchBudgetContractTest

# Run specific test
mvn test -Dtest=WatchBudgetContractTest#a6_cliOneshotBudgetUnchanged
```

## Manual Testing

### Setup

```bash
# Build project
mvn clean package -DskipTests

# Create test project
mkdir test-project
cd test-project
cat > Handler.java << 'EOF'
package demo;
public class Handler {
    public void process() {}
    public void handle() {}
    public void execute() {}
}
EOF

# Create index
java -jar ../target/jsrc.jar index
```

### Test Scenarios

#### Scenario 1: Budget Tiny

```bash
# Start watch mode
java -jar ../target/jsrc.jar watch

# Send request with tiny budget
{"command":"overview","budget":"tiny"}

# Expected: response ≤ 2048 bytes OR _budget.truncated:true
# Expected: exit: 0
```

#### Scenario 2: Budget Omitted (Default STANDARD)

```bash
# Send request without budget
{"command":"overview"}

# Expected: no _budget metadata (STANDARD = unlimited)
# Expected: exit: 0
```

#### Scenario 3: Invalid Budget String

```bash
# Send request with invalid budget
{"command":"overview","budget":"invalid"}

# Expected: exit: 2 (BAD_USAGE)
# Expected: result with error field mentioning "tiny, small, standard"
```

#### Scenario 4: Invalid Budget Type

```bash
# Send request with numeric budget (invalid type)
{"command":"overview","budget":123}

# Expected: exit: 2 (BAD_USAGE)
# Expected: result with error field about type mismatch
```

#### Scenario 5: Budget Small

```bash
# Send request with small budget
{"command":"overview","budget":"small"}

# Expected: response ≤ 8192 bytes OR _budget.truncated:true
# Expected: exit: 0
```

#### Scenario 6: Independent Per-Request Budgets

```bash
# Send multiple requests with different budgets
{"command":"overview","budget":"tiny"}
{"command":"overview"}
{"command":"overview","budget":"small"}

# Expected: each response respects its own budget independently
# Expected: cache still works (no redundant index loads)
```

### Quit Watch Mode

```bash
{"command":"quit"}
```

## Implementation Details

### Changes to WatchCommand

1. **Parse `budget` field** from JSON request (optional string)
2. **Validate type**: must be string (not numeric, null, etc.)
3. **Create BudgetContext** from BudgetProfile.fromString()
4. **Pass to OutputFormatter**: `OutputFormatter.create(..., budgetContext)`
5. **Error handling**: exit 2 + descriptive error for invalid values

### Behavior

- **Omitted**: STANDARD profile (no limits, backward compatible)
- **"tiny"**: TINY profile (2048 bytes max, 10 item limit)
- **"small"**: SMALL profile (8192 bytes max, 30 item limit)
- **"standard"**: STANDARD profile explicitly
- **Invalid string**: exit 2 + error listing valid values
- **Invalid type**: exit 2 + error explaining type requirement

### Exit Codes

- **0**: Success (valid request, budget applied if present)
- **1**: Unknown command
- **2**: BAD_USAGE (invalid budget)
- **3**: Runtime error

## Known Limitations

- Native tree-sitter libraries required for full functionality
- Tests A1-A5, A7-A8 require HybridJavaParser (needs tree-sitter)
- Test A6 (CLI regression) passes without tree-sitter dependency
- Manual testing requires a working index

## GREEN Criteria

Per issue #26 lean pipeline:
- Contract tests written (RED where gaps) ✅
- Production implementation ✅
- **GREEN**: Requires `mvn test` evidence in agent VM
  - A6 test passes independently ✅
  - Full test suite requires tree-sitter native libs

## Next Steps

1. Run tests in environment with tree-sitter libs
2. Verify all A1-A8 tests pass
3. Perform manual smoke tests
4. Create PR against master
5. Await DESIGN-OK in Review-1
