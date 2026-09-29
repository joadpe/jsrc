package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.JsrcCliFactory;
import com.jsrc.app.project.SourceSet;
import com.jsrc.app.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexRefreshCacheTest {

    @Test
    void unresolvedEdgeFromPublishedIndexDoesNotForceUnchangedReparse(@TempDir Path root)
            throws Exception {
        Path source = root.resolve("Demo.java");
        Files.writeString(source, "public class Demo { void run() {} }\n");
        CallEdge unresolved = new CallEdge("Demo", "run", -1,
                "missing.Target", "unknown", 1, -1);
        IndexEntry entry = new IndexEntry("Demo.java", Hashing.sha256(Files.readAllBytes(source)),
                Files.getLastModifiedTime(source).toMillis(), SourceSet.UNKNOWN,
                List.of(), List.of(unresolved), List.of(), 0);
        new CodebaseIndex(List.of(entry)).saveWithGraph(root, null);
        assertEquals(-1, CodebaseIndex.loadPublished(root).getFirst()
                .callEdges().getFirst().argCount());

        var originalErr = System.err;
        var capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
        int exitCode;
        try {
            exitCode = JsrcCliFactory.create().execute("--dir", root.toString(), "index");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(0, exitCode);
        assertTrue(capturedErr.toString().contains(
                "Done. Indexed 1 files (0 re-indexed, 1 cached)."),
                capturedErr.toString());
    }
}
