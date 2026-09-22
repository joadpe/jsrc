#!/usr/bin/env bash
set -euo pipefail

TREE_SITTER_COMMIT="${TREE_SITTER_COMMIT:-a467ea8502d95562171f97953a6dc5b2a8622609}"
TREE_SITTER_JAVA_COMMIT="${TREE_SITTER_JAVA_COMMIT:-94703d5a6bed02b98e438d7cad1136c01a60ba2c}"

usage() {
  cat <<'EOF'
Usage: scripts/build-native-unix.sh <linux-x64|macos-arm64|macos-x64>

Builds, smoke-tests, and packages a native jsrc bundle.
Environment:
  JSRC_JAR                Fat JAR path (default: target/jsrc.jar)
  TREE_SITTER_COMMIT      Immutable tree-sitter commit
  TREE_SITTER_JAVA_COMMIT Immutable tree-sitter-java commit
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
library_moved=false
cleanup() {
  if [[ "$library_moved" == true && -d "$work_dir/build-libs" && ! -d "$native_lib_dir" ]]; then
    mv "$work_dir/build-libs" "$native_lib_dir"
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

checkout_repository() {
  local repository_url="$1"
  local commit="$2"
  local destination="$3"

  git init -q "$destination"
  git -C "$destination" remote add origin "$repository_url"
  git -C "$destination" fetch -q --depth 1 origin "$commit"
  git -C "$destination" checkout -q --detach FETCH_HEAD

  local actual_commit
  actual_commit="$(git -C "$destination" rev-parse HEAD)"
  if [[ "$actual_commit" != "$commit" ]]; then
    echo "Error: expected $commit from $repository_url, got $actual_commit." >&2
    exit 1
  fi
}

checkout_repository https://github.com/tree-sitter/tree-sitter.git "$TREE_SITTER_COMMIT" "$work_dir/tree-sitter"
checkout_repository https://github.com/tree-sitter/tree-sitter-java.git "$TREE_SITTER_JAVA_COMMIT" "$work_dir/tree-sitter-java"

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

tar -C "$dist_dir" -czf "$archive_path" "$bundle_name"

smoke_extract="$work_dir/extracted"
smoke_home="$work_dir/home"
smoke_project="$work_dir/project"
mkdir -p "$smoke_extract" "$smoke_home/lib" "$smoke_project/src/main/java/example"
tar -xzf "$archive_path" -C "$smoke_extract"
cp "$smoke_extract/$bundle_name/lib/"* "$smoke_home/lib/"

cat > "$smoke_project/src/main/java/example/Hello.java" <<'JAVA'
package example;

public final class Hello {
    public String message() {
        return "hello";
    }
}
JAVA

mv "$native_lib_dir" "$work_dir/build-libs"
library_moved=true
smoke_binary="$smoke_extract/$bundle_name/jsrc"

HOME="$smoke_home" "$smoke_binary" -d "$smoke_project" index >/dev/null
overview_output="$(HOME="$smoke_home" "$smoke_binary" -d "$smoke_project" overview --json)"
grep -Fq '"totalFiles"' <<<"$overview_output"
read_output="$(HOME="$smoke_home" "$smoke_binary" -d "$smoke_project" read Hello --json)"
grep -Fq 'Hello' <<<"$read_output"

if HOME="$smoke_home" "$smoke_binary" definitely-not-a-command >/dev/null 2>&1; then
  echo "Error: invalid command unexpectedly succeeded." >&2
  exit 1
fi

mv "$work_dir/build-libs" "$native_lib_dir"
library_moved=false

echo "Created $archive_path"
