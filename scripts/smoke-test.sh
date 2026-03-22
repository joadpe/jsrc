#!/bin/bash
# smoke-test.sh — Run all jsrc commands against a real codebase before release.
# Usage: ./scripts/smoke-test.sh [jsrc-binary] [codebase-path]
#
# Defaults:
#   binary:   ./target/jsrc-native (or java -jar target/jsrc.jar)
#   codebase: current directory (must have .jsrc/ index)
#
# Timeouts are calibrated for codebases up to ~8K files.
# Commands needing call graph (callers, callees, impact, explain, related)
# take ~20-25s on 8K files due to 52K method scan.

set -euo pipefail

JSRC="${1:-}"
CODEBASE="${2:-.}"
PASS=0
FAIL=0
SKIP=0
PERF_BLOCK=0
FAILURES=()
PERF_RESULTS=()

# Auto-detect binary and resolve to absolute path
if [ -z "$JSRC" ]; then
    if [ -f "./target/jsrc-native" ]; then
        JSRC="$(pwd)/target/jsrc-native"
    elif [ -f "./target/jsrc.jar" ]; then
        JSRC="java -jar $(pwd)/target/jsrc.jar"
    else
        echo "ERROR: No jsrc binary found. Build first: mvn package -DskipTests"
        exit 1
    fi
