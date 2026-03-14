package com.javautil.app;

import java.nio.file.Path;
import java.util.List;

import com.javautil.app.codebase.CodeBase;
import com.javautil.app.codebase.CodeBaseLoader;
import com.javautil.app.codebase.JavaCodeBase;
import com.javautil.app.parser.CodeParser;
import com.javautil.app.parser.HybridJavaParser;
import com.javautil.app.parser.model.MethodInfo;

public class App {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: javautil <source-root> <method-name>");
            System.err.println("  source-root  Root directory to scan for .java files");
            System.err.println("  method-name  Method name to search for");
            System.exit(1);
        }

        String rootPath = args[0];
        String methodName = args[1];

        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();

        System.out.printf("Scanning %d Java files under '%s' for method '%s'...%n",
                javaFiles.size(), rootPath, methodName);

        CodeParser parser = new HybridJavaParser();

        int totalFound = 0;
        for (Path file : javaFiles) {
            List<MethodInfo> methods = parser.findMethods(file, methodName);
            for (MethodInfo m : methods) {
                totalFound++;
                System.out.printf("%n[%s] %s:%d-%d%n",
                        m.className().isEmpty() ? file.getFileName() : m.className(),
                        file, m.startLine(), m.endLine());
                System.out.printf("  %s%n", m.signature());

                if (!m.annotations().isEmpty()) {
                    System.out.printf("  Annotations: %s%n", m.annotations());
                }
                if (!m.thrownExceptions().isEmpty()) {
                    System.out.printf("  Throws: %s%n", String.join(", ", m.thrownExceptions()));
                }
                if (!m.typeParameters().isEmpty()) {
                    System.out.printf("  Type params: %s%n", String.join(", ", m.typeParameters()));
                }
                if (m.javadoc() != null) {
                    String firstLine = m.javadoc().lines().findFirst().orElse("").trim();
                    if (firstLine.startsWith("*")) firstLine = firstLine.substring(1).trim();
                    System.out.printf("  Javadoc: %s%n", firstLine);
                }
            }
        }

        System.out.printf("%nDone. Found %d match(es).%n", totalFound);
    }
}
