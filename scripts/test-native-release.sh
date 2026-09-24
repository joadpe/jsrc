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

expect_not_contains() {
  local path="$1"
  local text="$2"
  if [[ -f "$project_dir/$path" ]] && grep -Fq -- "$text" "$project_dir/$path"; then
    fail "$path unexpectedly contains: $text"
  fi
}

expect_file "scripts/build-native-unix.sh"
expect_file "scripts/build-native-windows.ps1"
expect_file "scripts/verify-release-version.sh"

release_version="$(cd "$project_dir" && mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
if ! "$project_dir/scripts/verify-release-version.sh" "v$release_version" >/dev/null; then
  fail "release version gate rejected the Maven project version"
fi
if "$project_dir/scripts/verify-release-version.sh" "v0.0.0-invalid" >/dev/null 2>&1; then
  fail "release version gate accepted a mismatched tag"
fi

expect_contains "scripts/build-native-windows.ps1" "/DEF:"
expect_contains "scripts/build-native-windows.ps1" "Functional smoke index failed."
expect_contains "scripts/build-native-windows.ps1" "Negative smoke test unexpectedly succeeded."
expect_contains "scripts/build-native-windows.ps1" "exit 0"
expect_contains "scripts/build-native-windows.ps1" "vswhere.exe"
expect_contains "scripts/build-native-windows.ps1" "ts_wasm_store_new"
expect_contains "scripts/build-native-windows.ps1" "ts_wasm_store_load_language"
expect_contains "scripts/build-native-windows.ps1" "ts_wasm_store_language_count"

# shellcheck disable=SC2016
expect_not_contains "scripts/build-native-unix.sh" 'cp "$smoke_extract/$bundle_name/lib/"* "$smoke_home/lib/"'
# shellcheck disable=SC2016
expect_not_contains "scripts/build-native-windows.ps1" 'Copy-Item (Join-Path $smokeExtract "$bundleName\lib\*.dll")'

for build_script in scripts/build-native-unix.sh scripts/build-native-windows.ps1; do
  expect_contains "$build_script" "a467ea8502d95562171f97953a6dc5b2a8622609"
  expect_contains "$build_script" "94703d5a6bed02b98e438d7cad1136c01a60ba2c"
  expect_contains "$build_script" "index"
  expect_contains "$build_script" "overview"
  expect_contains "$build_script" "read"
done

if grep -Fq -- "ilammy/msvc-dev-cmd" "$project_dir/.github/workflows/release.yml"; then
  fail "release workflow must not depend on mutable external MSVC setup action"
fi

if grep -Fq -- "--branch" "$project_dir/scripts/build-native-unix.sh" "$project_dir/scripts/build-native-windows.ps1"; then
  fail "native builds must use immutable Tree-sitter commits, not tags"
fi

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
expect_contains ".github/workflows/release.yml" "workflow_dispatch:"
expect_contains ".github/workflows/release.yml" "if: startsWith(github.ref, 'refs/tags/v')"
expect_contains ".github/workflows/release.yml" "scripts/verify-release-version.sh"
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
