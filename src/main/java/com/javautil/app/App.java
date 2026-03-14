package com.javautil.app;

import java.nio.file.Path;
import java.util.List;

import com.javautil.app.codebase.CodeBase;
import com.javautil.app.codebase.CodeBaseLoader;
import com.javautil.app.codebase.JavaCodeBase;
import com.javautil.app.parser.CodeParser;
import com.javautil.app.parser.HybridJavaParser;
import com.javautil.app.parser.model.CodeSmell;
import com.javautil.app.parser.model.MethodInfo;

public class App {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage:");
            System.err.println("  javautil <source-root> <method-name>   Search for methods");
            System.err.println("  javautil <source-root> --smells        Detect code smells");
            System.exit(1);
        }

        String rootPath = args[0];
        String command = args[1];

        CodeBase project = new JavaCodeBase(rootPath, new CodeBaseLoader());
        List<Path> javaFiles = project.getFiles();
        CodeParser parser = new HybridJavaParser();

        if ("--smells".equals(command)) {
            runSmellDetection(parser, javaFiles, rootPath);
        } else {
            runMethodSearch(parser, javaFiles, rootPath, command);
        }
    }

    private static void runSmellDetection(CodeParser parser, List<Path> javaFiles, String rootPath) {
        System.out.printf("Analyzing %d Java files under '%s' for code smells...%n",
                javaFiles.size(), rootPath);

        int totalSmells = 0;
        int warnings = 0;
        int infos = 0;

        for (Path file : javaFiles) {
            List<CodeSmell> smells = parser.detectSmells(file);
            if (smells.isEmpty()) continue;

            System.out.printf("%n--- %s ---%n", file);
            for (CodeSmell smell : smells) {
                totalSmells++;
                switch (smell.severity()) {
                    case WARNING, ERROR -> warnings++;
                    case INFO -> infos++;
                }
                System.out.printf("  [%s] %s at line %d in %s%n    %s%n",
                        smell.severity(), smell.ruleId(), smell.line(),
                        smell.methodName().isEmpty() ? smell.className() : smell.methodName() + "()",
                        smell.message());
            }
        }

        System.out.printf("%nDone. Found %d smell(s): %d warning(s), %d info(s).%n",
                totalSmells, warnings, infos);
    }

    private static void runMethodSearch(CodeParser parser, List<Path> javaFiles,
                                         String rootPath, String methodName) {
        System.out.printf("Scanning %d Java files under '%s' for method '%s'...%n",
                javaFiles.size(), rootPath, methodName);

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
