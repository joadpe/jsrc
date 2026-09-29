package com.jsrc.app.analysis;

import com.jsrc.app.parser.model.ClassInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Project data needed by reusable analysis services. */
public interface AnalysisSource {
    List<ClassInfo> getAllClasses();
    List<Path> javaFiles();
    String rootPath();
    Optional<String> indexedFileForClass(String className);
    CallGraph callGraph();
    Set<String> daoClasses();
}
