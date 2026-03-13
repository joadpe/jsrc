package com.javautil.app.parser;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.Tree;

public class TreeSitterParser implements JParser{
    
    private static final Logger logger = LoggerFactory.getLogger(TreeSitterParser.class);

    /**
     * Clase para almacenar información de ubicación de métodos encontrados
     */
    public static class MethodLocation {
        private final String methodName;
        private final int startLine;
        private final int endLine;
        private final String content;
        
        public MethodLocation(String methodName, int startLine, int endLine, String content) {
            this.methodName = methodName;
            this.startLine = startLine;
            this.endLine = endLine;
            this.content = content;
        }
        
        public String getMethodName() { return methodName; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public String getContent() { return content; }
    }

    private final static String METHOD_DECLARATION_EXACT_QUERY = "(method_declaration name: (identifier) @methodName (#eq? @methodName \"%s\"))";
    //private final static String CALL_EXPRESSION_QUERY = "(call_expression function: (identifier) @methodName (#eq? @methodName \"%s\"))";
    private final static String CALL_EXPRESSION_WITH_OBJECT_AND_PARAMS_QUERY = "(call_expression function: (field_access object: (identifier) @objectName field: (identifier) @methodName) arguments: (argument_list) @arguments (#eq? @objectName \"%s\") (#eq? @methodName \"%s\"))";
    
    private Parser parser;
    private Language language;
    
