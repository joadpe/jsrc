# Incremental index optimization design (10K)

Status: phase 1 merged; strict phase-2 edge reuse implemented on `feature/c40r-3-2-incremental-edges`. Broader declaration-change reuse and phase 3 remain design-only. No new dedicated 10K campaign.
Target: Java 22 (`pom.xml`), branch `feature/c40r-3-2-performance-budget`, baseline commit `7caedb1`.

## Implementation status

The first patch computes migrations from newly built `IndexEntry` paths, reuses suggestions only for unchanged paths in a compatible published snapshot, and scans changed/new paths once. Deleted paths disappear from the new map. Cold builds and refreshes now use source-relative path keys; `CachedMigration.ALGORITHM_VERSION` is 2, so version-1 snapshots rescan. Phase traces report `index.migrations.reused_paths` and `index.migrations.scanned_paths`. Java 22 source declarations are accepted for Java 21-compatible syntax; unparseable newer syntax remains quarantined until JavaParser exposes a matching grammar. This also lets jsrc index its own Java-22 checkout.

Focused tests cover edited, added, deleted and multi-class files, old-cache fallback, clean-rebuild parity, frozen readers and a Java-22 Maven project. The full Java-22 Maven suite passed (1,098 tests, zero failures/errors, one skipped). The own-checkout index completed with 455 Java files and a frozen `read IndexCommand.execute` succeeded. No new 10K timing or numeric threshold is claimed here. The strict edge-reuse guard is now implemented on the phase-2 branch. For a changed body, it preserves unchanged published entries only when the ordered path inventory, complete indexed class metadata, source set/level, canonical edge schema, and effective no-invoker context remain equal. It rejects parser diagnostics and previous reflective edges. The fingerprint hashes the declaration tree from the same parse already used for edge extraction after removing executable method, constructor, and initializer bodies; local and anonymous type declarations inside those bodies are included separately. This catches class modifiers and kind that `IndexedClass` does not store. Binary index schema version 10 persists the fingerprint and invalidates version-9 snapshots; legacy JSON entries without it take the full path on a changed refresh. Marker and symbol resolution use the complete class universe but mutate only changed paths. Any declaration, order, file-set, or invoker uncertainty takes the existing full extraction/resolution path. The conservative metadata equality also falls back for some body-only line-count changes.

The Java-22 Maven suite passed 1,109 tests (zero failures/errors, one skipped). Focused tests compare the incremental edge lists with a clean rebuild and cover a persisted snapshot, body-only reuse, added methods, changed visibility, class modifiers and kind, anonymous declarations, reordered colliding simple names, legacy fingerprints, and old reflective edges. A disposable three-file trace reported 2 reused/1 extracted/1 resolved file for a body-only edit; adding a method reported 0 reused/3 extracted/3 resolved files with `build.full_fallback_reason.declarations`. These are correctness/work counters, not 10K latency claims. Residual-cost work below remains unimplemented.

## Evidence and scope

Two independent `dedicated` 10K runs on HULK used the same pinned Java 22 container, JAR, corpus seed, and filesystem. Both passed correctness, concurrent publication, and interrupted-writer recovery. The `edit_single` mutation adds a `revision()` method containing a call to `C00020.value()` in exactly one source file; the index reports one re-indexed file. The CLI wall-time median was 30.84 s and 30.97 s. The median was 6.63/6.74 s for an unchanged refresh, 32.28/32.32 s for a 1% edit, and 78.62/78.63 s for a cold build. The similar one-file and 1% timings indicate a large corpus-wide fixed cost, not a slow parse of the edited file.

| `edit_single` trace, median per run | Run 1 | Run 2 | Source |
| --- | ---: | ---: | --- |
| `index.migrations` | 9.92 s | 10.08 s | `IndexCommand.execute` -> `MigrateCommand.computeAllForIndex` |
| `build.refresh_unchanged_edges` | 5.66 s | 5.86 s | `CodebaseIndex.build` -> `EdgeResolver.extract` |
| `build.resolve` | 8.66 s | 8.67 s | `CodebaseIndex.build` -> `EdgeResolver.resolveMarkers/resolveSymbols` |
| `build.parse_extract` | 0.033 s | 0.033 s | `CodebaseIndex.build` |
| `index.call_graph` | 1.79 s | 1.79 s | `CallGraphBuilder.loadFromIndex` |
| `index.publish` | 2.10 s | 2.12 s | `IndexSnapshotStore.publishLocked` |

