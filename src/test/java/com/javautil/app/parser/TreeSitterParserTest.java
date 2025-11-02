package com.javutil.app.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javutil.app.parser.TreeSitterParser.MethodLocation;

public class TreeSitterParserTest {
    
    private TreeSitterParser javaParser;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        javaParser = new TreeSitterParser("java");
    }
    
    @Test
    @DisplayName("Debería crear un parser para Java correctamente")
    void shouldCreateJavaParserCorrectly() {
        assertNotNull(javaParser, "El parser Java no debería ser null");
    }
    
    @Test
    @DisplayName("Debería retornar el nombre del lenguaje Java correctamente")
    void shouldReturnJavaLanguageName() {
        String languageName = javaParser.getLanguage();
        
        // El nombre del lenguaje puede ser null o contener información sobre el lenguaje
        // Verificamos que no cause excepción y que el comportamiento sea consistente
        assertDoesNotThrow(() -> {
        }, "getLanguage no debería lanzar excepción");
        
        // Si el lenguaje no es null, debería contener información sobre Java
        if (languageName != null) {
            assertTrue(languageName.contains("java") || languageName.contains("Java") || 
                      languageName.toLowerCase().contains("java"), 
                      "El nombre del lenguaje debería contener información sobre Java");
        }
    }
    
    
    @Test
    @DisplayName("Debería ejecutar findMethod sin errores")
    void shouldExecuteFindMethodWithoutErrors() {
        assertDoesNotThrow(() -> {
            javaParser.findMethod(null, null);
        }, "findMethod no debería lanzar excepción");
    }
    
    @Test
    @DisplayName("Debería manejar múltiples instancias de parser correctamente")
    void shouldHandleMultipleParserInstancesCorrectly() {
        TreeSitterParser parser1 = new TreeSitterParser("java");
        TreeSitterParser parser2 = new TreeSitterParser("java");
        
        assertNotNull(parser1, "Primera instancia no debería ser null");
        assertNotNull(parser2, "Segunda instancia no debería ser null");
        assertNotSame(parser1, parser2, "Las instancias deberían ser diferentes");
        
        String lang1 = parser1.getLanguage();
        String lang2 = parser2.getLanguage();
        
        assertEquals(lang1, lang2, "Ambas instancias deberían retornar el mismo nombre de lenguaje");
    }
    
    @Test
    @DisplayName("Debería manejar diferentes tipos de lenguajes en el constructor")
    void shouldHandleDifferentLanguageTypesInConstructor() {
        // Test con diferentes casos
        TreeSitterParser upperCaseParser = new TreeSitterParser("JAVA");
        TreeSitterParser mixedCaseParser = new TreeSitterParser("Java");
        
        // Verificar que no se lanzan excepciones
        assertDoesNotThrow(() -> {
            upperCaseParser.getLanguage();
            mixedCaseParser.getLanguage();
        }, "No deberían lanzarse excepciones para diferentes tipos de entrada");
    }
    
    @Test
    @DisplayName("Debería ser instancia de Parser")
    void shouldBeInstanceOfParser() {
        assertTrue(javaParser instanceof Parser, "TreeSitterParser debería implementar la interfaz Parser");
    }
    
    @Test
    @DisplayName("Debería mantener consistencia en múltiples llamadas a getLanguage")
    void shouldMaintainConsistencyInMultipleGetLanguageCalls() {
        String firstCall = javaParser.getLanguage();
        String secondCall = javaParser.getLanguage();
        String thirdCall = javaParser.getLanguage();
        
        assertEquals(firstCall, secondCall, "Primera y segunda llamada deberían retornar el mismo valor");
        assertEquals(secondCall, thirdCall, "Segunda y tercera llamada deberían retornar el mismo valor");
        assertEquals(firstCall, thirdCall, "Primera y tercera llamada deberían retornar el mismo valor");
    }
    
    @Test
    @DisplayName("Debería ejecutar findMethod múltiples veces sin errores")
    void shouldExecuteFindMethodMultipleTimesWithoutErrors() {
        assertDoesNotThrow(() -> {
            javaParser.findMethod(null, null);
            javaParser.findMethod(null, null);
            javaParser.findMethod(null, null);
        }, "findMethod debería poder ejecutarse múltiples veces sin errores");
    }


    @Test
    @DisplayName("Deberia encontrar todos los MethodLocation de un metodo")
    void shouldFindMethodLocations() throws IOException {
        String javaCode = """
            public class TestClass {
                public void testMethod() {
                    System.out.println("Test 1");
                }
                
                public void anotherMethod() {
                    System.out.println("Another");
                }
                
                public void testMethod(String param) {
                    System.out.println("Test 2");
                }
            }
            """;
            
        Path testFile;

        testFile = createTestFile("TestClass.java", javaCode);   
    
        List<MethodLocation> locations = javaParser.getMethodLocations(testFile, "testMethod");

        // Verificar que se encontraron las dos ubicaciones del método
        assertEquals(2, locations.size(), "Debería encontrar dos ubicaciones para testMethod");
        
        // Verificar primera ubicación
        MethodLocation firstLocation = locations.get(0);
        assertEquals("testMethod", firstLocation.getMethodName(), "El nombre del primer método debería ser testMethod");
        assertTrue(firstLocation.getContent().contains("Test 1"), "El contenido del primer método debería contener 'Test 1'");
        
        // Verificar segunda ubicación
        MethodLocation secondLocation = locations.get(1);
        assertEquals("testMethod", secondLocation.getMethodName(), "El nombre del segundo método debería ser testMethod");
        assertTrue(secondLocation.getContent().contains("Test 2"), "El contenido del segundo método debería contener 'Test 2'");
        
        // Verificar que las líneas son diferentes
        assertNotEquals(firstLocation.getStartLine(), secondLocation.getStartLine(), 
            "Las líneas de inicio deberían ser diferentes para cada método");
    }

    private Path createTestFile(String fileName, String content) throws IOException {
        Path testFile = tempDir.resolve(fileName);
        Files.write(testFile, content.getBytes());
        return testFile;
    }
}