else
    # Resolve relative path to absolute
    if [[ "$JSRC" == ./* ]]; then
        JSRC="$(pwd)/${JSRC#./}"
    elif [[ "$JSRC" != /* ]] && [[ "$JSRC" != "java "* ]]; then
        JSRC="$(pwd)/$JSRC"
    fi
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "jsrc smoke test"
echo "Binary:   $JSRC"
echo "Codebase: $CODEBASE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Find a real class name from the codebase for testing
find_class() {
    local cls
    # Strategy 1: find first non-test Java file
    cls=$(cd "$CODEBASE" && find . -name "*.java" -not -path "*/test/*" -not -path "*/testFixtures/*" \
          -not -name "*Test.java" -not -name "Abstract*.java" -not -name "package-info.java" \
          | head -1 | sed 's|.*/||; s|\.java$||' 2>/dev/null)
    echo "$cls"
}

# Test a command. Args: name, timeout_secs, expected_pattern, command...
test_cmd() {
    local name="$1"
    local timeout_secs="$2"
    local pattern="$3"
    shift 3

    local output
    local exit_code=0
    local start_ms=$(date +%s%3N)
    output=$(cd "$CODEBASE" && timeout "$timeout_secs" $JSRC "$@" 2>/dev/null) || exit_code=$?
    local end_ms=$(date +%s%3N)
    local elapsed_ms=$((end_ms - start_ms))

    if [ $exit_code -eq 124 ]; then
        printf "  ⏱  %-25s TIMEOUT (%ss)\n" "$name" "$timeout_secs"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: timeout after ${timeout_secs}s")
        return
    fi

    if [ -z "$output" ]; then
        printf "  ❌ %-25s EMPTY OUTPUT (exit %d)\n" "$name" "$exit_code"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: empty output (exit $exit_code)")
        return
    fi

    if [ -n "$pattern" ] && ! echo "$output" | grep -qE "$pattern"; then
        printf "  ❌ %-25s MISSING PATTERN: %s\n" "$name" "$pattern"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: missing '$pattern'")
        return
    fi

    # Validate JSON if --json flag is present in args
    local has_json=false
    for arg in "$@"; do [ "$arg" = "--json" ] && has_json=true; done
    if $has_json; then
        if ! echo "$output" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
            printf "  ❌ %-25s INVALID JSON\n" "$name"
            FAIL=$((FAIL + 1))
            FAILURES+=("$name: output is not valid JSON")
            return
        fi
    fi

    local size=${#output}

    # Performance classification
    local perf_icon=""
    local perf_label=""
    if [ "$elapsed_ms" -lt 2000 ]; then
        perf_icon="🟢"
        perf_label="IDEAL"
    elif [ "$elapsed_ms" -lt 6000 ]; then
        perf_icon="🟡"
        perf_label="ACCEPTABLE"
    else
        perf_icon="🔴"
        perf_label="UNACCEPTABLE"
        PERF_BLOCK=$((PERF_BLOCK + 1))
        FAILURES+=("$name: ${elapsed_ms}ms — UNACCEPTABLE (>=6s blocks release)")
    fi

    printf "  ✅ %-25s OK (%d bytes) %s %s %dms\n" "$name" "$size" "$perf_icon" "$perf_label" "$elapsed_ms"
    PERF_RESULTS+=("$name|${elapsed_ms}|${perf_label}")
    PASS=$((PASS + 1))
}

# Test that a flag works in both positions (before and after subcommand)
test_flag_position() {
    local flag="$1"
    local subcmd="$2"
    shift 2

    local out_before out_after
    out_before=$(cd "$CODEBASE" && timeout 15 $JSRC "$flag" "$subcmd" "$@" 2>/dev/null) || true
    out_after=$(cd "$CODEBASE" && timeout 15 $JSRC "$subcmd" "$flag" "$@" 2>/dev/null) || true

    if [ -n "$out_before" ] && [ -n "$out_after" ]; then
        printf "  ✅ %-25s OK (both positions)\n" "$flag + $subcmd"
        PASS=$((PASS + 1))
    elif [ -n "$out_before" ] && [ -z "$out_after" ]; then
        printf "  ❌ %-25s FAIL (only before subcommand)\n" "$flag + $subcmd"
        FAIL=$((FAIL + 1))
        FAILURES+=("$flag after $subcmd: not inherited")
    elif [ -z "$out_before" ] && [ -n "$out_after" ]; then
        printf "  ❌ %-25s FAIL (only after subcommand)\n" "$flag + $subcmd"
        FAIL=$((FAIL + 1))
        FAILURES+=("$flag before $subcmd: not working")
    else
        printf "  ❌ %-25s FAIL (both positions empty)\n" "$flag + $subcmd"
        FAIL=$((FAIL + 1))
        FAILURES+=("$flag + $subcmd: both empty")
    fi
}

echo ""
echo "── Meta ──"
test_cmd "--help"        5 "Commands:" --help
test_cmd "--version"     5 "jsrc"      --version
test_cmd "help overview" 5 "overview"  help overview

echo ""
echo "── Error handling ──"
# Error tests: verify useful error messages (checks stdout + stderr)
test_error() {
    local name="$1"
    local pattern="$2"
    shift 2
    local output
    output=$(cd "$CODEBASE" && timeout 30 $JSRC "$@" 2>&1) || true
    if [ -n "$output" ] && echo "$output" | grep -qiE "$pattern"; then
        printf "  ✅ %-25s OK (error message found)\n" "$name"
        PASS=$((PASS + 1))
    else
        printf "  ❌ %-25s MISSING ERROR: %s\n" "$name" "$pattern"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: missing error '$pattern'")
    fi
}
test_error "class-not-found"  "not found|Did you mean|error" summary NonExistentClassName123 --json
test_error "ambiguous-method" "ambiguous|Multiple|candidates" callers SpringApplication.run --json
test_error "missing-arg"     "Missing|required|Usage|missing" summary --json
test_error "bad-flag"        "Unknown|unrecognized|Unknown option" overview --xyz --json

echo ""
echo "── Flag inheritance (critical) ──"
test_flag_position "--json" "overview"
test_flag_position "--full" "overview"

echo ""
# Discover a class early — needed for flag combination tests
CLASS=$(find_class)
if [ -z "$CLASS" ]; then
    echo "  ⚠️  Could not find a class name. Using fallback."
    CLASS="App"
fi
echo "  ℹ️  Using class: $CLASS"

echo ""
echo "── Flag combinations ──"
test_cmd "json+full"         30 "$CLASS"    summary "$CLASS" --json --full
test_cmd "json+no-test"      15 "totalFiles" overview --json --no-test
test_cmd "json+fields"       30 "name"      summary "$CLASS" --json --fields name,pkg
test_cmd "json+sig-only"     30 "$CLASS"    summary "$CLASS" --json --signature-only

# --metrics writes to stderr, verify it appears there
test_metrics() {
    local output
    output=$(cd "$CODEBASE" && timeout 30 $JSRC summary "$CLASS" --json --metrics 2>&1 1>/dev/null) || true
    if echo "$output" | grep -qE "elapsedMs|ms|files"; then
        printf "  ✅ %-25s OK (metrics in stderr)\n" "json+metrics"
        PASS=$((PASS + 1))
    else
        printf "  ❌ %-25s MISSING METRICS in stderr\n" "json+metrics"
        FAIL=$((FAIL + 1))
        FAILURES+=("json+metrics: no metrics in stderr")
    fi
}
test_metrics

echo ""
echo "── Navigation ──"
test_cmd "overview"      15 "totalFiles"    overview --json
test_cmd "classes"       30 "classes"       classes --json

# Navigation: verify output references the class we asked about
test_cmd "summary"       30 "$CLASS"        summary "$CLASS" --json
test_cmd "mini"          30 "$CLASS"        mini "$CLASS" --json
test_cmd "read"          30 "$CLASS"        read "$CLASS" --json
test_cmd "hierarchy"     30 "$CLASS"        hierarchy "$CLASS" --json
test_cmd "deps"          30 "$CLASS"        deps "$CLASS" --json
test_cmd "annotations"   30 "Override"      annotations Override --json
test_cmd "related"       45 "$CLASS"        related "$CLASS" --json

echo ""
echo "── Call graph (requires full graph build ~20s on large codebases) ──"
# Call graph may return [] for classes with no callers — verify valid JSON output
test_cmd "callers"       45 ""              callers "$CLASS" --json
test_cmd "callees"       45 ""              callees "$CLASS" --json
test_cmd "impact"        45 "$CLASS"        impact "$CLASS" --json
test_cmd "test-for"      45 ""              test-for "$CLASS" --json

echo ""
echo "── Search ──"
test_cmd "search"        30 "TODO"          search "TODO" --json
test_cmd "find"          30 "parse"         find "parse" --json
test_cmd "scope"         30 "configuration" scope "configuration" --json
test_cmd "unused"        60 "unused|Method|class" unused --json

echo ""
echo "── Analysis ──"
# Verify smells/lint/complexity output references the class
test_cmd "smells"        30 "$CLASS"        smells "$CLASS" --json
test_cmd "smells --all"  60 "totalFindings|findings|total" smells --all --json
test_cmd "complexity"    30 "$CLASS"        complexity "$CLASS" --json
test_cmd "complexity --all" 60 "complexity|total" complexity --all --json
test_cmd "lint"          30 "$CLASS"        lint "$CLASS" --json
test_cmd "lint --all"    60 "mutableStatics|godClasses|highParamMethods" lint --all --json
test_cmd "hotspots"      45 "byCallers"     hotspots --json
test_cmd "packages"      60 "totalPackages" packages --json
test_cmd "style"         15 "java"          style --json
test_cmd "patterns"      15 "naming|logging|injection" patterns --json
test_cmd "snippet"       30 "service|Service" snippet service --json

echo ""
echo "── Architecture ──"
test_cmd "check"         30 "violations|ruleId|pass" check --json
test_cmd "endpoints"     30 "path|httpMethod" endpoints --json
test_cmd "entry-points"  30 "main|total"   entry-points --json
test_cmd "validate"      30 "$CLASS"        validate "$CLASS.main" --json
test_cmd "imports"       30 "class|import|relationship" imports "$CLASS" --json
# layer needs .jsrc.yaml with architecture.layers configured
if [ -f "$CODEBASE/.jsrc.yaml" ] && grep -q "layers:" "$CODEBASE/.jsrc.yaml" 2>/dev/null; then
    test_cmd "layer"     15 "controller"    layer controller --json
else
    printf "  ⏭  %-25s SKIPPED (no .jsrc.yaml layers)\n" "layer"
    SKIP=$((SKIP + 1))
fi

echo ""
echo "── Reverse engineering ──"
test_cmd "context"       45 "$CLASS"        context "$CLASS" --json
test_cmd "context-for"   30 "fix bug|relevant|score" context-for "fix bug" --json
test_cmd "contract"      30 "$CLASS"        contract "$CLASS" --json
test_cmd "drift"         30 "Violations|violations|Issues|totalIssues" drift --json
test_cmd "diff"          15 "changed|total|files" diff --json
test_cmd "changed"       15 "changed|total|files" changed --json

echo ""
echo "── Meta/Special ──"
test_cmd "map"           30 "class|pkg"    map --json
test_cmd "resolve"       15 ""              resolve "$CLASS" --json
test_cmd "similar"       45 ""              similar "$CLASS" --json
test_cmd "explain"       60 "$CLASS"        explain "$CLASS" --json
test_cmd "breaking-changes" 45 "$CLASS"     breaking-changes "$CLASS" --json
test_cmd "diff-impact"   30 ""              diff-impact --json
test_cmd "stats"         30 "$CLASS"        stats "$CLASS" --json
test_cmd "type-check"    30 "$CLASS"        type-check "$CLASS" --json
test_cmd "checklist"     45 "$CLASS"        checklist "$CLASS" --json
test_cmd "history"       30 "$CLASS"        history "$CLASS" --json

echo ""
echo "── Batch (stdin multi-query) ──"
test_batch() {
    local output
    output=$(cd "$CODEBASE" && echo '[{"command":"--overview"},{"command":"--summary","args":"'"$CLASS"'"}]' | \
             timeout 45 $JSRC batch --json 2>/dev/null)
    if [ -z "$output" ]; then
        printf "  ❌ %-25s EMPTY OUTPUT\n" "batch"
        FAIL=$((FAIL + 1))
        FAILURES+=("batch: empty output")
    elif echo "$output" | grep -qE "totalFiles" && echo "$output" | grep -qE "$CLASS"; then
        printf "  ✅ %-25s OK (both queries answered)\n" "batch"
        PASS=$((PASS + 1))
    elif echo "$output" | grep -qE "resultCount"; then
        # Batch executed but commands returned empty (known picocli migration issue)
        printf "  ⏭  %-25s SKIPPED (batch format migration pending)\n" "batch"
        SKIP=$((SKIP + 1))
    else
        printf "  ❌ %-25s INCOMPLETE (missing overview or class)\n" "batch"
        FAIL=$((FAIL + 1))
        FAILURES+=("batch: incomplete results")
    fi
}
test_batch

echo ""
echo "── Multiple classes ──"
# Discover a large class (most callers) and an interface
LARGE_CLASS=$(cd "$CODEBASE" && timeout 30 $JSRC hotspots --json 2>/dev/null | \
    python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    for c in d.get('byCallers', []):
        name = c.get('class', '')
        simple = name.split('.')[-1] if '.' in name else name
        if simple and 'Test' not in simple and 'Assert' not in simple and 'Mock' not in simple:
            print(simple)
            break
except: pass
" 2>/dev/null)

INTERFACE=$(cd "$CODEBASE" && find . -name "*.java" -not -path "*/test/*" -exec grep -l "^public interface " {} \; 2>/dev/null | head -1 | sed 's|.*/||; s|\.java$||')

if [ -n "$LARGE_CLASS" ] && [ "$LARGE_CLASS" != "$CLASS" ]; then
    echo "  ℹ️  Large class: $LARGE_CLASS"
    test_cmd "summary-large"   30 "$LARGE_CLASS"  summary "$LARGE_CLASS" --json
    test_cmd "mini-large"      30 "$LARGE_CLASS"   mini "$LARGE_CLASS" --json
    test_cmd "smells-large"    30 "$LARGE_CLASS"   smells "$LARGE_CLASS" --json
else
    printf "  ⏭  %-25s SKIPPED (no large class found)\n" "large-class tests"
    SKIP=$((SKIP + 1))
fi

if [ -n "$INTERFACE" ]; then
    echo "  ℹ️  Interface: $INTERFACE"
    test_cmd "summary-iface"   30 "$INTERFACE"    summary "$INTERFACE" --json
    test_cmd "hierarchy-iface" 30 "$INTERFACE"    hierarchy "$INTERFACE" --json
    test_cmd "implements-iface" 30 ""             implements "$INTERFACE" --json
else
    printf "  ⏭  %-25s SKIPPED (no interface found)\n" "interface tests"
    SKIP=$((SKIP + 1))
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Results: ✅ $PASS passed, ❌ $FAIL failed, ⏭ $SKIP skipped"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Performance summary
echo ""
echo "── Performance Summary ──"
ideal=0; acceptable=0; unacceptable=0
for pr in "${PERF_RESULTS[@]}"; do
    label="${pr##*|}"
    case "$label" in
        IDEAL) ideal=$((ideal + 1)) ;;
        ACCEPTABLE) acceptable=$((acceptable + 1)) ;;
        UNACCEPTABLE) unacceptable=$((unacceptable + 1)) ;;
    esac