Phase spans can be nested; do not add parent and child durations. Reports: `/srv/hulk-data/desarrollo/benchmarks/jsrc-c40r-3-2-10k/idx02a-calibration-jcmd-r{1,2}/dedicated-report.json`. The two campaign logs span about 2 h 14 min each, 4 h 28 min together. No new campaign is needed to identify these priorities.

## Current path and cause

1. `IndexCommand.execute` (`src/main/java/com/jsrc/app/command/meta/IndexCommand.java:14`) loads a published snapshot, calls `CodebaseIndex.build`, reconstructs the complete call graph, recomputes *all* migration suggestions whenever `reindexed > 0`, and publishes a new generation even for an unchanged refresh.
2. `CodebaseIndex.build` (`src/main/java/com/jsrc/app/index/CodebaseIndex.java:89`) hashes all files. Any changed file makes `semanticRefreshRequired` true; it extracts raw edges again from every unchanged file and resolves markers and symbols for every entry. With no configured invokers, a no-op bypasses those two global steps, explaining most of the observed delta. With invokers, the current condition still triggers a global refresh even on a no-op.
3. `MigrateCommand.computeAllForIndex` (`src/main/java/com/jsrc/app/command/quality/MigrateCommand.java:204`) iterates every class and reloads its source. `scanSource` uses the source lines and fixed target version 17; its suggestions are local to a source file. `IndexCommand` currently reuses the migration cache only for zero re-indexed files.
4. `CallGraphBuilder.loadFromIndex` (`src/main/java/com/jsrc/app/analysis/CallGraphBuilder.java:114`) reconstructs all methods and edges. `IndexSnapshotStore.publishLocked` (`src/main/java/com/jsrc/app/index/IndexSnapshotStore.java:229`) writes/fsyncs/reads the binary and verifies sources while holding the publication lock. These are secondary costs and also correctness boundaries.

## Proposed implementation, in dependency order

### 1. Recompute migration suggestions only for changed paths

Pass the previous migration map, changed/deleted paths, and cache algorithm version into a per-path migration update. Copy entries for unchanged paths, drop deleted paths, rescan changed paths, and publish the merged map. A missing/old cache version, changed migration algorithm/target version, invalid prior snapshot, or uncertain path mapping forces a full recomputation **from the newly built entries**, not from the pre-build `CommandContext`. `CommandContext.getAllClasses()` prefers `ctx.indexed()`, which can describe the previous snapshot and omit a newly added class. Use the same source loading and `scanSource` behavior in both paths. Currently `computeAllForIndex` calls `result.put(path, compact)` for each class, so the last result wins for a multi-class file; `scanSource` does not use its `ClassInfo` argument. Preserve that observable map and ordering for parity, or fix it explicitly with separate tests and an algorithm-version bump. Assemble the output in the newly built entry order, rather than appending changed keys to a copy of the old map; if old keys cannot be mapped unambiguously to source paths, recompute fully. Test added/deleted classes and multiple classes per file. Keep the current no-op reuse path.

Touch: `IndexCommand`, `MigrateCommand`, and migration-cache tests. No binary format change is required: `CachedMigration` is already stored by path with `ALGORITHM_VERSION`.

### 2. Preserve resolved edges only when the resolution context is identical

Keep published `IndexEntry` values for unchanged paths, but do not treat a changed file set or an unchanged set of type names as proof that its resolved edges remain valid. `EdgeResolver.resolveMarkers` builds simple-name maps with `putIfAbsent` in entry order; `SemanticCallResolver` uses the complete class/method hierarchy. A safe scoped-resolution path requires equality of the **ordered resolution inputs**, not merely equality of declaration-name sets:

- Same ordered file and class inventory, imports, source set/level, invoker configuration, and call-edge schema. Compare the effective ordered lookup context, including simple-name collisions; if its equivalence cannot be established, fall back.
- Same resolution-relevant symbols and metadata: class kind/modifiers, inheritance and generic relationships, fields, method signatures/return types/modifiers, and synthetic methods. An incomplete comparison or parser/IO/compatibility diagnostic forces fallback. In the first implementation, any added, removed, or changed declaration forces fallback, even when its name appears absent from unchanged edges.
- Only the bodies/edges of changed files differ. Resolve their raw edges against the complete new symbol universe while leaving already resolved unchanged entries intact. `resolveMarkers` and `resolveSymbols` must operate on the selected entries without mutating reused entries; the symbol context still includes every new entry.

