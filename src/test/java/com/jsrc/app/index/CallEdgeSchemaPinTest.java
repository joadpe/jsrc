package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.HybridJavaParser;

/**
 * The call-edge schema of the JSON entries moved from 5 to 6 with the binary index format (11 to
 * 12), so that edges written by 2.6.1 are extracted again once. FrozenIndexContractTest pins the
 * binary version; this pins the schema.
 */
class CallEdgeSchemaPinTest {

    @TempDir
    Path root;

    @Test
    void edgesWrittenWithTheSchemaOf261AreNotCurrent() throws Exception {
        Path source = root.resolve("app/Repo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package app;
                public class Repo { public String name() { return ""; } public void use() { name(); } }
                """);
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(source), root, List.of());
        index.save(root);

        Path edges = root.resolve(".jsrc/edges.json");
        String json = Files.readString(edges);
        assertTrue(json.matches("(?s).*\"callEdgeSchemaVersion\"\\s*:\\s*6\\b.*"),
                "2.6.2 writes call-edge schema 6: " + json);
        assertTrue(CodebaseIndex.hasCurrentSplitCallEdgeSchema(root));

        // The schema 2.6.1 wrote
        Files.writeString(edges, json.replaceAll("(\"callEdgeSchemaVersion\"\\s*:\\s*)6\\b", "$15"));
        assertFalse(CodebaseIndex.hasCurrentSplitCallEdgeSchema(root),
                "edges of schema 5 must not count as current");
    }
}
