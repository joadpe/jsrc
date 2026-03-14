# jsrc Benchmark

## Baseline: 2026-03-14

**Codebase:** jsrc itself (51 Java files, 53 classes, 342 methods)
**Machine:** Intel NUC, Linux 6.17, OpenJDK 22 (Semeru)
**Note:** Includes JVM startup + Tree-sitter native library loading (~500ms overhead)

| Command | Time | Files | Results |
|---------|------|-------|---------|
| `--overview` | 1728ms | 51 | 56 types |
| `--classes` | 1599ms | 51 | 56 types |
| `--smells` | 957ms | 51 | 81 smells |
| `--summary App` | 671ms | 51 | 1 class |
| `--hierarchy CodeParser` | 2715ms | 51 | 1 class |
| `--implements CodeParser` | 1460ms | 51 | 0 (interface search) |
| `--annotations Override` | 1715ms | 51 | 21 matches |
| `--deps TreeSitterParser` | 708ms | 51 | 1 class |
| `findMethods` (search) | 646ms | 51 | 6 methods |
| `--call-chain detectSmells` | 877ms | 51 | 22 chains |

### Observations

- **JVM + native loading:** ~500ms fixed overhead on every invocation
- **Fastest:** method search (646ms) and deps (708ms) — targeted operations
- **Slowest:** hierarchy (2715ms) — needs two full passes (parse all classes, then resolve relationships)
- **Per-file cost:** ~15-30ms per file for full parse (Tree-sitter + JavaParser)
- **At scale (10K files):** estimated 150-300s without index; index will be critical

### Improving performance

1. **Persistent index** (implemented, needs JSON reader for load)
2. **Parallel parsing** — `Files.walk` with parallel stream
3. **GraalVM native-image** — eliminates JVM startup overhead
4. **Tree-sitter only mode** — skip JavaParser for commands that don't need semantic info