This deliberately makes the benchmark's added `revision()` method a **full-resolution fallback** at first. Name absence alone cannot rule out changes to single-abstract-method (SAM) status, overload selection, virtual dispatch, or unresolved/reflective resolution. Do not mark this scenario eligible by inspecting only the final resolved edges. The existing snapshot stores resolved edges, not the raw edges needed to rerun global resolution; therefore fallback must continue re-extracting unchanged files.

After the strict path passes parity tests, there are two explicit options for the added-method case: prove a broader dependency guard with tests for SAM/overloads/dispatch/unresolved and ordered-name binding, **or** persist raw per-file edges in a versioned snapshot so global resolution can run without reparsing unchanged files. The latter requires reader/writer compatibility and recovery tests; neither option is assumed by this design. Keep the existing global extraction and resolution as the correctness baseline.

Touch for the strict path: `CodebaseIndex.build`, `EdgeResolver.resolveMarkers/resolveSymbols`, and semantic parity tests. Prefer a small internal resolution-scope decision over a duplicated build pipeline. A raw-edge snapshot change is a separate, measured follow-up, not part of the initial patch.

### 3. Address residual costs only after 1–2 are correct

`index.call_graph` (~1.8 s) and `index.publish` (~2.1 s) are secondary. For a genuinely unchanged index, a future no-op fast path may avoid graph reconstruction and publication, but must validate that sources/configuration and the current generation did not change while checking, under the same writer-lock/atomic-publication contract. Do not remove the publication source recheck or binary integrity verification merely to reduce time; measure their separate value after the main global work is gone. Source hashing and compatibility scanning are smaller (~0.3 s and ~0.9 s in the one-file run) and are not first targets.

## Correctness contract and focused validation

- Incremental and clean rebuilds must produce the same class/method inventory, resolved call edges (including resolution levels/evidence), graph query answers, and migration suggestions. Test both normal and `--frozen-index` readers.
- Cover body-only edits and verify that the strict path actually reuses unchanged edges. Cover `revision()` additions, overloads/overrides, SAM changes, changed return/field types, imports/inheritance, add/delete/rename, source level/config/invoker changes, unresolved calls that become resolvable, duplicate simple names with changed entry order, multiple classes in one file, and old cache/schema fallback. Each declaration or order change must take the full path until a separately proven broader guard exists.
- Keep concurrent reader/writer and killed-writer recovery tests: readers must see one complete generation, and a later refresh must match a clean rebuild. Never expose a partially updated graph or migration map.
- Add phase counters for `migrations.reused_paths`, `migrations.scanned_paths`, `build.edges_reused_files`, `build.edges_extracted_files`, and `build.resolved_files`, plus a tagged `build.full_fallback_reason` event. They should prove that a body-only edit reuses unaffected edges, while the benchmark's added method takes the full edge path and rescans only one migration path.
- Use focused JUnit/integration parity tests and one-shot phase traces on disposable small corpora during implementation. Do not use another multi-hour dedicated campaign for diagnosis. The first patch targets the ~10 s global migration pass on `edit_single`; its added method intentionally retains the ~14–15 s global edge path. Removing that edge cost is a follow-up objective contingent on a proven broader guard or versioned raw-edge persistence, **not** a calibrated CI threshold or promised latency.

## Review findings and residual risks

- Rune's limited review identified two P1 risks in the original proposal: `putIfAbsent` makes simple-name marker binding order-sensitive, and a new method can alter SAM/dispatch or candidate selection without an existing edge of that name. The strict guard above addresses both by requiring identical ordered resolution inputs and falling back on all declaration changes. A broader guard needs independent proof before implementation.
- Migration results must retain current ordering and duplicate/multi-class behavior. Old snapshot versions must not silently reuse incompatible suggestions.
- A no-op fast path has a publication race unless source/configuration revalidation and writer serialization are preserved; it is explicitly deferred.
