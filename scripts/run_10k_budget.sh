#!/usr/bin/env bash
# Weekly IDX-02 regression campaign on the pinned HULK runner.
set -euo pipefail

repo=/srv/hulk-data/projects/jsrc
bench_root=/srv/hulk-data/desarrollo/benchmarks/jsrc-c40r-3-2-10k
worktree_root=/srv/hulk-data/desarrollo/worktrees/jsrc
inputs="$bench_root/idx02b-body-35c721e/inputs"
real_project=/srv/hulk-data/projects/spring-boot
image=localhost/jsrc-perf-idx02:temurin22-20260928
expected_image=ae13731dfbf4f6654278e8fdfa939dfaac9a78435625790dc9e296b8ff40b6ec
expected_launcher=b63846d9e57c569c349b58e687b162fe7bde737e78a4971182d7db0bcd811a91
expected_archived_jar=d46dd21760d7fff8511fa6330ed9aecc92f0513c84dc6e4d61ce44ef4002d233

exec 9>"$bench_root/campaign.lock"
flock 9

if [[ "$(podman image inspect "$image" --format '{{.Id}}' | sed 's/^sha256://')" != "$expected_image" ]]; then
  echo "Pinned container image changed." >&2
  exit 2
fi
if [[ "$(sha256sum "$inputs/java" | cut -d' ' -f1)" != "$expected_launcher" ]]; then
  echo "Pinned Java launcher changed." >&2
  exit 2
fi
if [[ "$(sha256sum "$inputs/jsrc.jar" | cut -d' ' -f1)" != "$expected_archived_jar" ]]; then
  echo "Archived baseline JAR changed." >&2
  exit 2
fi

GIT_TERMINAL_PROMPT=0 git -C "$repo" fetch --quiet https://github.com/joadpe/jsrc.git master
revision=$(git -C "$repo" rev-parse FETCH_HEAD)
stamp=$(date +%Y%m%dT%H%M%S)
worktree="$worktree_root/idx02-budget-$stamp-$$"
run_dir="$bench_root/scheduled/$stamp-${revision:0:12}-$$"
mkdir -p "$bench_root/scheduled"
mkdir "$run_dir"
git -C "$repo" worktree add --quiet --detach "$worktree" "$revision"
cleanup() {
  git -C "$repo" worktree remove --force "$worktree" || true
}
trap cleanup EXIT

{
  date -Is
  printf 'commit=%s\n' "$revision"
  uptime
  podman image inspect "$image" --format '{{.Id}}'
  sha256sum "$inputs/java" "$inputs/jsrc.jar"
} >"$run_dir/environment.log"

podman run --rm --network none \
  -v "$worktree:$worktree" \
  -v "$repo/.git:$repo/.git:ro" \
  -v /home/joaquin/.m2:/root/.m2 \
  -w "$worktree" "$image" mvn -o -q -DskipTests package \
  >"$run_dir/build.log" 2>&1
cp "$worktree/target/jsrc.jar" "$run_dir/jsrc.jar"
cp "$worktree/scripts/perf_budget.py" "$run_dir/perf_budget.py"
sha256sum "$run_dir/jsrc.jar" "$run_dir/perf_budget.py" >>"$run_dir/environment.log"

baseline="$worktree/docs/perf/idx02-10k-shared-baseline.json"
thresholds="$worktree/docs/perf/idx02-10k-shared-thresholds.json"
runner="$run_dir/perf_budget.py"
run_one() {
  local number="$1"
  local data="$run_dir/r$number"
  mkdir -p "$data"
  podman run --rm --network none --hostname jp-hulk \
    -e JSRC_PERF_RUNNER_ID=jp-hulk-idx02-10k \
    -v "$bench_root:$bench_root" \
    -v "$run_dir/jsrc.jar:$inputs/jsrc.jar:ro" \
    -v "$worktree:$worktree:ro" \
    -v "$repo/.git:$repo/.git:ro" \
    -v "$real_project:$real_project:ro" \
    -w "$worktree" "$image" \
    python3 "$runner" run \
      --root "$data/corpus-10k" \
      --files 10000 --profile dedicated --seed 17 \
      --java "$inputs/java" --jar "$inputs/jsrc.jar" \
      --real-project "$real_project" --output "$data/report.json" \
    >"$data/run.log" 2>&1
}

run_one 1
python3 "$runner" compare --baseline "$baseline" \
  --current "$run_dir/r1/report.json" --thresholds "$thresholds" \
  >"$run_dir/first-comparison.json"
if python3 -c 'import json,sys; sys.exit(bool(json.load(open(sys.argv[1]))["breaches"]))' \
    "$run_dir/first-comparison.json"; then
  python3 "$runner" compare --baseline "$baseline" \
    --current "$run_dir/r1/report.json" --thresholds "$thresholds" --enforce \
    >"$run_dir/final-comparison.json"
else
  run_one 2
  python3 "$runner" compare --baseline "$baseline" \
    --current "$run_dir/r1/report.json" --second-report "$run_dir/r2/report.json" \
    --thresholds "$thresholds" --enforce \
    >"$run_dir/final-comparison.json"
fi
date -Is >>"$run_dir/environment.log"
cat "$run_dir/final-comparison.json"
