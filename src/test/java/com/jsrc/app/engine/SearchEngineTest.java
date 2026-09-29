package com.jsrc.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.parser.model.ClassInfo;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchEngineTest {
    @Test
    void searchesSuppliedLinesAndClassContextWithoutCliOrFilesystem() {
        var document = new JsrcEngine.SearchDocument("src/Demo.java",
                List.of("class Demo {", "  // TODO review", "  void run() { FIXME(); }", "}"),
                List.of(ClassInfo.basic("Demo", "", 1, 4, List.of(), List.of())));
        JsrcEngine.SearchSource source = new JsrcEngine.SearchSource() {
            @Override
            public List<String> paths() {
                return List.of(document.path());
            }

            @Override
            public Optional<JsrcEngine.SearchDocument> document(String path) {
                return Optional.of(document);
            }
        };

        var matches = new JsrcEngine().search(source, "TODO|FIXME").matches();

        assertEquals(2, matches.size());
        assertEquals("src/Demo.java", matches.getFirst().file());
        assertEquals("Demo", matches.getFirst().className());
        assertEquals(2, matches.getFirst().line());
        assertTrue(matches.getFirst().inComment());
        assertFalse(matches.getLast().inComment());
    }
}
