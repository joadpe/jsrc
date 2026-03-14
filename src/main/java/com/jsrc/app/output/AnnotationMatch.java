package com.jsrc.app.output;

import java.nio.file.Path;

import com.jsrc.app.parser.model.AnnotationInfo;

/**
 * Represents a match found by annotation search.
 *
 * @param type          "class" or "method"
 * @param name          name of the annotated element
 * @param className     enclosing class name
 * @param file          source file path
 * @param line          line number of the annotated element
 * @param annotation    the matched annotation with its attributes
 */
public record AnnotationMatch(
        String type,
        String name,
        String className,
        Path file,
        int line,
        AnnotationInfo annotation
) {}
