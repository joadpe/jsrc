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

The dedicated profile measures cold build, unchanged refresh, a body-only
single-file edit, a declaration-adding single-file edit, seeded 1% and 10%
updates, and 1% additions/deletions. Each scenario has
two preparation and seven measured independent CLI runs. The first measured
run compares indexed query answers with a clean rebuild. Warm symbol and
graph queries have five preparation and 50 measured invocations per mode.
The body-only mutation changes the return expression of one existing method
without changing imports, declarations, or line count. It must report one
re-indexed file; inspect `build.edges_reused_files`,
`build.edges_extracted_files`, and `build.resolved_files` to confirm that
unchanged entries were actually reused. The `edit_single` mutation adds a
`revision()` method and intentionally exercises the full-resolution fallback.

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

Use the physical host `jp-hulk` for the repeated 10K campaign. Shared host
load is acceptable for this budget; a quiet or exclusive window is not
required. No dedicated self-hosted runner is referenced by the project
workflows; the GitHub-hosted `ubuntu-latest` worker is not fixed hardware.

Use HULK's `/srv/hulk-data` ext4 filesystem, with
the same pinned Java 22 container image, native libraries, JVM heap settings,
CPU affinity (if used), corpus seed, and runner ID
(`JSRC_PERF_RUNNER_ID=jp-hulk-idx02-10k`). Record these settings with each
report. Use `-Xmx4g` for comparability; it is not an enforced RSS limit.
Run only one benchmark campaign at a time, and record competing CPU/disk load
so a confirmed regression can be diagnosed. Do not reject a sample solely
because other work ran on HULK.

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
reports breaches. Enforced mode rejects missing metrics, nonpositive measurements, invalid
values, failed reports, or mismatched profiles, commands and sampling plans.
A missing RSS or peak-disk sample fails the campaign rather than becoming zero.
The command pins the Java launcher, JVM arguments and JAR path; the product
commit and JAR contents may change while that measurement configuration stays
fixed. A confirmed breach requires a second independent report. Each generated report has a unique `run_id`;
enforced confirmation rejects missing or duplicate IDs and the same report path.

The shared-HULK 10K regression budget is in
`docs/perf/idx02-10k-shared-thresholds.json`; its compact baseline is
`docs/perf/idx02-10k-shared-baseline.json`. The baseline values come from the
September 2026 `r1` campaign and were checked against the independent `r2`
campaign. The factors are 1.20 for index-scenario median wall times, 1.25
for index and warm-query p95 wall times, 1.20 for peak RSS, and 1.10 for
observed peak .jsrc disk bytes. RSS and disk ceilings cover all seven index
scenarios and four warm queries. Disk is sampled periodically, so a
sub-sampling temporary-file spike may escape this ceiling. These margins
exceed the observed r1/r2 spread and allow shared load without treating a
single outlier as a failure. A breach is enforced only when a second
independent same-configuration report confirms it. The runner's recorded
`generator_sha256` is provenance for the entire benchmark script, not a
corpus identity: comparison requires equal generated content hashes and
generation parameters but permits this script hash to change:

```sh
python3 scripts/perf_budget.py compare \
  --baseline docs/perf/idx02-10k-shared-baseline.json \
  --current /absolute/path/to/current-report.json \
  --second-report /absolute/path/to/confirmation-report.json \
  --thresholds docs/perf/idx02-10k-shared-thresholds.json --enforce
```

### Weekly HULK campaign

`scripts/run_10k_budget.sh` fetches `origin/master` into a temporary
worktree, builds its JAR with the pinned Temurin 22 container, and runs the
dedicated 10K campaign on HULK. It keeps the original calibration inputs
unchanged: the new JAR is retained with each run and bind-mounted at the
baseline command's fixed in-container path. The script checks the container,
Java launcher, and archived JAR hashes before running; the product commit and
new JAR hash go into the run's environment log.

