package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommandDocumentationContractTest {

    private final CommandDocumentationRenderer renderer =
            new CommandDocumentationRenderer(DefaultCommandRegistry.create());

    @Test
    void generatedCatalogIsDeterministicAndContainsEveryCommand() {
        String first = renderer.renderCatalog();
        String second = renderer.renderCatalog();

        assertEquals(first, second);
        assertFalse(first.contains("%n"), "Picocli format tokens must be normalized");
        for (CommandDescriptor command : DefaultCommandRegistry.create().commands()) {
            assertTrue(first.contains("| `" + command.name() + "` |"), command.name());
        }
    }

    @Test
    void repositoryDocumentationContainsTheGeneratedCatalogExactly() throws Exception {
        String expected = renderer.renderCatalog();

        assertEquals(expected, renderer.extractGeneratedBlock(Files.readString(Path.of("README.md"))));
        assertEquals(expected, renderer.extractGeneratedBlock(Files.readString(Path.of("SKILL.md"))));
    }

    @Test
    void replacementRejectsDocumentsWithoutCatalogMarkers() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.replaceGeneratedBlock("# no markers"));
    }
}
