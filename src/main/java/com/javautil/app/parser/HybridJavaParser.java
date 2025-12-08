package com.javautil.app.parser;

import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ParseResult;
import com.javautil.app.parser.TreeSitterParser.MethodLocation;

public class HybridJavaParser implements JParser{

    private static final Logger logger = LoggerFactory.getLogger(HybridJavaParser.class);

    private TreeSitterParser ts;
    private JavaParser jp;

    public HybridJavaParser(){
        this.ts = new TreeSitterParser("java");
        this.jp = new JavaParser();
    }

    @Override
    public void findMethod(Path path, String method) {
        logger.debug("Iniciando búsqueda híbrida para método: {} en archivo: {}", method, path.getFileName());
        
        // Paso 1: Usar TreeSitter para encontrar rápidamente ubicaciones por nombre
        List<MethodLocation> locations = null;//ts.getMethodLocations(path, method);
        
        if (locations.isEmpty()) {
            logger.debug("No se encontraron métodos con nombre '{}' usando TreeSitter", method);
            return;
        }
        
        logger.debug("TreeSitter encontró {} ubicaciones para el método '{}'", locations.size(), method);
        
        // Paso 2: Usar JavaParser para análisis detallado de cada ubicación
        try {
            String fullContent = Files.readString(path);
            ParseResult<CompilationUnit> parseResult = jp.parse(fullContent);
            
            if (!parseResult.isSuccessful()) {
                logger.error("JavaParser no pudo parsear el archivo: {}", path);
                return;
            }
            
            CompilationUnit cu = parseResult.getResult().get();
            
            // Analizar cada ubicación encontrada por TreeSitter
            for (MethodLocation location : locations) {
                analyzeMethodAtLocation(cu, location, path);
            }
            
        } catch (IOException ex) {
            logger.error("Error al leer archivo para JavaParser: {}", ex.getMessage(), ex);
        }
    }
    
    /**
     * Analiza un método específico en una ubicación usando JavaParser
     */
    private void analyzeMethodAtLocation(CompilationUnit cu, MethodLocation location, Path path) {
        // Buscar el método en JavaParser que coincida con la ubicación de TreeSitter
        Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
            .filter(method -> {
                // Verificar que el nombre coincida
                if (!method.getNameAsString().equals(location.getMethodName())) {
                    return false;
                }
                
                // Verificar que la ubicación sea aproximadamente la misma
                if (method.getBegin().isPresent()) {
                    int jpStartLine = method.getBegin().get().line;
                    // Permitir una pequeña diferencia en líneas debido a diferencias de parsing
                    return Math.abs(jpStartLine - location.getStartLine()) <= 2;
                }
                return false;
            })
            .findFirst();
            
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();
            logDetailedMethodInfo(method, location, path);
        } else {
            logger.warn("JavaParser no pudo encontrar el método en la ubicación esperada. TreeSitter línea: {}", location.getStartLine());
        }
    }
    
    /**
     * Registra información detallada del método usando JavaParser
     */
    private void logDetailedMethodInfo(MethodDeclaration method, MethodLocation location, Path path) {
        StringBuilder info = new StringBuilder();
        info.append("✓ Método encontrado: ");
        
        // Modificadores
        if (!method.getModifiers().isEmpty()) {
            method.getModifiers().forEach(mod -> info.append(mod.getKeyword().asString()).append(" "));
        }
        
        // Tipo de retorno
        info.append(method.getTypeAsString()).append(" ");
        
        // Nombre
        info.append(method.getNameAsString());
        
        // Parámetros detallados
        info.append("(");
        List<Parameter> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter param = parameters.get(i);
            info.append(param.getTypeAsString()).append(" ").append(param.getNameAsString());
            if (i < parameters.size() - 1) {
                info.append(", ");
            }
        }
        info.append(")");
        
        // Información adicional
        logger.debug("{}", info.toString());
        logger.debug("   └── Archivo: {}. Línea: {} (TreeSitter: {})", 
                   path.getFileName().toString(), 
                   method.getBegin().map(pos -> pos.line).orElse(-1),
                   location.getStartLine());
        logger.debug("   └── Parámetros: {} | Modificadores: {} | Tipo retorno: {}", 
                   parameters.size(),
                   method.getModifiers().size(),
                   method.getTypeAsString());
    }
    
    /**
     * Busca métodos con parámetros específicos
     */
    public void findMethodWithParameters(Path path, String methodName, String... parameterTypes) {
        logger.debug("Búsqueda híbrida con parámetros específicos: {} con tipos [{}]", 
                   methodName, String.join(", ", parameterTypes));
        
        // Paso 1: TreeSitter para ubicaciones rápidas
        List<MethodLocation> locations = null;//ts.getMethodLocations(path, methodName);
        
        if (locations.isEmpty()) {
            logger.debug("No se encontraron métodos con nombre '{}'", methodName);
            return;
        }
        
        // Paso 2: JavaParser para coincidencia exacta de parámetros
        try {
            String fullContent = Files.readString(path);
            ParseResult<CompilationUnit> parseResult = jp.parse(fullContent);
            
            if (!parseResult.isSuccessful()) {
                logger.error("JavaParser no pudo parsear el archivo: {}", path);
                return;
            }
            
            CompilationUnit cu = parseResult.getResult().get();
            
            for (MethodLocation location : locations) {
                findExactMethodMatch(cu, location, path, parameterTypes);
            }
            
        } catch (IOException ex) {
            logger.error("Error al leer archivo: {}", ex.getMessage(), ex);
        }
    }
    
    /**
     * Busca coincidencia exacta de método con parámetros específicos
     */
    private void findExactMethodMatch(CompilationUnit cu, MethodLocation location, Path path, String[] expectedParamTypes) {
        Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
            .filter(method -> {
                // Verificar nombre
                if (!method.getNameAsString().equals(location.getMethodName())) {
                    return false;
                }
                
                // Verificar parámetros exactos
                List<Parameter> params = method.getParameters();
                if (params.size() != expectedParamTypes.length) {
                    return false;
                }
                
                for (int i = 0; i < params.size(); i++) {
                    if (!params.get(i).getTypeAsString().equals(expectedParamTypes[i])) {
                        return false;
                    }
                }
                
                return true;
            })
            .findFirst();
            
        if (methodOpt.isPresent()) {
            MethodDeclaration method = methodOpt.get();
            logger.debug("✓ Coincidencia exacta encontrada: {} en línea {}", 
                       buildMethodSignature(method), 
                       method.getBegin().map(pos -> pos.line).orElse(-1));
        }
    }
    
    /**
     * Construye la signatura completa del método
     */
    private String buildMethodSignature(MethodDeclaration method) {
        StringBuilder signature = new StringBuilder();
        
        // Modificadores
        method.getModifiers().forEach(mod -> signature.append(mod.getKeyword().asString()).append(" "));
        
        // Tipo retorno
        signature.append(method.getTypeAsString()).append(" ");
        
        // Nombre
        signature.append(method.getNameAsString()).append("(");
        
        // Parámetros
        List<Parameter> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            signature.append(params.get(i).getTypeAsString());
            if (i < params.size() - 1) {
                signature.append(", ");
            }
        }
        signature.append(")");
        
        return signature.toString();
    }
}