The first run is compared with the versioned baseline and all 40 thresholds.
A potential breach triggers a second independent campaign; only a confirmed
breach fails the scheduled service. Correctness or measurement failures fail
immediately. Campaigns serialize with `campaign.lock`, and reports, raw
samples, logs and built artifacts remain under
`/srv/hulk-data/desarrollo/benchmarks/jsrc-c40r-3-2-10k/scheduled/`.
The user-systemd timer runs on Sundays at 03:00 local time (up to 30 minutes
of randomized delay). Missed runs are not caught up at startup. Install the
versioned script and `scripts/systemd/jsrc-10k-budget.{service,timer}` as
`~/.local/bin/jsrc-10k-budget` and
`~/.config/systemd/user/jsrc-10k-budget.{service,timer}`, then enable the
timer with `systemctl --user enable --now jsrc-10k-budget.timer`.
A failed service is visible with `systemctl --user status jsrc-10k-budget.service`.

This is a regression ceiling, not an interactive-latency target. Recalibrate
the unchanged and body-only baselines after two full 10K campaigns on a
newer commit. The 1K GitHub-hosted smoke remains correctness-only because its environment does
not match HULK.

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

## IDX-02b edge reuse at 10K (September 2026)

Two serial dedicated campaigns (`r1` and `r2`) used the same seeded 10K
corpus, Temurin 22.0.2 container, `-Xmx4g`, JAR, runner, and `jp-hulk`
filesystem. Both passed query parity with a clean rebuild, concurrency, and
interrupted-publication recovery. Their independent reports are retained at
`/srv/hulk-data/desarrollo/benchmarks/jsrc-c40r-3-2-10k/idx02b-body-35c721e/`
on HULK; the pinned source commit is `fa8f046`.

| Scenario | r1 median / p95 | r2 median / p95 |
| --- | ---: | ---: |
| Cold build | 71.77 / 75.00 s | 71.33 / 74.34 s |
| Unchanged refresh | 6.63 / 7.07 s | 6.46 / 6.69 s |
| Body-only single-file edit | 7.34 / 7.55 s | 7.28 / 7.32 s |
| Declaration-adding single-file edit | 23.03 / 23.54 s | 22.35 / 22.76 s |
| 1% edit | 23.42 / 24.25 s | 23.33 / 24.52 s |
| 10% edit | 28.75 / 29.71 s | 28.52 / 29.27 s |
| 1% add/delete | 23.46 / 24.60 s | 23.54 / 24.14 s |

Every body-only sample re-indexed and resolved one file, extracted its one
edge set, and reused the other 9,999. Declaration or file-set changes still
intentionally take the full fallback: the single declaration edit resolved all
10,000 files in every sample. In the body-only case, the median residual
call-graph phase was 2.08/2.01 s and publication 2.27/2.19 s (r1/r2), versus
1.04/1.06 s for `index.build`; unchanged refresh still spent 2.18/2.18 s on
the call graph and 2.07/1.97 s on publication. These phases, not edge
extraction, now dominate the incremental path. Publication includes snapshot
verification and writing; the phase spans are nested and must not be added
to the end-to-end wall time.

The two campaigns show low within-run spread and agree closely. Host `sar`
recorded about 94% CPU idle on average across 128 logical CPUs and nonzero
background CPU and disk activity; the container had no CPU affinity or
exclusive host reservation. They now establish the shared-HULK 10K regression
baseline above. A future same-configuration campaign can enforce it despite
shared load, with independent confirmation of a breach. The GitHub-hosted PR
smoke remains correctness-only.

## IDX-02c unchanged-graph reuse (September 2026)

When a refresh changes source text but leaves every indexed class and resolved
call edge unchanged, both `jsrc index` and normal command auto-refresh can
reuse the published call graph. Changed declarations or call edges still take
the full graph build. A binary format/version change invalidates old snapshots;
changes to graph-building semantics must bump that version before reuse.

