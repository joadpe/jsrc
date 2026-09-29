# Index performance budget (IDX-02)

`docs/BENCHMARK.md` contains historical March 2026 measurements from the
pre-IDX-01 index. They are not an IDX-02 baseline or CI thresholds.

## Workloads

`scripts/perf_budget.py` generates an offline, seeded Maven corpus with 1K,
5K, or 10K Java 17 files. Its manifest records the seed, generator hash,
source level, file count, package topology, and content digest. The report
records the generated source distributions and, when supplied, a pinned real
project's source distributions and Git commit. The source profiler is a
lexical approximation; it does not count resolved calls or types.

The PR smoke measures one cold build, unchanged refresh, single-file edit,
normal and frozen symbol lookup, and normal and frozen graph lookup on 1K
files. It requires correct re-index counts, phase traces, and consistent
query answers. Its timings are **report-only**; correctness failures fail CI.

The dedicated profile measures cold build, unchanged refresh, single-file
edit, seeded 1% and 10% edits, and 1% additions/deletions. Each scenario has
two preparation and seven measured independent CLI runs. The first measured
run compares indexed query answers with a clean rebuild. Warm symbol and
graph queries have five preparation and 50 measured invocations per mode.
The dedicated run also exercises one writer with four frozen-index readers,
then two competing writers, and interrupted-publication recovery. On Linux,
the runner observes the writer's actual OS lock and pauses that process with
`SIGSTOP` while readers complete. It holds the same lock until both competing
JVMs are observed in the product's lock-wait path (`/proc/locks` or `jcmd`
thread dumps), then releases it. Each reader answer must equal a complete
pre- or post-publication graph generation from a **clean** rebuild. A final
refresh must report zero changed files. A separate writer is killed with
`SIGKILL` while holding the publication lock; frozen queries must still read
a complete generation and a recovery refresh must match a clean rebuild.
These fault-injection samples establish correctness, not representative
latency: the writer is deliberately paused. They require Linux, `jcmd`, and
the JVM CLI rather than a native image.

Samples and phase traces are retained in the JSON report. Index phases are
nanosecond spans, while whole-process wall time and peak RSS include JVM
startup and native loading. Some spans are nested; do not sum inclusive
parent and child durations.

The runner records environment, JDK, CPU, governor, filesystem, commit,
corpus digest, median and p95 latency, peak RSS, index bytes, and observed
peak `.jsrc` disk use. Disk sampling is periodic, so very short-lived
temporary files may fall between samples. Run only one performance campaign
at a time on the same pinned machine and filesystem; do not compare
report timings across different environments as a hard gate.

## Fixed 10K calibration runner

Use the physical host `jp-hulk` in a reserved quiet window for the repeated
10K campaign. No dedicated self-hosted runner is referenced by the project
workflows; the GitHub-hosted `ubuntu-latest` worker is not fixed hardware. HULK's normal
shared-load measurements, including the pilot below, remain report-only.

Run one campaign at a time on HULK's `/srv/hulk-data` ext4 filesystem, with
the same pinned Java 22 container image, native libraries, JVM heap settings,
CPU affinity (if used), corpus seed, and runner ID
(`JSRC_PERF_RUNNER_ID=jp-hulk-idx02-10k`). Record these settings with each
report. Start with `-Xmx4g` as the heap candidate from the successful pilot;
do not treat that single run as a calibrated memory limit. If competing CPU or
disk work cannot be excluded, discard timing samples and keep the gate
report-only. Enable numeric enforcement only after repeated quiet-window
campaigns establish a same-configuration baseline and its variance.

## Reproduce

First build `target/jsrc.jar` with Java 22 and the Tree-sitter native
libraries available. The runtime must be able to load those libraries (for
example via `LD_LIBRARY_PATH`). Use absolute paths for all inputs and outputs.

```sh
python3 scripts/perf_budget.py run \
  --root /absolute/path/to/disposable-corpus \
  --files 10000 --profile dedicated --seed 17 \
  --jar /absolute/path/to/jsrc.jar \
  --real-project /absolute/path/to/pinned/real-project \
  --output /absolute/path/to/report.json
```

The runner replaces `--root` between independent scenarios and refuses to
replace a directory without its corpus manifest. Keep the report outside that
directory. Run `python3 scripts/perf_budget.py profile --root PATH` to inspect
a real project's distributions without indexing it. The report's `samples`
contain the raw timing and resource observations.

`compare` accepts a baseline report, current report, and JSON map from
summary metric names to multiplicative limits. Without `--enforce`, it only
reports breaches. Enforced mode rejects missing metrics, invalid values,
failed reports, or mismatched profiles and requires a second independent
report to confirm each breach. Each generated report has a unique `run_id`;
enforced confirmation rejects missing or duplicate IDs and the same report path.
Calibrate limits from repeated runs on a dedicated,
fixed runner before enabling enforcement; no numeric budget is established
by the one-shot PR smoke or historical benchmark.

## Real-project contrast

Spring Boot v3.5.0 (`8c2d6453243f319accaef7a190ff8ddf89f482a2`) was
used as a pinned contrast project. In the 1K smoke report, synthetic
`src/main/java` source files had LOC p50/p95 of 27/134 and imports 4/8;
Spring Boot's 4,517 main-source files had 47/214 LOC and 5/23 imports.
The synthetic corpus exercises inter-file calls but has a shorter size and
import tail than Spring Boot. Its timing is a reproducible regression signal,
not a substitute for real-project validation. A one-shot 1K smoke on Temurin
22 passed; it is not a calibrated 10K baseline.

In one-shot phase traces on that Spring Boot checkout (6,651 indexed Java
files across included source sets), cold index time was 66.1 s and unchanged
refresh 22.3 s. Cold work included 18.8 s of parse/extraction and 17.3 s of
source-compatibility scanning; unchanged refresh included 13.1 s of migration
processing. The 1K synthetic smoke showed the same leading phase categories
(parse/extraction and compatibility for cold; migrations for refresh), but
these single samples cannot establish relative scaling or a budget.

An uncalibrated 10K pilot on HULK indexed all 10,000 files in 65.2 s with
9.79 GiB peak RSS; unchanged refresh took 16.7 s with 0 re-indexed files and
2.24 GiB peak RSS. A separate cold run using `java -Xmx4g` completed in
67.9 s with 2.66 GiB peak RSS. These are single runs, not an enforced memory
limit or same-runner baseline; heap configuration must be pinned and recorded
for dedicated comparisons.

## IDX-02a no-op refresh check

The dedicated 10K run-2 baseline on shared HULK measured an unchanged-refresh
median of 15.78 s, with about 9.13 s in `index.migrations`. After caching
migration suggestions in the published snapshot, a single no-op run on the
same seeded 10K corpus copy re-indexed 0 files, reused the migration cache,
and measured 0.049 ms in `index.migrations` and 6.09 s wall time. The first
refresh of an older snapshot intentionally recomputed suggestions (9.29 s in
`index.migrations`, 15.70 s wall) to establish the cache revision. The raw
post-change traces and wall samples are retained on HULK under
`/srv/hulk-data/desarrollo/benchmarks/jsrc-c40r-3-2-10k/idx02a-check/`.

These are single, shared-host samples, not a calibrated before/after latency
gate. The phase trace establishes that the repeated migration scan is avoided;
source hashing, call-graph construction, snapshot verification, and
publication still run. A single-file edit also triggers global edge refresh
and resolution work; the previous dedicated median was 30.25 s. That cost is
separate from the no-op migration-cache improvement.
