package com.javautil.app.codebase;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class CodeBaseLoader {
    
    private List<Path> filesPath;
    
    public CodeBaseLoader(){
        this.filesPath = new ArrayList<>();
    }
    
    /**
     * Recorre una estructura de archivos y filtra solo los archivos .java
     * @param rootPath La ruta raíz desde donde comenzar el recorrido
     * @return Lista de rutas de archivos .java encontrados
     */
    public List<Path> loadFilesFrom(String rootPath, String extension) {
        try {
            Path startPath = Paths.get(rootPath);
            if (!Files.exists(startPath)) {
                return filesPath;
            }
            
            Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith("."+extension)) {
                        filesPath.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    System.err.println("Error al acceder al archivo: " + file + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            
        } catch (IOException e) {
            System.err.println("Error al recorrer la estructura de archivos: " + e.getMessage());
        }
        
        return filesPath;
    }
    
    /**
     * Obtiene la lista de archivos Java encontrados
     * @return Lista de rutas de archivos .java
     */
    public List<Path> getFilesPath() {
        return filesPath;
    }
    
    /**
     * Limpia la lista de archivos Java
     */
    public void clearJavaFiles() {
        filesPath.clear();
    }
}
