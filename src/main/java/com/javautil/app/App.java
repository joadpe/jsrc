/*----------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for license information.
 *---------------------------------------------------------------------------------------*/

package com.javautil.app;

import java.nio.file.Path;
import java.util.List;

import com.javautil.app.codebase.CodeBase;
import com.javautil.app.codebase.CodeBaseLoader;
import com.javautil.app.codebase.JavaCodeBase;
import com.javautil.app.parser.JParser;
import com.javautil.app.parser.TreeSitterParser;

public class App {
    public static void main(String[] args) {
        System.out.println("=== Recorriendo estructura de archivos y filtrando archivos .java ===");

        String rootPath = "src";

        CodeBase project = new JavaCodeBase(new CodeBaseLoader());
        project.setPath(rootPath);

        List<Path> javaFiles = project.getFiles();

        JParser parser = new TreeSitterParser("java");

        javaFiles.forEach(p -> parser.findMethod(p, "findMethod"));

        
    }
} 