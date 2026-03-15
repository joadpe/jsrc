package com.jsrc.app.model;

import java.util.List;

/**
 * Represents the hierarchy of a class: its superclass, interfaces,
 * subclasses, and implementors.
 *
 * @param target       the queried class name
 * @param superClass   direct superclass (empty if none/unknown)
 * @param interfaces   implemented interfaces
 * @param subClasses   direct subclasses found in the codebase
 * @param implementors classes implementing this interface (if target is an interface)
 */
public record HierarchyResult(
        String target,
        String superClass,
        List<String> interfaces,
        List<String> subClasses,
        List<String> implementors
) {}
