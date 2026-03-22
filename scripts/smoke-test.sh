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
FAILURES=()

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
    output=$(cd "$CODEBASE" && timeout "$timeout_secs" $JSRC "$@" 2>/dev/null) || exit_code=$?

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

    if [ -n "$pattern" ] && ! echo "$output" | grep -q "$pattern"; then
        printf "  ❌ %-25s MISSING PATTERN: %s\n" "$name" "$pattern"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: missing '$pattern'")
        return
    fi

    local size=${#output}
    printf "  ✅ %-25s OK (%d bytes)\n" "$name" "$size"
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
    output=$(cd "$CODEBASE" && timeout 15 $JSRC "$@" 2>&1) || true
    if [ -n "$output" ] && echo "$output" | grep -qi "$pattern"; then
        printf "  ✅ %-25s OK (error message found)\n" "$name"
        PASS=$((PASS + 1))
    else
        printf "  ❌ %-25s MISSING ERROR: %s\n" "$name" "$pattern"
        FAIL=$((FAIL + 1))
        FAILURES+=("$name: missing error '$pattern'")
    fi
}
test_error "class-not-found"  "not found\|Did you mean\|error" summary NonExistentClassName123 --json

echo ""
echo "── Flag inheritance (critical) ──"
test_flag_position "--json" "overview"
test_flag_position "--full" "overview"

echo ""
echo "── Navigation ──"
test_cmd "overview"      15 "totalFiles"    overview --json
test_cmd "classes"       30 "classes"       classes --json

# Discover a class for testing
CLASS=$(find_class)
if [ -z "$CLASS" ]; then
    echo "  ⚠️  Could not find a class name. Some tests will be skipped."
    CLASS="App"
fi
echo "  ℹ️  Using class: $CLASS"

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
test_cmd "unused"        60 "unused\|Method\|class" unused --json

echo ""
echo "── Analysis ──"
# Verify smells/lint/complexity output references the class
test_cmd "smells"        30 "$CLASS"        smells "$CLASS" --json
test_cmd "smells --all"  60 "totalFindings\|findings\|total" smells --all --json
test_cmd "complexity"    30 "$CLASS"        complexity "$CLASS" --json
test_cmd "complexity --all" 60 "complexity\|total" complexity --all --json
test_cmd "lint"          30 "$CLASS"        lint "$CLASS" --json
test_cmd "lint --all"    60 "mutableStatics\|godClasses\|highParamMethods" lint --all --json
test_cmd "hotspots"      45 "byCallers"     hotspots --json
test_cmd "packages"      60 "totalPackages" packages --json
test_cmd "style"         15 "java"          style --json
test_cmd "patterns"      15 "naming\|logging\|injection" patterns --json
test_cmd "snippet"       30 "service\|Service" snippet service --json

echo ""
echo "── Architecture ──"
test_cmd "check"         30 "violations\|ruleId\|pass" check --json
test_cmd "endpoints"     30 "path\|httpMethod" endpoints --json
test_cmd "entry-points"  30 "main\|total"   entry-points --json
test_cmd "validate"      30 "$CLASS"        validate "$CLASS.main" --json
test_cmd "imports"       30 "class\|import\|relationship" imports "$CLASS" --json
# layer needs .jsrc.yaml with architecture.layers configured
if cd "$CODEBASE" && [ -f ".jsrc.yaml" ] && grep -q "layers:" .jsrc.yaml 2>/dev/null; then
    test_cmd "layer"     15 "controller"    layer controller --json
else
    printf "  ⏭  %-25s SKIPPED (no .jsrc.yaml layers)\n" "layer"
    SKIP=$((SKIP + 1))
fi

echo ""
echo "── Reverse engineering ──"
test_cmd "context"       45 "$CLASS"        context "$CLASS" --json
test_cmd "context-for"   30 "fix bug\|relevant\|score" context-for "fix bug" --json
test_cmd "contract"      30 "$CLASS"        contract "$CLASS" --json
test_cmd "drift"         30 "Violations\|violations\|Issues\|totalIssues" drift --json
test_cmd "diff"          15 "changed\|total\|files" diff --json
test_cmd "changed"       15 "changed\|total\|files" changed --json

echo ""
echo "── Meta/Special ──"
test_cmd "map"           30 "class\|pkg"    map --json
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
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Results: ✅ $PASS passed, ❌ $FAIL failed, ⏭ $SKIP skipped"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ ${#FAILURES[@]} -gt 0 ]; then
    echo ""
    echo "Failures:"
    for f in "${FAILURES[@]}"; do
        echo "  • $f"
    done
    exit 1
fi

echo ""
echo "🎉 All commands working. Safe to release."
exit 0
