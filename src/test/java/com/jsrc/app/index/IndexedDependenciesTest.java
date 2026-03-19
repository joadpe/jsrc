package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.model.DependencyResult;
import com.jsrc.app.parser.HybridJavaParser;

/**
 * Tests for IndexedCodebase.getDependencies() — deps from index without parsing.
 */
class IndexedDependenciesTest {

    @TempDir
    Path tempDir;

    @Test
    void classWithFieldsAndConstructor() throws Exception {
        Path file = tempDir.resolve("OrderService.java");
        Files.writeString(file, """
                package com.app;
                import com.app.repo.OrderRepo;
                import com.app.event.EventBus;
                public class OrderService {
                    private final OrderRepo repo;
                    private final EventBus bus;
                    public OrderService(OrderRepo repo, EventBus bus) {
                        this.repo = repo;
                        this.bus = bus;
                    }
                    public void process() {}
                }
                """);

        var indexed = buildIndex(file);
        var deps = indexed.getDependencies("OrderService");
        assertTrue(deps.isPresent());
        var d = deps.get();

        assertTrue(d.imports().contains("com.app.repo.OrderRepo"));
        assertTrue(d.imports().contains("com.app.event.EventBus"));
        assertEquals(2, d.fieldDependencies().size());
        assertTrue(d.fieldDependencies().stream().anyMatch(f -> f.name().equals("repo") && f.type().equals("OrderRepo")));
        assertTrue(d.constructorDependencies().size() >= 2);
    }

    @Test
    void classWithoutConstructor() throws Exception {
        Path file = tempDir.resolve("Helper.java");
        Files.writeString(file, """
                package com.app;
                public class Helper {
                    private String name;
                    public void help() {}
                }
                """);

        var indexed = buildIndex(file);
        var deps = indexed.getDependencies("Helper");
        assertTrue(deps.isPresent());
        assertTrue(deps.get().constructorDependencies().isEmpty());
        assertEquals(1, deps.get().fieldDependencies().size());
    }

    @Test
    void classNotFound_returnsEmpty() throws Exception {
        Path file = tempDir.resolve("Dummy.java");
        Files.writeString(file, "public class Dummy {}");

        var indexed = buildIndex(file);
        assertTrue(indexed.getDependencies("NonExistent").isEmpty());
    }

    @Test
    void genericFields_typeStripped() throws Exception {
        Path file = tempDir.resolve("Container.java");
        Files.writeString(file, """
                package com.app;
                import java.util.List;
                import java.util.Map;
                public class Container {
                    private List<String> items;
                    private Map<String, Integer> counts;
                    public void run() {}
                }
                """);

        var indexed = buildIndex(file);
        var deps = indexed.getDependencies("Container");
        assertTrue(deps.isPresent());
        var fields = deps.get().fieldDependencies();
        assertEquals(2, fields.size());
        // Generics should be stripped
        assertTrue(fields.stream().anyMatch(f -> f.type().equals("List")));
        assertTrue(fields.stream().anyMatch(f -> f.type().equals("Map")));
    }

    private IndexedCodebase buildIndex(Path file) throws Exception {
        var files = List.of(file);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        return IndexedCodebase.tryLoad(tempDir, files);
    }
}
