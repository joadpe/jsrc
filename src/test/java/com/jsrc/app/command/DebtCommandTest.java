package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.command.quality.DebtCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexEntry;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class DebtCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void countsSmellsOfFilesMissingFromPartialCache() throws Exception {
        Path risky = writeEmptyCatch("Risky");
        Path other = writeEmptyCatch("Other");
        var files = List.of(risky, other);
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        index.saveWithGraph(tempDir, null);
        Files.writeString(risky, "// edited\n", java.nio.file.StandardOpenOption.APPEND);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        assertTrue(indexed.getCachedSmells("Other.java").isEmpty(),
                "Refresh should cache only the edited file");

        var out = new ByteArrayOutputStream();
        new DebtCommand(false).execute(new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(out)), indexed,
                new HybridJavaParser()));

        var result = (Map<String, Object>) JsonReader.parse(out.toString());
        assertEquals(2L, ((Map<String, Object>) result.get("byCategory")).get("maintainability"));
        assertTrue(CodebaseIndex.loadPublished(tempDir).stream()
                .allMatch(IndexEntry::smellsComputed), "debt should cache what it analyzed");
    }

    private Path writeEmptyCatch(String className) throws Exception {
        return Files.writeString(tempDir.resolve(className + ".java"), """
                public class %s {
                    public void load() {
                        try {
                            Integer.parseInt("x");
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                """.formatted(className));
    }
}
