package com.jsrc.app.accuracy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexedSemanticObserverTest {

    @TempDir
    Path tempDir;

    @Test
    void observesMethodsUsingQualifiedClassAndFullSignature() throws Exception {
        Path service = writeSource("com/example/Service.java", """
                package com.example;
                public class Service {
                    public void process(String value) {}
                }
                """);

        SemanticObservation observation = IndexedSemanticObserver.observe(
                tempDir, List.of(service));

        assertEquals(Set.of("com.example.Service#process(String)"), observation.symbols());
    }

    @Test
    void observesResolvedCallEdgesUsingCanonicalMethodIdentities() throws Exception {
        Path service = writeSource("com/example/Service.java", """
                package com.example;
                public class Service {
                    public void process(String value) {}
                }
                """);
        Path client = writeSource("com/example/Client.java", """
                package com.example;
                public class Client {
                    public void run(Service service) {
                        service.process("value");
                    }
                }
                """);

        SemanticObservation observation = IndexedSemanticObserver.observe(
                tempDir, List.of(service, client));

        assertEquals(Set.of(
                "com.example.Client#run(Service)->com.example.Service#process(String)"),
                observation.edges());
    }

    @Test
    void observesMethodReferenceAsCallEdge() throws Exception {
        Path mapper = writeSource("com/example/Mapper.java", """
                package com.example;
                public interface Mapper {
                    String map(String value);
                }
                """);
        Path client = writeSource("com/example/Client.java", """
                package com.example;
                import java.util.function.Function;
                public class Client {
                    public Function<String, String> adapter(Mapper mapper) {
                        return mapper::map;
                    }
                }
                """);

        SemanticObservation observation = IndexedSemanticObserver.observe(
                tempDir, List.of(mapper, client));

        assertEquals(Set.of(
                "com.example.Client#adapter(Mapper)->com.example.Mapper#map(String)"),
                observation.edges());
    }

    private Path writeSource(String relativePath, String source) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
