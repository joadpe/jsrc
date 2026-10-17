package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

class TreeSitterParserTest {

    private TreeSitterParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new TreeSitterParser("java");
    }

    @Test
    @DisplayName("Should report correct language")
    void shouldReportLanguage() {
        assertEquals("java", parser.getLanguage());
    }

    @Test
    @DisplayName("Should implement CodeParser")
    void shouldImplementCodeParser() {
        assertInstanceOf(CodeParser.class, parser);
    }

    @Test
    @DisplayName("Should reject null/blank language")
    void shouldRejectInvalidLanguage() {
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser(null));
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser(""));
        assertThrows(IllegalArgumentException.class, () -> new TreeSitterParser("cobol"));
    }

    @Test
    @DisplayName("Should return empty for null/missing path or blank method")
    void shouldReturnEmptyForInvalidInput() {
        assertTrue(parser.findMethods(null, "test").isEmpty());
        assertTrue(parser.findMethods(tempDir.resolve("nope.java"), "test").isEmpty());
    }

    @Test
    @DisplayName("Should find a single method with basic info")
    void shouldFindSingleMethod() throws IOException {
        Path file = writeFile("Hello.java", """
                public class Hello {
                    public void greet() {
                        System.out.println("Hi");
                    }
                }
                """);

        List<MethodInfo> results = parser.findMethods(file, "greet");
        assertEquals(1, results.size());

        MethodInfo m = results.getFirst();
        assertEquals("greet", m.name());
        assertEquals("Hello", m.className());
        assertEquals("void", m.returnType());
        assertTrue(m.modifiers().contains("public"));
        assertTrue(m.annotations().isEmpty(), "TreeSitter should not extract annotations");
        assertTrue(m.thrownExceptions().isEmpty(), "TreeSitter should not extract throws");
        assertNull(m.javadoc(), "TreeSitter should not extract javadoc");
    }

    @Test
    @DisplayName("Should find overloaded methods and filter by param types")
    void shouldFilterByParameterTypes() throws IOException {
        Path file = writeFile("Calc.java", """
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                    public double add(double a, double b) { return a + b; }
                }
                """);

        assertEquals(2, parser.findMethods(file, "add").size());
        assertEquals(1, parser.findMethods(file, "add", List.of("int", "int")).size());
        assertEquals(1, parser.findMethods(file, "add", List.of("double", "double")).size());
        assertTrue(parser.findMethods(file, "add", List.of("String")).isEmpty());
    }

    @Test
    @DisplayName("Should find all methods in a file")
    void shouldFindAllMethods() throws IOException {
        Path file = writeFile("Multi.java", """
                public class Multi {
                    public void a() {}
                    private int b() { return 1; }
                    protected String c(String x) { return x; }
                }
                """);

        List<MethodInfo> all = parser.findAllMethods(file);
        assertEquals(3, all.size());
    }

    @Test
    @DisplayName("Should parse classes with basic info")
    void shouldParseClasses() throws IOException {
        Path file = writeFile("Two.java", """
                class Alpha {
                    public void doA() {}
                }
                class Beta {
                    public void doB() {}
                    public void doC() {}
                }
                """);

        List<ClassInfo> classes = parser.parseClasses(file);
        assertEquals(2, classes.size());
        assertEquals("Alpha", classes.get(0).name());
        assertEquals(1, classes.get(0).methods().size());
        assertEquals("Beta", classes.get(1).name());
        assertEquals(2, classes.get(1).methods().size());
    }

    @Test
    @DisplayName("Should match declared names only, not comments, strings, calls or longer names")
    void shouldMatchOnlyDeclaredMethodNames() throws IOException {
        Path file = writeFile("Mentions.java", """
                public class Mentions {
                    // greet is mentioned here
                    private final String label = "greet";
                    public void greeting() { greet(); }
                }
                """);

        assertTrue(parser.findMethods(file, "greet").isEmpty());
        assertEquals(List.of("greeting"),
                parser.findMethods(file, "greeting").stream().map(MethodInfo::name).toList());
    }

    @Test
    @DisplayName("Should extract modifiers, return type and parameters of a declaration")
    void shouldExtractSignatureParts() throws IOException {
        Path file = writeFile("Sig.java", """
                public class Sig {
                    @Deprecated
                    protected static <T> java.util.List<T> pick(final int count, String... names) {
                        return null;
                    }
                    void plain() {}
                }
                """);

        MethodInfo pick = parser.findMethods(file, "pick").getFirst();
        assertEquals(List.of("protected", "static"), pick.modifiers());
        assertEquals("java.util.List<T>", pick.returnType());
        // tree-sitter-java's spread_parameter has no type or name fields, and CI builds the
        // grammar from its latest commit, so only the varargs parameter's presence is fixed.
        assertEquals(2, pick.parameters().size());
        assertEquals(new MethodInfo.ParameterInfo("int", "count"), pick.parameters().getFirst());
        assertEquals(2, pick.startLine());
        assertEquals(5, pick.endLine());

        MethodInfo plain = parser.findMethods(file, "plain").getFirst();
        assertEquals(List.of(), plain.modifiers());
        assertEquals("void", plain.returnType());
        assertEquals(List.of(), plain.parameters());
    }

    @Test
    @DisplayName("Should find methods whose names are not ASCII")
    void shouldFindNonAsciiMethodName() throws IOException {
        Path file = writeFile("Tarea.java", """
                public class Tarea {
                    public void método() {}
                    public void metodo() {}
                }
                """);

        List<MethodInfo> methods = parser.findMethods(file, "método");

        assertEquals(1, methods.size());
        assertEquals("método", methods.getFirst().name());
        assertEquals(2, methods.getFirst().startLine());
    }

    @Test
    @DisplayName("Should give the same results when one parser is reused across files")
    void shouldReuseParserAcrossFiles() throws IOException {
        Path first = writeFile("First.java", """
                package demo.one;
                public class First {
                    public int size(String s) { return s.length(); }
                    static class Inner { void size() {} }
                }
                """);
        Path second = writeFile("Second.java", """
                package demo.two;
                interface Second { void size(); void clear(); }
                """);

        for (int round = 0; round < 3; round++) {
            assertEquals(List.of("First", "Inner"), parser.findMethods(first, "size").stream()
                    .map(MethodInfo::className).toList());
            assertEquals(List.of("size"), parser.findMethods(second, "size").stream()
                    .map(MethodInfo::name).toList());
            assertEquals(2, parser.findAllMethods(second).size());

            List<ClassInfo> classes = parser.parseClasses(first);
            assertEquals(List.of("First", "Inner"), classes.stream().map(ClassInfo::name).toList());
            assertEquals(List.of("demo.one", "demo.one"),
                    classes.stream().map(ClassInfo::packageName).toList());
            assertEquals(List.of(1, 1), classes.stream().map(c -> c.methods().size()).toList());

            ClassInfo iface = parser.parseClasses(second).getFirst();
            assertEquals("demo.two", iface.packageName());
            assertTrue(iface.isInterface());
            assertEquals(2, iface.methods().size());
        }
    }

    @Test
    @DisplayName("Should still locate methods in files with syntax errors")
    void shouldFindMethodsInBrokenFile() throws IOException {
        Path file = writeFile("Broken.java", """
                public class Broken {
                    public void ok() {}
                    public void broken( {
                    public int after() { return 1; }
                """);

        assertEquals(List.of("ok"), parser.findMethods(file, "ok").stream()
                .map(MethodInfo::name).toList());
        assertEquals(parser.findAllMethods(file).stream().filter(m -> m.name().equals("after")).toList(),
                parser.findMethods(file, "after"));
    }

    @Test
    @DisplayName("findMethodsByAnnotation should return empty (not supported)")
    void shouldReturnEmptyForAnnotationSearch() throws IOException {
        Path file = writeFile("Ann.java", """
                public class Ann {
                    @Override
                    public String toString() { return ""; }
                }
                """);

        assertTrue(parser.findMethodsByAnnotation(file, "Override").isEmpty());
    }

    @Test
    @DisplayName("Should handle empty file")
    void shouldHandleEmptyFile() throws IOException {
        Path file = writeFile("Empty.java", "");
        assertTrue(parser.findMethods(file, "any").isEmpty());
        assertTrue(parser.findAllMethods(file).isEmpty());
        assertTrue(parser.parseClasses(file).isEmpty());
    }

    @Test
    @DisplayName("Should read Windows-1252 sources with offsets into the parsed text")
    void shouldReadWindows1252Source() throws IOException {
        Path file = tempDir.resolve("Catalogo.java");
        Files.write(file, """
                // Catálogo, señal, acción, año
                public class Catalogo {
                    public static String estado() { return "señal"; }
                }
                """.getBytes(java.nio.charset.Charset.forName("windows-1252")));

        List<MethodInfo> methods = parser.findMethods(file, "estado");

        assertEquals(1, methods.size());
        assertEquals(List.of("public", "static"), methods.getFirst().modifiers());
        assertTrue(methods.getFirst().content().contains("return \"señal\";"));
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
