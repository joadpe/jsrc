#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mvn -q -DskipTests package
java -cp target/jsrc.jar com.jsrc.app.cli.CommandDocumentationRenderer README.md SKILL.md