A one-shot same-corpus comparison on shared HULK used the 10K corpus and
Temurin 22.0.2 with `-Xmx4g`. A single return-expression edit took 7.51 s
with the previous JAR and 5.33 s with graph reuse. The `index.call_graph`
phase fell from 2.126 s to 0.280 s. The modified run re-indexed and resolved
one file, reused 9,999 edge sets and the prior graph, and matched the previous
JAR's selected normal and frozen query answers. A normal `overview --json`
query that auto-refreshed the same single edit took 7.47 s before and 6.08 s
afterward, with identical output. A separate one-shot run with 100
return-expression edits in one batch took 6.57 s: 100 files were
re-indexed/resolved, 9,900 edge sets and the graph were reused. The traces
are under `/srv/hulk-data/desarrollo/benchmarks/jsrc-idx02c-compare/`,
`/srv/hulk-data/desarrollo/benchmarks/jsrc-idx02c-auto/`, and
`/srv/hulk-data/desarrollo/benchmarks/jsrc-idx02c-batch100/` on HULK.

These are diagnostic samples under shared host load, not calibrated medians or
CI thresholds. Publication still took about 2.0 s in the modified samples;
its source verification and binary serialization remain whole-index work.
Edits that add declarations or change call edges do not benefit from graph
reuse. The IDX-02b 10K declaration-edit median remains about 22–23 s until
that fallback is optimized separately.

## IDX-02d no-op publication skip (September 2026)

An explicit `jsrc index` with unchanged entries, a compatible migration cache,
an unchanged call graph, and the same Git tree now verifies sources and build
metadata but keeps the existing published generation. Changes to the Git tree,
source content, or cache format still publish a new generation. Normal query
auto-refresh already avoided publication on an unchanged index.

A one-shot comparison on copies of the same seeded 10K corpus, with Temurin
22.0.2 and `-Xmx4g` on shared HULK, measured `index.total` at 3.098 s before
and 1.736 s after. The old run spent 2.264 s in `index.publish`; the new
run reported `index.publish.skipped=1` and left its manifest unchanged. Both
runs re-indexed zero files and reused the graph and migration cache. Traces
and the two JARs are retained under
`/srv/hulk-data/desarrollo/benchmarks/jsrc-idx02d-noop/`. These are single
phase samples, not calibrated end-to-end medians. The no-op change does not
reduce publication cost for actual edits.

## IDX-02e publication source verification (September 2026)

Publication verifies each accepted source hash once, then checks discovery,
build metadata, and compatibility only for sources that were rejected when the
build started. The compatibility scanner records a hash of the same bytes it
evaluated; publication requires both the index entry and current file to match
that hash. This prevents a source that changes between scan and build from
being published without validation. A rejected source can become compatible
before publication, so it still must be rescanned. The trace count
`source_snapshot.compatibility_rescanned_files` records how many rejected files
required that second check.

Three serial before/after pairs on identical copies of the same indexed 10K
corpus each applied the same body-only edit to one file. The prior JAR was
built from `3b7dc18`; the first updated JAR includes source-scan hash
verification. Both used the same
Java 22 container and `-Xmx4g`. All six refreshes re-indexed one file. Median
CLI wall time was 5.520 s before and 4.916 s after; `index.total` was
3.619 s and 3.052 s. The nested `publish.source_verify` phase fell from
0.757 s to 0.366 s. The binary write/fsync/verify phase remained about
1.2-1.4 s, and no rejected files were rescanned in the final samples. Raw
traces, logs, JARs, and `final-results.json` are under
`/srv/hulk-data/desarrollo/benchmarks/jsrc-idx02e-verify/` on HULK.

After the immutable-source fix, three more serial pairs used copies of the
same indexed 10K corpus, each with a body-only edit to `C00001.java`. The
original `before.jar` was compared against `secure.jar` built from the final
working tree, in the same Java 22 container with `-Xmx4g`. All six runs
re-indexed one file and reused 9,999 edge sets. Median CLI wall time was
5.595 s before and 5.191 s after; `index.total` was 3.577 s and 3.245 s,
and `publish.source_verify` was 0.800 s and 0.364 s. The changed file's
parse/extract phase was 0.040 s and 0.037 s. The result remains diagnostic
on shared HULK; raw data, traces, logs, JARs, and the replay script are in
`secure-results.json` and `run-secure-pairs.py` in the same benchmark directory.

These three-pair measurements are diagnostic, not a recalibration of the
shared-host 10K budget. Full snapshot serialization and its verification remain
the principal publication cost for real edits.
