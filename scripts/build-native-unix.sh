#!/usr/bin/env bash
set -euo pipefail

TREE_SITTER_VERSION="${TREE_SITTER_VERSION:-v0.25.9}"
TREE_SITTER_JAVA_VERSION="${TREE_SITTER_JAVA_VERSION:-v0.23.5}"

usage() {
  cat <<'EOF'
Usage: scripts/build-native-unix.sh <linux-x64|macos-arm64|macos-x64>

Builds, smoke-tests, and packages a native jsrc bundle.
Environment:
  JSRC_JAR                  Fat JAR path (default: target/jsrc.jar)
  TREE_SITTER_VERSION       tree-sitter tag (default: v0.25.9)
  TREE_SITTER_JAVA_VERSION  tree-sitter-java tag (default: v0.23.5)
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

target="${1:-}"
case "$target" in
  linux-x64)
    expected_os="Linux"
    expected_arch="x86_64"
    ;;
  macos-arm64)
    expected_os="Darwin"
    expected_arch="arm64"
    ;;
  macos-x64)
    expected_os="Darwin"
    expected_arch="x86_64"
    ;;
  *)
    echo "Error: unsupported target '$target'." >&2
    usage >&2
    exit 2
    ;;
esac

actual_os="$(uname -s)"
actual_arch="$(uname -m)"
if [[ "$actual_os" != "$expected_os" || "$actual_arch" != "$expected_arch" ]]; then
  echo "Error: target $target requires $expected_os/$expected_arch, found $actual_os/$actual_arch." >&2
  exit 2
fi

for required_command in git cc native-image tar; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Error: required command not found: $required_command" >&2
    exit 1
  fi
done

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_path="${JSRC_JAR:-$project_dir/target/jsrc.jar}"
dist_dir="$project_dir/dist"
bundle_name="jsrc-$target"
bundle_dir="$dist_dir/$bundle_name"
native_lib_dir="$bundle_dir/lib"
archive_path="$dist_dir/$bundle_name.tar.gz"

if [[ ! -f "$jar_path" ]]; then
  echo "Error: JAR not found: $jar_path" >&2
  echo "Build it first with: mvn -B -DskipTests package" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
smoke_home="$(mktemp -d)"
cleanup() {
  rm -rf "$work_dir" "$smoke_home"
}
trap cleanup EXIT

git clone --depth 1 --branch "$TREE_SITTER_VERSION" https://github.com/tree-sitter/tree-sitter.git "$work_dir/tree-sitter"
git clone --depth 1 --branch "$TREE_SITTER_JAVA_VERSION" https://github.com/tree-sitter/tree-sitter-java.git "$work_dir/tree-sitter-java"

rm -rf "$bundle_dir"
rm -f "$archive_path"
mkdir -p "$native_lib_dir"

if [[ "$actual_os" == "Linux" ]]; then
  cc -shared -fPIC -I"$work_dir/tree-sitter/lib/include" -I"$work_dir/tree-sitter/lib/src" "$work_dir/tree-sitter/lib/src/lib.c" -Wl,-soname,libtree-sitter.so -o "$native_lib_dir/libtree-sitter.so"
  cc -shared -fPIC -I"$work_dir/tree-sitter-java/src" "$work_dir/tree-sitter-java/src/parser.c" -o "$native_lib_dir/libtree-sitter-java.so"
else
  cc -dynamiclib -fPIC -I"$work_dir/tree-sitter/lib/include" -I"$work_dir/tree-sitter/lib/src" "$work_dir/tree-sitter/lib/src/lib.c" -Wl,-install_name,@rpath/libtree-sitter.dylib -o "$native_lib_dir/libtree-sitter.dylib"
  cc -dynamiclib -fPIC -I"$work_dir/tree-sitter-java/src" "$work_dir/tree-sitter-java/src/parser.c" -Wl,-install_name,@rpath/libtree-sitter-java.dylib -o "$native_lib_dir/libtree-sitter-java.dylib"
fi

native-image --enable-native-access=ALL-UNNAMED "-Djava.library.path=$native_lib_dir" -jar "$jar_path" -o "$bundle_dir/jsrc" -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport

mkdir -p "$smoke_home/lib"
cp "$native_lib_dir"/* "$smoke_home/lib/"

HOME="$smoke_home" "$bundle_dir/jsrc" describe --json >/dev/null
if HOME="$smoke_home" "$bundle_dir/jsrc" definitely-not-a-command >/dev/null 2>&1; then
  echo "Error: invalid command unexpectedly succeeded." >&2
  exit 1
fi

tar -C "$dist_dir" -czf "$archive_path" "$bundle_name"
echo "Created $archive_path"
