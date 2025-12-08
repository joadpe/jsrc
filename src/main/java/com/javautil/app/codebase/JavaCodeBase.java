package com.javautil.app.codebase;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaCodeBase implements CodeBase{

    private static final Logger logger = LoggerFactory.getLogger(JavaCodeBase.class);
    
    private String path;
    private CodeBaseLoader loader;
    List<Path> javaFiles;

    public JavaCodeBase(CodeBaseLoader loader){
        this.loader = loader;
        logger.debug("JavaCodeBase inicializado con loader");
    }

    @Override
    public void setPath(String path) {
        this.path = path;
        logger.debug("Ruta establecida: {}", path);
    }

    @Override
    public List<Path> getFiles() {
        if(Objects.isNull(javaFiles)) {
            logger.debug("Cargando archivos Java desde: {}", path);
            this.javaFiles = loader.loadFilesFrom(path, "java");
            logger.info("Cargados {} archivos Java", javaFiles.size());
        }

        return javaFiles;
    } 
    
}
