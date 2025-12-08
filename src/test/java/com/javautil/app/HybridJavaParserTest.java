package com.javautil.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import com.javautil.app.parser.HybridJavaParser;
import com.javautil.app.parser.JParser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class HybridJavaParserTest {
    
    private HybridJavaParser hybridParser;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        hybridParser = new HybridJavaParser();
    }
    
    @Test
    @DisplayName("Debería crear un parser híbrido correctamente")
    void shouldCreateHybridParserCorrectly() {
        assertNotNull(hybridParser, "El parser híbrido no debería ser null");
        assertTrue(hybridParser instanceof JParser, "HybridJavaParser debería implementar la interfaz Parser");
    }
    
    @Test
    @DisplayName("Debería manejar archivo no existente sin errores")
    void shouldHandleNonExistentFileWithoutErrors() {
        Path nonExistentFile = tempDir.resolve("NoExiste.java");
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(nonExistentFile, "testMethod");
        }, "findMethod no debería lanzar excepción para archivo inexistente");
    }
    
    @Test
    @DisplayName("Debería tirar excepcion con parametros nulos")
    void shouldHandleNullParametersWithoutErrors() {
        assertThrows(Exception.class, () -> {
            hybridParser.findMethod(null, null);
        }, "findMethod debería lanzar excepción con parámetros null");
    }
    
    @Test
    @DisplayName("Debería encontrar método simple sin parámetros")
    void shouldFindSimpleMethodWithoutParameters() throws IOException {
        // Crear archivo de prueba
        String javaCode = """
            public class TestClass {
                public void simpleMethod() {
                    System.out.println("Hello World");
                }
                
                public int anotherMethod() {
                    return 42;
                }
            }
            """;
        
        Path testFile = createTestFile("TestClass.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "simpleMethod");
        }, "Debería encontrar método simple sin errores");
    }
    
    @Test
    @DisplayName("Debería encontrar método con parámetros usando findMethodWithParameters")
    void shouldFindMethodWithParametersUsingSpecificMethod() throws IOException {
        String javaCode = """
            public class TestClass {
                public void calculate(int number, String text) {
                    System.out.println("Calculating: " + number + " " + text);
                }
                
                public void calculate(double value) {
                    System.out.println("Calculating: " + value);
                }
                
                public void calculate() {
                    System.out.println("Calculating without parameters");
                }
            }
            """;
        
        Path testFile = createTestFile("TestClass.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethodWithParameters(testFile, "calculate");
            hybridParser.findMethodWithParameters(testFile, "calculate", "int", "String");
            hybridParser.findMethodWithParameters(testFile, "calculate", "double");
        }, "Debería encontrar métodos sobrecargados sin errores");
    }
    
    @Test
    @DisplayName("Debería manejar clase con múltiples métodos del mismo nombre")
    void shouldHandleClassWithMultipleMethodsOfSameName() throws IOException {
        String javaCode = """
            public class Calculator {
                private int value;
                
                public Calculator() {
                    this.value = 0;
                }
                
                public void add(int number) {
                    this.value += number;
                }
                
                public void add(double number) {
                    this.value += (int) number;
                }
                
                public void add(int a, int b) {
                    this.value += (a + b);
                }
                
                public int getValue() {
                    return this.value;
                }
            }
            """;
        
        Path testFile = createTestFile("Calculator.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "add");
            hybridParser.findMethodWithParameters(testFile, "add", "int");
            hybridParser.findMethodWithParameters(testFile, "add", "double");
            hybridParser.findMethodWithParameters(testFile, "add", "int", "int");
        }, "Debería manejar métodos sobrecargados correctamente");
    }

    @Test
    @DisplayName("Deberia manejar multiples clases con nombres iguales")
    void shouldHandleMultiplesClassesWithMethodsOfSameName() throws IOException {
        String javaCode_class1 = """
            public class Calculator {
                private int value;
                
                public Calculator() {
                    this.value = 0;
                }
                
                public void add(int number) {
                    this.value += number;
                }
                
                public void add(double number) {
                    this.value += (int) number;
                }
                
                public void add(int a, int b) {
                    this.value += (a + b);
                }
                
                public int getValue() {
                    return this.value;
                }
            }
            """;

            String javaCode_class2 = """
                public class SuperCalculator {
                    private int value;
                    
                    public Calculator() {
                        this.value = 0;
                    }
                    
                    public void add(int number) {
                        this.value += number;
                    }
                    
                    public void add(double number) {
                        this.value += (int) number;
                    }
                    
                    public void add(int a, int b) {
                        this.value += (a + b);
                    }
                    
                    public int getValue() {
                        return this.value;
                    }
                }
                """;
        
        Path testFileCalculator = createTestFile("Calculator.java", javaCode_class1);
        Path testFileSuperCalculator = createTestFile("SuperCalculator.java", javaCode_class2);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFileCalculator, "add");
            hybridParser.findMethodWithParameters(testFileCalculator, "add", "int");
            hybridParser.findMethodWithParameters(testFileCalculator, "add", "double");
            hybridParser.findMethodWithParameters(testFileCalculator, "add", "int", "int");
        }, "Debería manejar métodos sobrecargados correctamente");
    }
    
    @Test
    @DisplayName("Debería manejar archivo Java con sintaxis compleja")
    void shouldHandleJavaFileWithComplexSyntax() throws IOException {
        String javaCode = """
            package com.example.test;
            
            import java.util.List;
            import java.util.ArrayList;
            import java.util.Map;
            
            public class ComplexClass extends BaseClass implements TestInterface {
                private static final String CONSTANT = "TEST";
                
                @Override
                public void process(List<String> items, Map<String, Object> config) {
                    // Método complejo con generics
                    for (String item : items) {
                        System.out.println(item);
                    }
                }
                
                public static <T> T transform(T input, Function<T, T> transformer) {
                    return transformer.apply(input);
                }
                
                protected synchronized void synchronizedMethod() throws Exception {
                    throw new Exception("Test exception");
                }
            }
            """;
        
        Path testFile = createTestFile("ComplexClass.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "process");
            hybridParser.findMethod(testFile, "transform");
            hybridParser.findMethod(testFile, "synchronizedMethod");
        }, "Debería manejar sintaxis compleja sin errores");
    }
    
    @Test
    @DisplayName("Debería manejar archivo vacío sin errores")
    void shouldHandleEmptyFileWithoutErrors() throws IOException {
        Path emptyFile = createTestFile("Empty.java", "");
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(emptyFile, "anyMethod");
        }, "Debería manejar archivo vacío sin errores");
    }
    
    @Test
    @DisplayName("Debería manejar archivo con solo comentarios")
    void shouldHandleFileWithOnlyComments() throws IOException {
        String javaCode = """
            /*
             * Este es un archivo de prueba
             * Solo tiene comentarios
             */
            
            // Comentario de línea
            /* Otro comentario */
            """;
        
        Path testFile = createTestFile("OnlyComments.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "anyMethod");
        }, "Debería manejar archivo con solo comentarios sin errores");
    }
    
    @Test
    @DisplayName("Debería manejar múltiples clases en un archivo")
    void shouldHandleMultipleClassesInFile() throws IOException {
        String javaCode = """
            class FirstClass {
                public void firstMethod() {
                    System.out.println("First");
                }
            }
            
            class SecondClass {
                public void firstMethod() {
                    System.out.println("Second");
                }
                
                public void secondMethod() {
                    System.out.println("Second method");
                }
            }
            
            public class MainClass {
                public void firstMethod() {
                    System.out.println("Main");
                }
            }
            """;
        
        Path testFile = createTestFile("MultipleClasses.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "firstMethod");
            hybridParser.findMethod(testFile, "secondMethod");
        }, "Debería manejar múltiples clases sin errores");
    }
    
    @Test
    @DisplayName("Debería ejecutar múltiples búsquedas consecutivas")
    void shouldExecuteMultipleConsecutiveSearches() throws IOException {
        String javaCode = """
            public class TestClass {
                public void method1() {}
                public void method2(int param) {}
                public void method3(String a, int b) {}
            }
            """;
        
        Path testFile = createTestFile("TestClass.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "method1");
            hybridParser.findMethod(testFile, "method2");
            hybridParser.findMethod(testFile, "method3");
            hybridParser.findMethodWithParameters(testFile, "method2", "int");
            hybridParser.findMethodWithParameters(testFile, "method3", "String", "int");
        }, "Debería ejecutar múltiples búsquedas consecutivas sin errores");
    }
    
    @Test
    @DisplayName("Debería manejar método no encontrado sin errores")
    void shouldHandleMethodNotFoundWithoutErrors() throws IOException {
        String javaCode = """
            public class TestClass {
                public void existingMethod() {
                    System.out.println("I exist");
                }
            }
            """;
        
        Path testFile = createTestFile("TestClass.java", javaCode);
        
        assertDoesNotThrow(() -> {
            hybridParser.findMethod(testFile, "nonExistentMethod");
            hybridParser.findMethodWithParameters(testFile, "nonExistentMethod", "int", "String");
        }, "Debería manejar método no encontrado sin errores");
    }
    
    /**
     * Helper method para crear archivos de prueba
     */
    private Path createTestFile(String fileName, String content) throws IOException {
        Path testFile = tempDir.resolve(fileName);
        Files.write(testFile, content.getBytes());
        return testFile;
    }
        
    
}  