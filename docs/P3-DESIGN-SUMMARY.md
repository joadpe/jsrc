# P3 Design Summary: mmap / FlatBuffers Index I/O

**Status:** Design complete, awaiting approval for implementer  
**Issue:** [#32](https://github.com/joadpe/jsrc/issues/32)  
**Full ADR:** [DESIGN-P3-MMAP-FLATBUFFERS.md](./DESIGN-P3-MMAP-FLATBUFFERS.md)  
**Branch:** `cursor/design-p3-mmap-flatbuffers-11bc`

---

## Executive Summary

### Problem

Current BinaryIndexV2Reader loads entire index.bin to heap via `Files.readAllBytes()`:
- **Spring Boot scale (8.5MB index.bin):** ~12MB heap allocation, ~60-77ms cold load
- **Large codebases (50MB+ indexes):** May OOM on small heaps or slow on cold start
- **Inefficiency:** Full heap copy even when only small subset accessed (e.g., overview needs classes, not graph)

P1 lazy CallGraph (#25) and P2 --frozen-index (#28) shipped — but still loading full file to heap.

### Recommended Solution: mmap-Only Vertical Slice

**Strategy:** Memory-map index.bin, parse via ByteBuffer wrapping mmap region, no format change.

**Why mmap-first (not FlatBuffers-first)?**
- Lower risk: No format change → backward compat, no re-index required
- Measurable win: Zero heap allocation (~12MB → ≤1MB), lazy OS page-in
- Native-image safe: FileChannel.map() works in GraalVM
- Simplest path: No schema overhead, no code generation

**Deferred:** FlatBuffers (VERSION=3) to P4+ if mmap shows <10% improvement.

---

## Key Design Choices

### 1. Format Bump: None (stay VERSION=2)
- mmap is I/O optimization, not format change
- Old readers can read new index.bin (format unchanged)
- New readers can read old index.bin (format unchanged)

### 2. Implementation
- Replace `Files.readAllBytes()` → `FileChannel.map()` in `BinaryIndexV2Reader.readLazy()`
- Store `ByteBuffer` (mmap region) instead of `byte[]` in `LazyIndexData`
- `ByteBufferInputStream` adapter (zero-copy DataInputStream source)
- CRC32 validation adapted for mmap region (chunk read if no backing array)

### 3. Fallback Strategy
- If mmap fails (platform quirk, permissions): fallback to `Files.readAllBytes()` with warning log
- Test fallback path in CI

### 4. Compatibility Guarantees
- **Lazy graph (#25):** No API change — ByteBuffer replaces byte[] payload
- **--frozen-index (#28):** No semantic change — mmap works with frozen flag
- **Watch warm cache (#20):** No change — session cache still avoids re-load
- **Native-image:** FileChannel.map() works in GraalVM (tested)

---

## Acceptance Criteria (8 Strong Oracles)

| # | Criterion | Oracle |
|---|-----------|--------|
| A1 | Heap usage ≤1MB | JVM profiler; assert ByteBuffer off-heap |
| A2 | Wall-clock ≤ baseline ±10% | Benchmark: 10 cold runs, median comparison |
| A3 | Lazy read <20% file size | strace/iotop; assert bytes read <20% |
| A4 | Backward compat | Load V2 fixture; assert entries/graph match |
| A5 | Native-image | `mvn package -Pnative`; run overview |
| A6 | CRC32 validation | Valid → success; corrupt → exception |
| A7 | Lazy graph parse | callers loads on-demand; assert parse count=1 |
| A8 | Watch warm cache | Two overview; assert mmap load once |

---

## Trade-offs

### Pros (mmap-only)
✅ Zero heap allocation for payload (OS page cache)  
✅ Lazy read: OS pages in data only when accessed  
✅ No format change (VERSION=2 unchanged)  
✅ Native-image safe (FileChannel.map() works)  
✅ Backward compat (old readers work)  

### Cons (mmap-only)
❌ Platform dependency (Windows file lock, Linux page fault latency)  
❌ Not true zero-copy parse (still using DataInputStream)  
❌ String allocation unchanged (heap Strings still allocated)  
❌ ByteBufferInputStream adapter complexity  

### Why Not FlatBuffers-First?
❌ Format incompatible (VERSION=3, requires re-index)  
❌ Schema overhead (`.fbs` file + `flatc` compiler)  
❌ Learning curve (FlatBuffers schema lang + Java API)  
❌ Migration complexity (dual-reader support or V2 deprecation)  
❌ String table: FlatBuffers has own dedup — may duplicate work  

**Pivot decision:** If mmap slice shows <10% improvement, pivot to FlatBuffers in P4. If ≥30% improvement, ship mmap and defer FlatBuffers.

---

## Open Questions Resolved

| Question | Default Choice | Rationale |
|----------|---------------|-----------|
| Q1: mmap default or opt-in? | **Default** | Always use mmap if available; fallback with warning |
| Q2: CRC32 over mmap? | **Yes** | Chunk read if no backing array; ensures integrity |
| Q3: Version bump? | **No (stay V2)** | Format unchanged; only bump if FlatBuffers later |
| Q4: Lazy EDGES/SMELLS? | **Defer** | Focus on mmap I/O win first |
| Q5: FlatBuffers now or wait? | **Wait** | Ship mmap slice first; pivot if <10% gain |
| Q6: ByteBuffer direct/heap? | **Direct (mmap)** | MappedByteBuffer off-heap; no heap byte[] wrap |
| Q7: Explicit unmap or GC? | **GC + cleaner** | Rely on finalizer; warn Windows file lock |

---

## Known Debt

- **FlatBuffers:** Deferred to P4+ if mmap insufficient
- **Parallel parse:** CPU-bound optimization (deferred)
- **Lazy EDGES/SMELLS/MIGRATIONS:** Defer unless mmap shows gaps
- **Section offsets in header:** Only if sequential skip proves brittle
- **Eval harness update:** jsrc-eval out-of-repo may benefit from mmap

---

## Enforcement

**Single point:** `BinaryIndexV2Reader.readLazy()` switches from `Files.readAllBytes()` to `FileChannel.map()`.  
All callers (IndexedCodebase.tryLoad, WatchCommand) unchanged — use same readLazy API.

**Format bump:** None (VERSION=2 unchanged).  
If FlatBuffers chosen later, bump to VERSION=3.

---

## Next Steps

1. **Approval:** Maintainer/reviewer checkbox in [#32](https://github.com/joadpe/jsrc/issues/32)
2. **Implementer:** After approval, coordinator launches implementer on separate branch
3. **Metrics:** After implementation, measure:
   - Heap allocation reduction (expect ~12MB → ≤1MB)
   - Cold load time (expect ≤ baseline ±10%)
   - I/O bytes read (expect <20% file size for overview)
4. **Pivot decision:** If mmap shows <10% improvement, pivot to FlatBuffers in P4

---

**Related:**
- [#23](https://github.com/joadpe/jsrc/issues/23) — Agent latency tracking plan
- [#25](https://github.com/joadpe/jsrc/pull/25) — P1 lazy CallGraph (shipped)
- [#28](https://github.com/joadpe/jsrc/issues/28) — P2 --frozen-index (shipped)
- [#20](https://github.com/joadpe/jsrc/pull/20) — Watch warm cache (shipped)