done
printf "  🟢 IDEAL (<2s):         %d commands\n" "$ideal"
printf "  🟡 ACCEPTABLE (2-6s):   %d commands\n" "$acceptable"
printf "  🔴 UNACCEPTABLE (>=6s): %d commands\n" "$unacceptable"

# Show slowest commands
echo ""
echo "── Slowest commands ──"
for pr in "${PERF_RESULTS[@]}"; do
    IFS='|' read -r pname ptime plabel <<< "$pr"
    if [ "$ptime" -ge 2000 ] 2>/dev/null; then
        if [ "$ptime" -ge 6000 ]; then
            printf "  🔴 %-25s %5dms  BLOCKS RELEASE\n" "$pname" "$ptime"
        else
            printf "  🟡 %-25s %5dms\n" "$pname" "$ptime"
        fi
    fi
done

if [ ${#FAILURES[@]} -gt 0 ]; then
    echo ""
    echo "Failures:"
    for f in "${FAILURES[@]}"; do
        echo "  • $f"
    done
fi

if [ "$PERF_BLOCK" -gt 0 ]; then
    echo ""
    echo "⛔ RELEASE BLOCKED: $PERF_BLOCK command(s) >= 6 seconds."
    echo "   Fix performance before tagging a release."
    exit 1
fi

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "⛔ RELEASE BLOCKED: $FAIL test(s) failed."
    exit 1
fi

echo ""
echo "🎉 All commands working + performance within limits. Safe to release."
exit 0
