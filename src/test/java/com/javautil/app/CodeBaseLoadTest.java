package com.javautil.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javautil.app.codebase.CodeBaseLoader;

import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CodeBaseLoadTest {
    
    private CodeBaseLoader codeBaseLoad;
    
    @BeforeEach
    void setUp() {
        codeBaseLoad = new CodeBaseLoader();
    }
    
    @Test
    @DisplayName("Debería encontrar archivos .java en una estructura de directorios")
    void shouldFindJavaFilesInDirectoryStructure(@TempDir Path tempDir) throws IOException {
        // Crear estructura de archivos de prueba
        Path srcDir = tempDir.resolve("src");
        Path mainDir = srcDir.resolve("main");
        Path javaDir = mainDir.resolve("java");
        Path testDir = srcDir.resolve("test");
        
        Files.createDirectories(javaDir);
        Files.createDirectories(testDir);
        
        // Crear archivos .java
        Path javaFile1 = javaDir.resolve("Main.java");
        Path javaFile2 = javaDir.resolve("Utils.java");
        Path javaFile3 = testDir.resolve("TestClass.java");
        
        // Crear archivos que NO son .java
        Path textFile = javaDir.resolve("config.txt");
        Path xmlFile = javaDir.resolve("config.xml");
        
        Files.writeString(javaFile1, "public class Main {}");
        Files.writeString(javaFile2, "public class Utils {}");
        Files.writeString(javaFile3, "public class TestClass {}");
        Files.writeString(textFile, "config data");
        Files.writeString(xmlFile, "<config></config>");
        
        // Ejecutar búsqueda
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        
        // Verificaciones
        assertEquals(3, javaFiles.size(), "Debería encontrar exactamente 3 archivos .java");
        assertTrue(javaFiles.contains(javaFile1), "Debería incluir Main.java");
        assertTrue(javaFiles.contains(javaFile2), "Debería incluir Utils.java");
        assertTrue(javaFiles.contains(javaFile3), "Debería incluir TestClass.java");
        assertFalse(javaFiles.contains(textFile), "NO debería incluir archivos .txt");
        assertFalse(javaFiles.contains(xmlFile), "NO debería incluir archivos .xml");
    }
    
    @Test
    @DisplayName("Debería manejar directorio vacío correctamente")
    void shouldHandleEmptyDirectory(@TempDir Path tempDir) {
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        
        assertEquals(0, javaFiles.size(), "Debería retornar lista vacía para directorio sin archivos .java");
    }
    
    @Test
    @DisplayName("Debería manejar ruta inexistente correctamente")
    void shouldHandleNonExistentPath() {
        String nonExistentPath = "/ruta/que/no/existe/12345";
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(nonExistentPath, "java");
        
        assertEquals(0, javaFiles.size(), "Debería retornar lista vacía para ruta inexistente");
    }
    
    @Test
    @DisplayName("Debería encontrar archivos .java en subdirectorios anidados")
    void shouldFindJavaFilesInNestedSubdirectories(@TempDir Path tempDir) throws IOException {
        // Crear estructura anidada
        Path deepDir = tempDir.resolve("a").resolve("b").resolve("c").resolve("d");
        Files.createDirectories(deepDir);
        
        Path javaFile = deepDir.resolve("DeepClass.java");
        Files.writeString(javaFile, "public class DeepClass {}");
        
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        
        assertEquals(1, javaFiles.size(), "Debería encontrar el archivo .java en subdirectorio profundo");
        assertTrue(javaFiles.contains(javaFile), "Debería incluir el archivo en subdirectorio anidado");
    }
    
    @Test
    @DisplayName("Debería filtrar correctamente solo archivos .java")
    void shouldFilterOnlyJavaFiles(@TempDir Path tempDir) throws IOException {
        // Crear archivos con extensiones similares
        Path javaFile = tempDir.resolve("Test.java");
        Path javaFileUpperCase = tempDir.resolve("Test.JAVA");
        Path javaFileMixed = tempDir.resolve("Test.Java");
        Path javacFile = tempDir.resolve("Test.javac");
        Path javaBackup = tempDir.resolve("Test.java.bak");
        Path javaInName = tempDir.resolve("javaTest.txt");
        
        Files.writeString(javaFile, "public class Test {}");
        Files.writeString(javaFileUpperCase, "public class Test {}");
        Files.writeString(javaFileMixed, "public class Test {}");
        Files.writeString(javacFile, "compiled file");
        Files.writeString(javaBackup, "backup file");
        Files.writeString(javaInName, "text file");
        
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        
        assertEquals(1, javaFiles.size(), "Solo debería encontrar archivos que terminen exactamente en .java");
        assertTrue(javaFiles.contains(javaFile), "Debería incluir archivo con extensión .java");
        assertFalse(javaFiles.contains(javaFileUpperCase), "NO debería incluir archivo con extensión .JAVA");
        assertFalse(javaFiles.contains(javaFileMixed), "NO debería incluir archivo con extensión .Java");
        assertFalse(javaFiles.contains(javacFile), "NO debería incluir archivo con extensión .javac");
        assertFalse(javaFiles.contains(javaBackup), "NO debería incluir archivo con extensión .java.bak");
        assertFalse(javaFiles.contains(javaInName), "NO debería incluir archivo con 'java' en el nombre");
    }
    
    @Test
    @DisplayName("Debería limpiar la lista de archivos Java correctamente")
    void shouldClearJavaFilesList(@TempDir Path tempDir) throws IOException {
        // Crear un archivo .java
        Path javaFile = tempDir.resolve("Test.java");
        Files.writeString(javaFile, "public class Test {}");
        
        // Encontrar archivos
        List<Path> javaFiles = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        assertEquals(1, javaFiles.size(), "Debería encontrar 1 archivo .java");
        
        // Limpiar lista
        codeBaseLoad.clearJavaFiles();
        List<Path> emptyList = codeBaseLoad.getFilesPath();
        
        assertEquals(0, emptyList.size(), "La lista debería estar vacía después de limpiar");
    }
    
    @Test
    @DisplayName("Debería retornar la lista correcta de archivos Java")
    void shouldReturnCorrectJavaFilesList(@TempDir Path tempDir) throws IOException {
        // Crear archivos .java
        Path javaFile1 = tempDir.resolve("Class1.java");
        Path javaFile2 = tempDir.resolve("Class2.java");
        
        Files.writeString(javaFile1, "public class Class1 {}");
        Files.writeString(javaFile2, "public class Class2 {}");
        
        // Encontrar archivos
        codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        
        // Obtener lista
        List<Path> javaFiles = codeBaseLoad.getFilesPath();
        
        assertEquals(2, javaFiles.size(), "Debería retornar 2 archivos .java");
        assertTrue(javaFiles.contains(javaFile1), "Debería incluir Class1.java");
        assertTrue(javaFiles.contains(javaFile2), "Debería incluir Class2.java");
    }
    
    @Test
    @DisplayName("Debería manejar múltiples búsquedas correctamente")
    void shouldHandleMultipleSearches(@TempDir Path tempDir) throws IOException {
        // Primera búsqueda
        Path javaFile1 = tempDir.resolve("First.java");
        Files.writeString(javaFile1, "public class First {}");
        
        List<Path> firstSearch = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        assertEquals(1, firstSearch.size(), "Primera búsqueda debería encontrar 1 archivo");
        
        // Segunda búsqueda en la misma instancia
        List<Path> secondSearch = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        assertEquals(2, secondSearch.size(), "Segunda búsqueda debería encontrar 2 archivos (acumulativo)");
        
        // Limpiar y hacer tercera búsqueda
        codeBaseLoad.clearJavaFiles();
        List<Path> thirdSearch = codeBaseLoad.loadFilesFrom(tempDir.toString(), "java");
        assertEquals(1, thirdSearch.size(), "Tercera búsqueda después de limpiar debería encontrar 1 archivo");
    }
}