    /**
     * Extrae un substring usando índices de bytes de Tree-sitter
     * Tree-sitter devuelve índices en bytes (UTF-8), no en caracteres
     */
    private String substringFromBytes(byte[] bytes, int startByte, int endByte) {
        if (startByte >= bytes.length) {
            return "";
        }
        if (endByte > bytes.length) {
            endByte = bytes.length;
        }
        return new String(bytes, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    public TreeSitterParser(String lang){
        if (lang == null) {
            throw new IllegalArgumentException("El parámetro 'lang' no puede ser null");
        }    
        
        switch (lang.toLowerCase()) {
            case "java":
                try {
                    // Cargar la biblioteca nativa desde el classpath
                    // jtreesitter empaqueta las bibliotecas nativas en el JAR
                    String libraryName = System.mapLibraryName("tree-sitter-java");
                    java.net.URL libraryUrl = null;
                    java.lang.ClassLoader cl = TreeSitterParser.class.getClassLoader();
                    
                    // Intentar buscar en diferentes ubicaciones comunes donde jtreesitter puede empaquetar las bibliotecas
                    String[] searchPaths = {
                        "native/" + libraryName,
                        "META-INF/native/" + libraryName,
                        "META-INF/native/lib/" + libraryName,
                        libraryName
                    };
                    
                    for (String path : searchPaths) {
                        libraryUrl = cl.getResource(path);
                        if (libraryUrl != null) {
                            logger.debug("Biblioteca nativa encontrada en: {}", path);
                            break;
                        }
                    }
                    
                    if (libraryUrl != null) {
                        // Extraer la biblioteca a un archivo temporal y cargarla
                        java.nio.file.Path tempLib = java.nio.file.Files.createTempFile("tree-sitter-java", 
                            libraryName.substring(libraryName.lastIndexOf('.')));
                        tempLib.toFile().deleteOnExit();
                        
                        try (java.io.InputStream is = libraryUrl.openStream()) {
                            java.nio.file.Files.copy(is, tempLib, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        
                        // Hacer el archivo ejecutable (necesario en Linux)
                        tempLib.toFile().setExecutable(true);
                        
                        // Cargar la biblioteca y crear el SymbolLookup
                        System.load(tempLib.toAbsolutePath().toString());
                        SymbolLookup symbols = SymbolLookup.libraryLookup(tempLib.toAbsolutePath().toString(), Arena.global());
                        language = Language.load(symbols, "tree_sitter_java");
                        logger.debug("Lenguaje Java de Tree-sitter cargado correctamente");
                    } else {
                        logger.warn("No se encontró la biblioteca nativa tree-sitter-java en el classpath. " +
                            "Buscado en: {}", String.join(", ", searchPaths));
                        throw new RuntimeException("No se encontró la biblioteca nativa tree-sitter-java en el classpath. " +
                            "La biblioteca nativa debería estar incluida en el JAR de jtreesitter o disponible en el sistema.");
                    }
                } catch (RuntimeException e) {
                    throw e; // Re-lanzar RuntimeException tal cual
                } catch (Exception e) {
                    logger.error("No se pudo cargar el lenguaje Java de Tree-sitter: {}", e.getMessage(), e);
                    throw new RuntimeException("No se pudo cargar el lenguaje Tree-sitter para Java. " +
                        "Asegúrate de que jtreesitter esté correctamente configurado y que las bibliotecas nativas estén disponibles.", e);
                }
                break;
            default:
                throw new IllegalArgumentException("El parámetro 'lang': "+lang+" no defininda.");
        }

        parser = new Parser();
        if (language != null) {
            parser.setLanguage(language);
        }
    }

    public void findMethod(Path path, String method){
        String queryString = String.format(METHOD_DECLARATION_EXACT_QUERY, method);
        try{
            if (language == null) {
                logger.error("No se puede analizar archivo - lenguaje no configurado");
                return;
            }
            
            if (!Files.exists(path)) {
                logger.error("El archivo no existe: {}", path);
                return;
            }
            
            byte[] fileBytes = Files.readAllBytes(path);
            String content = new String(fileBytes, StandardCharsets.UTF_8);
            List<String> sourceLines = Files.readAllLines(path);
            Tree tree = parser.parse(content, InputEncoding.UTF_8).orElseThrow();

            Query query = new Query(language, queryString);
            try(QueryCursor cursor = new QueryCursor(query)){
                cursor.findMatches(tree.getRootNode()).forEach(match -> {
                    List<Node> nodes = match.findNodes("methodName");
                    nodes.forEach(node -> {
                        String methodName = node.getText();
                        if (methodName.equals(method)) {
                            System.out.println("Método encontrado: " + methodName);
                        }
                    });
                });
            }

            
        }catch(IOException ex){
            logger.error("Error al leer el archivo {}: {}", path, ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("Error inesperado al analizar archivo {}: {}", path, ex.getMessage(), ex);
        }
    }

    /**
     * Busca llamadas exactas a métodos especificando objeto, nombre de método y tipos de parámetros
     * @param path Archivo a analizar
     * @param objectName Nombre del objeto (ej: "clase2")
     * @param methodName Nombre del método (ej: "calcular")
     * @param paramTypes Array de tipos de parámetros esperados (ej: {"int", "String"}) - usar array vacío para métodos sin parámetros
     */
    /*public void findExactMethodCall(Path path, String objectName, String methodName, String[] paramTypes){
        String queryString = String.format(CALL_EXPRESSION_WITH_OBJECT_AND_PARAMS_QUERY, objectName, methodName);
        try{
            if (language == null) {
                logger.error("No se puede analizar archivo - lenguaje no configurado");
                return;
            }
            
            if (!Files.exists(path)) {
                logger.error("El archivo no existe: {}", path);
                return;
            }
            
            byte[] fileBytes = Files.readAllBytes(path);
            String content = new String(fileBytes, StandardCharsets.UTF_8);
            TSTree tree = parser.parseString(null, content);

            TSQuery query = new TSQuery(language, queryString);
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(query, tree.getRootNode());

            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                TSNode callNode = null;
                TSNode argumentsNode = null;
                String foundObjectName = null;
                String foundMethodName = null;
                
                // Recoger todas las capturas
                for (TSQueryCapture capture : match.getCaptures()) {
                    TSNode node = capture.getNode();
                    String captureName = query.getCaptureNameForId(capture.getIndex());
                    
                    if ("objectName".equals(captureName)) {
                        foundObjectName = substringFromBytes(fileBytes, node.getStartByte(), node.getEndByte());
                        callNode = node.getParent().getParent(); // field_access -> call_expression
                    } else if ("methodName".equals(captureName)) {
                        foundMethodName = substringFromBytes(fileBytes, node.getStartByte(), node.getEndByte());
                    } else if ("arguments".equals(captureName)) {
                        argumentsNode = node;
                    }
                }
                
                // Verificar coincidencia exacta de objeto y método
                if (objectName.equals(foundObjectName) && methodName.equals(foundMethodName) && 
                    callNode != null && argumentsNode != null) {
                    
                    // Analizar los argumentos
                    String[] foundParamTypes = analyzeArgumentTypes(argumentsNode, fileBytes, content);
                    
                    // Verificar coincidencia exacta de parámetros
                    if (parametersMatch(paramTypes, foundParamTypes)) {
                        int lineNumber = callNode.getStartPoint().getRow() + 1;
                        String fullCall = substringFromBytes(fileBytes, callNode.getStartByte(), callNode.getEndByte());
                        
                        logger.debug("✓ Llamada exacta encontrada: {}. Archivo: {}. Línea: {}. Parámetros: [{}]", 
                                   fullCall, path.getFileName().toString(), lineNumber, String.join(", ", foundParamTypes));
                    }
                }
            }
            
        }catch(IOException ex){
            logger.error("Error al leer el archivo {}: {}", path, ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("Error inesperado al analizar archivo {}: {}", path, ex.getMessage(), ex);
        }
    }*/
    
    /**
     * Analiza los tipos de argumentos en una llamada a método
     */
    /*private String[] analyzeArgumentTypes(TSNode argumentsNode, byte[] fileBytes, String content) {
        if (argumentsNode.getChildCount() <= 2) { // Solo paréntesis () 
            return new String[0];
        }
        
        java.util.List<String> types = new java.util.ArrayList<>();
        
        // Recorrer los hijos del argument_list (excluyendo paréntesis)
        for (int i = 0; i < argumentsNode.getChildCount(); i++) {
            TSNode child = argumentsNode.getChild(i);
            String nodeType = child.getType();
            
            // Saltar paréntesis y comas
            if ("(".equals(nodeType) || ")".equals(nodeType) || ",".equals(nodeType)) {
                continue;
            }
            
            String inferredType = inferArgumentType(child, fileBytes, content);
            if (inferredType != null) {
                types.add(inferredType);
            }
        }
        
        return types.toArray(new String[0]);
    }
    
    /**
     * Infiere el tipo de un argumento basándose en su nodo AST
     */
    /*private String inferArgumentType(TSNode argumentNode, byte[] fileBytes, String content) {
        String nodeType = argumentNode.getType();
        String argumentText = substringFromBytes(fileBytes, argumentNode.getStartByte(), argumentNode.getEndByte());
        
        switch (nodeType) {
            case "decimal_integer_literal":
                return "int";
            case "decimal_floating_point_literal":
                return "double";
            case "string_literal":
                return "String";
            case "true":
            case "false":
            case "boolean_literal":
                return "boolean";
            case "character_literal":
                return "char";
            case "identifier":
                // Para identificadores, intentar inferir del contexto o nombre
                return inferFromIdentifier(argumentText);
            case "field_access":
                // Para accesos a campos, intentar inferir
                return "Object"; // Genérico por defecto
            case "method_invocation":
            case "call_expression":
                // Para llamadas a métodos, es más complejo - usar genérico
                return "Object";
            default:
                return "Object"; // Tipo genérico por defecto
        }
    }
    
    /**
     * Intenta inferir el tipo de un identificador basándose en convenciones de nombres
     */
    private String inferFromIdentifier(String identifier) {
        // Convenciones básicas de nombres
        if (identifier.toLowerCase().contains("string") || identifier.toLowerCase().contains("str")) {
            return "String";
        }
        if (identifier.toLowerCase().contains("int") || identifier.toLowerCase().contains("count") || 
            identifier.toLowerCase().contains("index") || identifier.toLowerCase().contains("size")) {
            return "int";
        }
        if (identifier.toLowerCase().contains("double") || identifier.toLowerCase().contains("float")) {
            return "double";
        }
        if (identifier.toLowerCase().contains("bool") || identifier.toLowerCase().contains("flag")) {
            return "boolean";
        }
        
        // Por defecto, tipo genérico
        return "Object";
    }
    
    /**
     * Verifica si los parámetros coinciden exactamente
     */
    private boolean parametersMatch(String[] expected, String[] found) {
        if (expected.length != found.length) {
            return false;
        }
        
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(found[i])) {
                return false;
            }
        }
        
        return true;
    }

    /*private TSNode _getModifiersNode(TSNode methodNode){
        TSNode modifiersNode = null;
        for (int i = 0; i < methodNode.getChildCount(); i++) {
            TSNode child = methodNode.getChild(i);
            if ("modifiers".equals(child.getType())) {
                modifiersNode = child;
                break;
            }
        }

        return modifiersNode;
    }*/
    
    /*private String getMethodSignature(TSNode methodNode, byte[] fileBytes, String content) {
        // Obtener el nodo de modificadores (public, private, etc.)
        TSNode modifiersNode = _getModifiersNode(methodNode);
        
        // Obtener el nodo de tipo de retorno
        TSNode returnTypeNode = _getReturnTypeNode(methodNode);
        
        // Obtener el nodo de parámetros
        TSNode parametersNode = _getParametersNode(methodNode);
        
        StringBuilder signature = new StringBuilder();
        
        // Añadir modificadores
        if (modifiersNode != null) {
            signature.append(substringFromBytes(fileBytes, modifiersNode.getStartByte(), modifiersNode.getEndByte())).append(" ");
        }
        
        // Añadir tipo de retorno
        if (returnTypeNode != null) {
            signature.append(substringFromBytes(fileBytes, returnTypeNode.getStartByte(), returnTypeNode.getEndByte())).append(" ");
        }
        
        // Añadir nombre del método
        TSNode nameNode = methodNode.getChildByFieldName("name");
        if (nameNode != null) {
            signature.append(substringFromBytes(fileBytes, nameNode.getStartByte(), nameNode.getEndByte()));
        }
        
        // Añadir parámetros
        if (parametersNode != null) {
            signature.append(substringFromBytes(fileBytes, parametersNode.getStartByte(), parametersNode.getEndByte()));
        }
        
        return signature.toString();
    }   */

    /*private TSNode _getParametersNode(TSNode methodNode) {
        TSNode parametersNode = null;
        for (int i = 0; i < methodNode.getChildCount(); i++) {
            TSNode child = methodNode.getChild(i);
            if ("formal_parameters".equals(child.getType())) {
                parametersNode = child;
                break;
            }
        }
        return parametersNode;
    }*/

    /*private TSNode _getReturnTypeNode(TSNode methodNode) {
        TSNode returnTypeNode = null;
        for (int i = 0; i < methodNode.getChildCount(); i++) {
            TSNode child = methodNode.getChild(i);
            if ("type_identifier".equals(child.getType()) || "void_type".equals(child.getType())) {
                returnTypeNode = child;
                break;
            }
        }
        return returnTypeNode;
    }*/
  

    public String getLanguage(){
        if (language != null) {
            String langName = this.language.toString();
            logger.debug("Retornando nombre del lenguaje: {}", langName);
            return langName;
        }
        logger.debug("Retornando null para lenguaje no configurado");
        return null;
    }

    /**
     * Devuelve una lista de ubicaciones de métodos encontrados por nombre
     * Para uso en parsers híbridos
     */
    /*public List<MethodLocation> getMethodLocations(Path path, String methodName) {
        List<MethodLocation> locations = new ArrayList<>();
        String queryString = String.format(METHOD_DECLARATION_EXACT_QUERY, methodName);
        
        try {
            if (language == null) {
                logger.error("No se puede analizar archivo - lenguaje no configurado");
                return locations;
            }
            
            if (!Files.exists(path)) {
                logger.error("El archivo no existe: {}", path);
                return locations;
            }
            
            byte[] fileBytes = Files.readAllBytes(path);
            String content = new String(fileBytes, StandardCharsets.UTF_8);
            List<String> sourceLines = Files.readAllLines(path);
            TSTree tree = parser.parseString(null, content);

            TSQuery query = new TSQuery(language, queryString);
            TSQueryCursor cursor = new TSQueryCursor();
            cursor.exec(query, tree.getRootNode());

            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    TSNode node = capture.getNode();
                    String captureName = query.getCaptureNameForId(capture.getIndex());
                    
                    if ("methodName".equals(captureName)) {
                        String foundMethodName = substringFromBytes(fileBytes, node.getStartByte(), node.getEndByte());
                        
                        if (methodName.equals(foundMethodName)) {
                            // Obtener el nodo padre (method_declaration)
                            TSNode methodNode = node.getParent();
                            int startLine = methodNode.getStartPoint().getRow() + 1;
                            int endLine = methodNode.getEndPoint().getRow() + 1;
                            
                            // Extraer el contenido del método
                            StringBuilder methodContent = new StringBuilder();
                            for (int i = startLine - 1; i < Math.min(endLine, sourceLines.size()); i++) {
                                methodContent.append(sourceLines.get(i)).append("\n");
                            }
                            
                            locations.add(new MethodLocation(foundMethodName, startLine, endLine, methodContent.toString()));
                        }
                    }
                }
            }
            
        } catch (IOException ex) {
            logger.error("Error al leer el archivo {}: {}", path, ex.getMessage(), ex);
        } catch (Exception ex) {
            logger.error("Error inesperado al analizar archivo {}: {}", path, ex.getMessage(), ex);
        }
        
        return locations;
    }*/
}
