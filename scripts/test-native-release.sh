#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

expect_file() {
  local path="$1"
  [[ -f "$project_dir/$path" ]] || fail "missing file: $path"
}

expect_contains() {
  local path="$1"
  local text="$2"
  if [[ ! -f "$project_dir/$path" ]] || ! grep -Fq -- "$text" "$project_dir/$path"; then
    fail "$path does not contain: $text"
  fi
}

expect_file "scripts/build-native-unix.sh"
expect_file "scripts/build-native-windows.ps1"

expect_contains "scripts/build-native-windows.ps1" "/DEF:"
expect_contains "scripts/build-native-windows.ps1" "Positive smoke test failed."
expect_contains "scripts/build-native-windows.ps1" "Negative smoke test unexpectedly succeeded."

if grep -Fq -- "Require-Command cmake" "$project_dir/scripts/build-native-windows.ps1"; then
  fail "Windows build must not require unavailable Tree-sitter CMake configuration"
fi

if grep -Fq -- "--no-fallback" "$project_dir/scripts/build-native-unix.sh" "$project_dir/scripts/build-native-windows.ps1"; then
  fail "native build scripts still use deprecated --no-fallback"
fi

if [[ -x "$project_dir/scripts/build-native-unix.sh" ]]; then
  help_output="$("$project_dir/scripts/build-native-unix.sh" --help)"
  [[ "$help_output" == *"linux-x64"* ]] || fail "Unix help omits linux-x64"
  [[ "$help_output" == *"macos-arm64"* ]] || fail "Unix help omits macos-arm64"
  [[ "$help_output" == *"macos-x64"* ]] || fail "Unix help omits macos-x64"

  set +e
  invalid_output="$("$project_dir/scripts/build-native-unix.sh" invalid-target 2>&1)"
  invalid_status=$?
  set -e
  [[ $invalid_status -eq 2 ]] || fail "invalid Unix target must exit 2, got $invalid_status"
  [[ "$invalid_output" == *"unsupported target"* ]] || fail "invalid Unix target lacks diagnostic"
fi

expect_contains ".github/workflows/release.yml" "jsrc-linux-x64.tar.gz"
expect_contains ".github/workflows/release.yml" "jsrc-macos-arm64.tar.gz"
expect_contains ".github/workflows/release.yml" "jsrc-macos-x64.tar.gz"
expect_contains ".github/workflows/release.yml" "jsrc-windows-x64.zip"
expect_contains ".github/workflows/release.yml" "macos-15"
expect_contains ".github/workflows/release.yml" "macos-15-intel"
expect_contains ".github/workflows/release.yml" "windows-latest"
expect_contains ".github/workflows/release.yml" "checksums.txt"
expect_contains ".github/workflows/release.yml" "zlib1g-dev"
expect_contains ".github/workflows/release.yml" "if: matrix.target == 'linux-x64'"
expect_contains "README.md" "zlib1g-dev"

expect_contains "README.md" "### Linux x64"
expect_contains "README.md" "### macOS"
expect_contains "README.md" "### Windows x64"
expect_contains "README.md" "## Build native binaries from source"
expect_contains "README.md" "scripts/build-native-windows.ps1"
expect_contains "README.md" "scripts/build-native-unix.sh"

expect_contains "install.sh" "jsrc-linux-x64.tar.gz"
expect_contains "install.sh" "jsrc-macos-arm64.tar.gz"
expect_contains "install.sh" "describe --json"

if grep -Fq -- "--describe" "$project_dir/install.sh"; then
  fail "install.sh still uses deprecated --describe syntax"
fi

if grep -Fq -- "jsrc --index" "$project_dir/install.sh"; then
  fail "install.sh still uses deprecated --index syntax"
fi

if grep -Fq -- "jsrc --overview" "$project_dir/install.sh"; then
  fail "install.sh still uses deprecated --overview syntax"
fi

if LC_ALL=C grep -q $'\x13' "$project_dir/install.sh"; then
  fail "install.sh contains an unexpected control character"
fi

if (( failures > 0 )); then
  echo "$failures contract check(s) failed." >&2
  exit 1
fi

echo "Native release contract checks passed."
