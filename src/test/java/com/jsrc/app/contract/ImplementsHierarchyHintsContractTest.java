package com.jsrc.app.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.navigate.HierarchyCommand;
import com.jsrc.app.command.navigate.ImplementsCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

/**
 * Contract tests for issue #36: implements/hierarchy nextCommands hints must cite
 * concrete list entries (simple names) not the interface/query target.
 * <p>
 * Acceptance cases:
 * A1: implements with non-empty implementors → hint uses implementor simple name
 * A2: implements with empty implementors → no misleading hint
 * A3: hierarchy with non-empty subClasses → hint uses subclass simple name
 */
class ImplementsHierarchyHintsContractTest {

    @TempDir
    Path tempDir;

    private HybridJavaParser parser;

    @BeforeEach
    void setUp() {
        parser = new HybridJavaParser();
    }

    @Test
    @DisplayName("A1: implements with implementors → nextCommands[0] hints read <implementorSimpleName>")
    void implementsHintsImplementorSimpleName() throws Exception {
        Path ifaceFile = tempDir.resolve("MyService.java");
        Files.writeString(ifaceFile, """
                package com.example;
                public interface MyService {
                    void execute();
                }
                """);

        Path implFile = tempDir.resolve("DefaultService.java");
        Files.writeString(implFile, """
                package com.example.impl;
                public class DefaultService implements com.example.MyService {
                    public void execute() {}
                }
                """);

        var result = executeImplements("MyService", List.of(ifaceFile, implFile));
        
        @SuppressWarnings("unchecked")
        List<Object> implementors = (List<Object>) result.get("implementors");
        assertNotNull(implementors, "implementors list must be present");
        assertFalse(implementors.isEmpty(), "Should have at least one implementor");

        @SuppressWarnings("unchecked")
        List<Object> hints = (List<Object>) result.get("nextCommands");
        assertNotNull(hints, "nextCommands must be present");
        assertFalse(hints.isEmpty(), "Should have at least one hint");

        @SuppressWarnings("unchecked")
        Map<String, Object> firstHint = (Map<String, Object>) hints.get(0);
        String command = (String) firstHint.get("command");
        
        assertTrue(command.startsWith("read "), "First hint command should start with 'read '");
        String hintedClass = command.substring(5).trim();
        
        assertNotEquals("MyService", hintedClass, 
            "Hint must NOT cite the interface name (MyService)");
        
        boolean isImplementorSimpleName = false;
        for (Object impl : implementors) {
            String fqcn = (String) impl;
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            if (hintedClass.equals(simpleName)) {
                isImplementorSimpleName = true;
                break;
            }
        }
        assertTrue(isImplementorSimpleName, 
            "Hint must cite a simple name from implementors list, got: " + hintedClass);
    }

    @Test
    @DisplayName("A2: implements with empty implementors → no misleading 'Read an implementor' hint")
    void implementsEmptyImplementorsNoMisleadingHint() throws Exception {
        Path ifaceFile = tempDir.resolve("UnusedInterface.java");
        Files.writeString(ifaceFile, """
                package com.example;
                public interface UnusedInterface {
                    void unused();
                }
                """);

        var result = executeImplements("UnusedInterface", List.of(ifaceFile));
        
        @SuppressWarnings("unchecked")
        List<Object> implementors = (List<Object>) result.get("implementors");
        assertTrue(implementors == null || implementors.isEmpty(), 
            "Should have no implementors");

        @SuppressWarnings("unchecked")
        List<Object> hints = (List<Object>) result.get("nextCommands");
        
        if (hints != null && !hints.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstHint = (Map<String, Object>) hints.get(0);
            String command = (String) firstHint.get("command");
            String description = (String) firstHint.get("description");
            
            if (command.startsWith("read ")) {
                String hintedClass = command.substring(5).trim();
                assertNotEquals("UnusedInterface", hintedClass,
                    "Must not hint 'read UnusedInterface' (the interface) when no implementors");
            }
            
            assertFalse(description != null && description.toLowerCase().contains("read an implementor"),
                "Description must not claim 'Read an implementor' when list is empty");
        }
    }

    @Test
    @DisplayName("A3: hierarchy with subClasses → nextCommands hints read <subclassSimpleName>")
    void hierarchyHintsSubclassSimpleName() throws Exception {
        Path baseFile = tempDir.resolve("BaseClass.java");
        Files.writeString(baseFile, """
                package com.example;
                public class BaseClass {
                    public void baseMethod() {}
                }
                """);

        Path subFile = tempDir.resolve("SubClass.java");
        Files.writeString(subFile, """
                package com.example.sub;
                public class SubClass extends com.example.BaseClass {
                    public void subMethod() {}
                }
                """);

        var result = executeHierarchy("BaseClass", List.of(baseFile, subFile));
        
        @SuppressWarnings("unchecked")
        List<Object> subClasses = (List<Object>) result.get("subClasses");
        assertNotNull(subClasses, "subClasses list must be present");
        assertFalse(subClasses.isEmpty(), "Should have at least one subclass");

        @SuppressWarnings("unchecked")
        List<Object> hints = (List<Object>) result.get("nextCommands");
        assertNotNull(hints, "nextCommands must be present");
        assertFalse(hints.isEmpty(), "Should have at least one hint");

        @SuppressWarnings("unchecked")
        Map<String, Object> firstHint = (Map<String, Object>) hints.get(0);
        String command = (String) firstHint.get("command");
        
        assertTrue(command.startsWith("read "), "First hint command should start with 'read '");
        String hintedClass = command.substring(5).trim();
        
        boolean isSubclassSimpleName = false;
        for (Object sub : subClasses) {
            String fqcn = (String) sub;
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            if (hintedClass.equals(simpleName)) {
                isSubclassSimpleName = true;
                break;
            }
        }
        
        if (!isSubclassSimpleName && !subClasses.isEmpty()) {
            String target = (String) result.get("target");
            String targetSimple = target.substring(target.lastIndexOf('.') + 1);
            boolean targetInSubclasses = subClasses.contains(target);
            
            if (!targetInSubclasses || !hintedClass.equals(targetSimple)) {
                fail("Hint must cite a simple name from subClasses list (not the query target unless it's also a subclass), got: " + hintedClass);
            }
        }
    }

    private Map<String, Object> executeImplements(String ifaceName, List<Path> files) throws Exception {
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        
        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, null, new PrintStream(out));
        var ctx = new CommandContext(files, tempDir.toString(), null, formatter, indexed, parser);

        new ImplementsCommand(ifaceName).execute(ctx);

        String json = out.toString().trim();
        assertFalse(json.isEmpty(), "Command should produce JSON output");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(json);
        assertNotNull(result, "JSON should parse to a map");
        return result;
    }

    private Map<String, Object> executeHierarchy(String className, List<Path> files) throws Exception {
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        
        var out = new ByteArrayOutputStream();
        var formatter = new JsonFormatter(false, null, new PrintStream(out));
        var ctx = new CommandContext(files, tempDir.toString(), null, formatter, indexed, parser);

        new HierarchyCommand(className).execute(ctx);

        String json = out.toString().trim();
        assertFalse(json.isEmpty(), "Command should produce JSON output");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(json);
        assertNotNull(result, "JSON should parse to a map");
        return result;
    }
}
