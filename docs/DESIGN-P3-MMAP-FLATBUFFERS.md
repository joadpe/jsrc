# Design: mmap / FlatBuffers Index I/O (P3)

**Context:** Post P1 lazy CallGraph (#25) and P2 --frozen-index (#28), further cold load I/O / parse cost reduction for large indexes (~Spring Boot scale: 8K+ files, 50K+ methods) via memory-mapped I/O and/or zero-copy serialization formats. Goal is to pick a **vertical first slice** with falsifiable oracles (not boil ocean) and establish defaults for format bump, compatibility, and native-image implications.

**Tracking:** Part of agent latency plan in #23 — next major latency slice after P1/P2 shipped.

---

## Phase 0: Inventory (tip 0e429ce)

### Current State

**BinaryIndexV2Reader/Writer** (V2 binary format, VERSION=2):
- **Magic + Header:** `JSRC` (4B) + VERSION (4B) + CRC32 (4B) + PAYLOAD
- **Sections:** STRING_TABLE, CLASSES, EDGES, GRAPH, SMELLS, MIGRATIONS (sequential, count-prefixed)
- **Reader.readLazy()** (#25 / commit `42d1d51`):
  - `Files.readAllBytes(file)` → entire index.bin loaded into heap byte[]
  - CRC32 validation on full payload (line 120-124)
  - Eager parse: STRING_TABLE, CLASSES, EDGES, SMELLS, MIGRATIONS
  - Lazy parse: GRAPH (deferred until `LazyIndexData.ensureGraph()` called)
  - **Keeps payload byte[] in memory** for lazy graph access (line 60-64)
- **Writer.write()** (lines 40-179):
  - String table deduplication (line 46-85)
  - Sequential DataOutputStream writes to ByteArrayOutputStream (line 87-163)
  - CRC32 computed, written to temp file + atomic rename (line 165-179)

**IndexedCodebase.tryLoad()** (lines 91-165):
- **Non-frozen path:** computes stamp (index mtime + max source mtime walk), rebuilds if changed
- **Frozen path (#28):** skips walk, loads index.bin directly via `readLazy()`, fails if missing/corrupt
- **Memory footprint:** full byte[] payload kept for lazy graph access

**Spring Boot scale fixture:**
- **8,323 files, 13,335 classes, 52K methods**
- **index.bin size:** ~8.5MB on disk (compressed with string table)
- **Cold load:** ~77ms (eager V2), ~60ms (lazy V2 classes-only, no graph)
- **Memory:** ~12MB heap for payload byte[] + parsed IndexEntry objects

**P1 lazy CallGraph (#25)** shipped:
- `LazyIndexData` defers graph parse until `ensureGraph()` called
- Overview/mini/summary/read commands skip graph section → reduced cold latency
- **Format-compatible:** no VERSION bump, sequential skip via count prefixes

**P2 --frozen-index (#28)** shipped:
- `--frozen-index` flag skips filesystem walk, loads existing index directly
- Fails clearly if index missing/corrupt (no auto-rebuild)

**Java stdlib I/O:**
- **FileChannel.map():** Memory-mapped I/O (java.nio)
- **ByteBuffer:** Direct buffers, zero-copy reads (wrap mmap region)
- **DataInputStream wrap ByteArrayInputStream:** Current parse path (heap allocation per read)

**Serialization libraries:**
- **FlatBuffers (Google):**
  - Zero-copy access via ByteBuffer offsets
  - Schema versioning (forward/backward compat)
  - Code generation: `flatc` compiler → Java classes
  - Native-image: works (tested in GraalVM projects)
- **Protobuf (Google):**
  - Parse to heap objects (not zero-copy)
  - Slower than FlatBuffers for read-heavy workloads
  - More mature schema evolution
- **Cap'n Proto:**
  - Zero-copy like FlatBuffers
  - C++-first, Java bindings less mature
- **MessagePack, CBOR, etc.:**
  - Compact binary formats but require parse to heap

**Native-image (GraalVM) constraints:**
- Reflection-based serialization → requires native-image config
- Direct ByteBuffer + FileChannel.map() → **works natively** (tested in GraalVM)
- FlatBuffers generated code → **works natively** (no reflection)
- Must avoid dynamic class loading / reflection in hot path

### Gap Analysis

**Exists:**
- V2 binary format with string table deduplication
- Lazy CallGraph parse (#25) → deferred section reads
- --frozen-index (#28) → skip walk, load only
- CRC32 integrity check
- Atomic write (temp file + rename)

**Partial:**
- **Current I/O:** `Files.readAllBytes()` loads full index.bin to heap → ~12MB allocation for Spring Boot scale
- **Parse cost:** DataInputStream wrapping ByteArrayInputStream → sequential reads, no zero-copy
- **Memory lifetime:** full payload byte[] kept in LazyIndexData for graph access → heap pressure

**Gap:**
- **No mmap:** Full file load to heap even when only small subset accessed (e.g., overview needs classes, not graph)
- **No zero-copy:** Every string, int, long read via DataInputStream → heap allocations
- **Large indexes:** 50MB+ indexes (10K+ files) may OOM on small heaps or slow on cold start
- **Spring Boot scale (~8.5MB):** cold load is acceptable (~60ms) but not optimal for 100K+ method codebases

**Do Not Reinvent:**
- String table deduplication (keep)
- Lazy graph pattern (#25) (keep, extend to other sections)
- CRC32 integrity check (keep or adapt)
- Atomic write (keep)

---

## Goal

Reduce cold load I/O / parse cost for **large indexes** (Spring Boot scale and beyond) via mmap and/or FlatBuffers — pick a **vertical first slice** with falsifiable oracles:

1. **mmap-only slice:** Memory-map index.bin, parse via ByteBuffer wrapping mmap region, no format change (VERSION=2 compat)
2. **FlatBuffers-first slice:** New format (VERSION=3), zero-copy schema, generated accessors, optional mmap
3. **Hybrid:** V2 with mmap + lazy parse improvements, defer FlatBuffers to later

**Prefer:** one enforcement path; falsifiable oracle (cold load bytes read, heap allocation, parse time).

---

## Non-Goals

- **Not in this slice:** redoing watch protocol (shipped #22), eval harness commits (out-of-repo)
- **Not in this slice:** changing string table deduplication algorithm (keep current)
- **Not in this slice:** parallel parse (CPU-bound optimization, defer)
- **Not changing:** pom Java version (stay 21), GraalVM native-image target (keep working)
- **Not changing:** lazy graph API (#25), --frozen-index semantics (#28)

---

## Design

### Strategy: Mmap-Only Vertical Slice (default)

**Rationale:** Simplest path with measurable I/O win; no format change → backward compat; minimal risk.

#### Proposed Changes

**1. Replace `Files.readAllBytes()` with mmap in BinaryIndexV2Reader.readLazy():**

**Current (line 240):**
```java
byte[] allBytes = Files.readAllBytes(file);
```

**Proposed:**
```java
try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
    long size = channel.size();
    var mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
    
    // Wrap mmap ByteBuffer, parse as before
    byte[] header = new byte[12];
    mappedBuffer.get(header);
    // ... (parse magic, version, CRC32 from header bytes)
    
    // Store ByteBuffer reference in LazyIndexData (not byte[])
    var payloadBuffer = mappedBuffer.slice(); // from offset 12
    // ... (parse sections via DataInputStream wrapping payloadBuffer)
}
```

**2. Update LazyIndexData to hold ByteBuffer (not byte[]):**

**Current (line 54-56):**
```java
public static class LazyIndexData {
    private final IndexData lightData;
    private final byte[] payload;  // full heap allocation
    private final int graphSectionOffset;
    // ...
}
```

**Proposed:**
```java
public static class LazyIndexData {
    private final IndexData lightData;
    private final ByteBuffer payload;  // mmap region (no heap copy)
    private final int graphSectionOffset;
    // ...
    
    public CallGraph ensureGraph() {
        if (lazyGraph == null && graphSectionOffset >= 0) {
            // Slice ByteBuffer to graph section, parse without copy
            var graphBuffer = payload.slice(graphSectionOffset, payload.limit() - graphSectionOffset);
            var in = new DataInputStream(new ByteBufferInputStream(graphBuffer));
            lazyGraph = readGraph(in, stringTable);
            // ...
        }
        return lazyGraph;
    }
}
```

**3. Create `ByteBufferInputStream` adapter (zero-copy DataInputStream source):**

**New class (internal to BinaryIndexV2Reader):**
```java
private static class ByteBufferInputStream extends InputStream {
    private final ByteBuffer buffer;
    
    ByteBufferInputStream(ByteBuffer buffer) {
        this.buffer = buffer;
    }
    
    @Override
    public int read() {
        return buffer.hasRemaining() ? (buffer.get() & 0xFF) : -1;
    }
    
    @Override
    public int read(byte[] b, int off, int len) {
        if (!buffer.hasRemaining()) return -1;
        len = Math.min(len, buffer.remaining());
        buffer.get(b, off, len);
        return len;
    }
    
    // skip(), available() delegates to ByteBuffer position/remaining
}
```

**4. CRC32 validation over mmap region:**

**Current (line 119-123):**
```java
CRC32 crc = new CRC32();
crc.update(allBytes, 12, allBytes.length - 12);
if ((int) crc.getValue() != storedCrc) {
    throw new IOException("CRC32 mismatch — index may be corrupt");
}
```

**Proposed (adapt for ByteBuffer):**
```java
CRC32 crc = new CRC32();
var payloadBuffer = mappedBuffer.slice(12, mappedBuffer.limit() - 12);
if (payloadBuffer.hasArray()) {
    crc.update(payloadBuffer.array(), payloadBuffer.arrayOffset(), payloadBuffer.remaining());
} else {
    // Fallback: read chunks into temp buffer (mmap may not expose array)
    byte[] chunk = new byte[8192];
    while (payloadBuffer.hasRemaining()) {
        int len = Math.min(chunk.length, payloadBuffer.remaining());
        payloadBuffer.get(chunk, 0, len);
        crc.update(chunk, 0, len);
    }
}
if ((int) crc.getValue() != storedCrc) {
    throw new IOException("CRC32 mismatch — index may be corrupt");
}
```

#### Trade-offs

**Pros:**
- **Zero heap allocation for payload:** mmap region stays off-heap (OS page cache)
- **Lazy read:** OS pages in index.bin data only when accessed (e.g., skip graph section if not needed)
- **No format change:** VERSION=2 unchanged, existing index.bin works
- **Native-image safe:** FileChannel.map() works in GraalVM native-image
- **Backward compat:** Clients with old reader can still read new index.bin (format unchanged)

**Cons:**
- **Complexity:** ByteBufferInputStream adapter + CRC32 chunk read
- **Platform dependency:** mmap may have platform quirks (Windows file lock, Linux page fault latency)
- **Not zero-copy parse:** Still using DataInputStream (int/long reads copy bytes to stack)
- **String allocation:** String table still allocates heap Strings (no change vs current)

#### Alternative: FlatBuffers-First Vertical Slice

**New format (VERSION=3):** Replace V2 binary with FlatBuffers schema.

**Pros:**
- **True zero-copy:** FlatBuffers accessors read ByteBuffer offsets directly (no DataInputStream)
- **Schema versioning:** Forward/backward compat via FlatBuffers schema evolution
- **Generated code:** Type-safe accessors (no manual DataInputStream reads)
- **Supports optional fields:** Can add new sections without breaking old readers

**Cons:**
- **Format incompatible:** Requires VERSION=3, clients must re-index
- **Schema overhead:** `.fbs` schema file + `flatc` compiler in build
- **Learning curve:** Team must learn FlatBuffers schema lang + Java API
- **Migration:** Need dual-reader support (V2 + V3) or deprecate V2
- **String table:** FlatBuffers has its own string dedup — may duplicate work or require custom FlatBuffers table

**Default choice:** **Mmap-only slice first** (lower risk, no format change). FlatBuffers deferred to P4+ if mmap slice shows insufficient gains.

#### Alternative: Hybrid (V2 mmap + lazy parse improvements)

**Extend mmap slice with:**
- Lazy parse of EDGES, SMELLS, MIGRATIONS sections (similar to GRAPH #25)
- Section offsets in header (VERSION=2.1 or keep sequential skip)

**Pros:**
- Incremental: build on P1 lazy pattern
- No format breaking change if using sequential skip

**Cons:**
- More code churn in BinaryIndexV2Reader (already complex)
- Gains may be marginal if EDGES/SMELLS are small (<10% of index size)

**Default choice:** Start with mmap-only (simpler). Add lazy sections if mmap alone insufficient.

---

## Acceptance Criteria

**A1:** Cold load of Spring Boot fixture (~8.5MB index.bin) with mmap allocates **≤1MB heap for payload** (vs ~12MB baseline). 
_Oracle:_ JVM heap profiler or `-XX:+PrintGCDetails` after load; assert byte[] payload size ≤1MB or ByteBuffer off-heap confirmed.

**A2:** Cold load wall-clock time on Spring Boot fixture: mmap ≤ baseline ± 10% (no regression). 
_Oracle:_ Benchmark: 10 cold runs (new JVM each), compare median. Allow 10% margin (mmap may be slower on first access, OS page fault cost).

**A3:** Lazy graph access: `overview` (classes-only) reads **<20% of index.bin file size** from disk (I/O bytes measured). 
_Oracle:_ strace/iotop or JVM FileChannel stats; assert bytes read < 20% of file size (proves lazy/mmap avoid full read).

**A4:** Existing index.bin (V2) still readable with mmap path (backward compat). 
_Oracle:_ Commit known-good V2 index.bin fixture; load with mmap reader; assert entries/graph match eager baseline.

**A5:** Native-image build succeeds + mmap reader works in native binary (GraalVM compat). 
_Oracle:_ `mvn package -Pnative`, run `./target/jsrc --frozen-index overview --json`, assert success.

**A6:** CRC32 validation over mmap region succeeds for valid index, fails for corrupt index. 
_Oracle:_ Write valid index.bin, load → success. Truncate/modify index.bin, load → CRC32 mismatch exception.

**A7:** Lazy graph parse still works: `callers` command loads graph on-demand, returns correct results. 
_Oracle:_ Regression test vs P1 baseline: assert graph parse count = 1 after callers, results match.

**A8:** Watch session with mmap: warm cache (#20) still load-once, second command reuses cached index. 
_Oracle:_ WatchCommand two sequential overview; assert mmap load called once (warm cache bypasses re-load).

---

## Test Plan

1. **Unit:** BinaryIndexV2ReaderTest additions:
 - `testReadLazy_mmap_heapUsage()` (A1) — measure heap allocation before/after load
 - `testReadLazy_mmap_backwardsCompatible()` (A4) — load V2 fixture with mmap path
 - `testReadLazy_mmap_crc32Valid()` (A6) — valid index succeeds
 - `testReadLazy_mmap_crc32Corrupt()` (A6) — truncated/modified index fails with CRC32 error

2. **Integration:** MmapIndexBenchmark (new):
 - Cold load wall-clock: mmap vs baseline (A2) — median of 10 runs, assert ≤ baseline + 10%
 - I/O bytes read: strace wrapper or FileChannel instrumentation (A3) — assert <20% file size for overview

3. **Native-image:** NativeImageSmokeTest (existing or new):
 - Build native binary, run `--frozen-index overview --json` (A5) — assert exit 0 + valid JSON

4. **Regression:**
 - Existing BinaryIndexV2LazyLoadTest suite (A7) — assert lazy graph tests pass with mmap
 - WatchCommandTest warm cache (A8) — assert load-once behavior unchanged

---

## Known Debt

- **FlatBuffers:** Deferred to P4+ if mmap slice insufficient
- **Parallel parse:** CPU-bound optimization (deferred)
- **Lazy EDGES/SMELLS/MIGRATIONS:** Defer unless mmap alone shows gaps
- **Section offsets in header:** Only if sequential skip proves brittle (no evidence yet)
- **Eval harness update:** jsrc-eval out-of-repo may benefit from mmap (separate slice)

---

## Open Questions & Defaults

**Q1:** Should mmap path be default or opt-in flag (`--mmap`)? 
**Default:** Make it default (always use mmap if available). No flag needed. Old readers (pre-mmap) fail gracefully on corrupt/missing index (no silent data loss).

**Q2:** What if mmap fails (platform quirk, permissions, etc.)? 
**Default:** Fallback to `Files.readAllBytes()` with warning log. Test fallback path.

**Q3:** Should CRC32 validation read full file or trust mmap? 
**Default:** Validate CRC32 over mmap region (chunk read if no backing array). Ensures integrity check still runs.

**Q4:** Should ByteBuffer be direct or heap-backed? 
**Default:** mmap returns MappedByteBuffer (direct, off-heap). Don't wrap heap byte[].

**Q5:** Version bump (V2→V3) or keep VERSION=2? 
**Default:** Keep VERSION=2 (no format change). mmap is I/O optimization, not format change.

**Q6:** Lazy parse EDGES/SMELLS in this slice or defer? 
**Default:** Defer. Focus on mmap I/O win first. Add lazy sections in follow-up if needed.

**Q7:** Should mmap region be unmapped explicitly or GC'd? 
**Default:** Rely on GC + cleaner (MappedByteBuffer finalizer). Explicit unmap via reflection is fragile. Document that index.bin file may stay locked until GC (warn on Windows).

**Q8:** Should FlatBuffers be prototyped in parallel or wait for mmap results? 
**Default:** Wait for mmap slice results. If mmap shows ≥30% improvement, ship it and defer FlatBuffers. If <10% improvement, pivot to FlatBuffers in next slice.

---

## Enforcement

**Single point:** `BinaryIndexV2Reader.readLazy()` switches from `Files.readAllBytes()` to `FileChannel.map()`. All callers (IndexedCodebase.tryLoad, WatchCommand) unchanged (use same readLazy API).

**Format bump:** None (VERSION=2 unchanged). If FlatBuffers chosen later, bump to VERSION=3.

**Compatibility:** Old readers (pre-mmap) can still read V2 index.bin written by new writer (format unchanged). New readers (mmap) can read V2 index.bin written by old writer (format unchanged).

---

## Approval

- [ ] **Approved for Implementer** (maintainer/reviewer checkbox)

---

**Related:** #23 (tracking plan), #25 (P1 lazy CallGraph shipped), #28 (P2 --frozen-index shipped), #20 (watch warm cache), #22 (envelope)
