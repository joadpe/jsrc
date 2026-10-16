package com.jsrc.app.review;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.JavaParserTypeNames;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The constructor Java declares for a type without constructor declarations, which parsed
 * class models leave out: the default constructor of a class (JLS 8.8.9) and the canonical
 * constructor of a record (JLS 8.10.4). Enums get none here: no {@code new} expression can
 * call an enum constructor (JLS 8.9.2), so there is no caller to compare it for.
 */
final class ImplicitConstructors {
    private static final Set<String> ACCESS = Set.of("public", "protected", "private");

    /** Types whose constructors the class model covers, with the implicit one if they have it. */
    private final Map<String, Optional<MethodInfo>> resolved;
    /** Records that declare constructors, which parsed class models do not list. */
    private final Set<String> recordsWithConstructors;

    private ImplicitConstructors(Map<String, Optional<MethodInfo>> resolved,
                                 Set<String> recordsWithConstructors) {
        this.resolved = resolved;
        this.recordsWithConstructors = recordsWithConstructors;
    }

    static ImplicitConstructors parse(JavaParser parser, String source) {
        Map<String, Optional<MethodInfo>> resolved = new HashMap<>();
        Set<String> recordsWithConstructors = new HashSet<>();
        if (source != null) {
            parser.parse(source).getResult().ifPresent(unit -> {
                for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                    String name = JavaParserTypeNames.qualifiedName(type);
                    if (type instanceof RecordDeclaration record) {
                        if (record.getConstructors().isEmpty() && record.getCompactConstructors().isEmpty()) {
                            resolved.put(name, Optional.of(implicit(record, record.getParameters().stream()
                                    .map(component -> new MethodInfo.ParameterInfo(
                                            component.getTypeAsString(), component.getNameAsString()))
                                    .toList())));
                        } else {
                            recordsWithConstructors.add(name);
                        }
                    } else if (type instanceof ClassOrInterfaceDeclaration declaration
                            && !declaration.isInterface() && declaration.getConstructors().isEmpty()) {
                        resolved.put(name, Optional.of(implicit(declaration, List.of())));
                    } else {
                        resolved.put(name, Optional.empty());
                    }
                }
            });
        }
        return new ImplicitConstructors(resolved, recordsWithConstructors);
    }

    /** The implicit constructor of a type, or null when it declares constructors or has none. */
    MethodInfo of(String type) {
        return resolved.getOrDefault(type, Optional.empty()).orElse(null);
    }

    /** Why the constructors of a type cannot be compared, or null when they can. */
    String unresolved(String type) {
        if (recordsWithConstructors.contains(type)) {
            return "record declares constructors that are not modelled; constructor changes unresolved";
        }
        return resolved.containsKey(type) ? null
                : "type not resolved in parsed source; implicit constructor unknown";
    }

    private static MethodInfo implicit(TypeDeclaration<?> type, List<MethodInfo.ParameterInfo> parameters) {
        // The access of the type, package access without a modifier; members of interfaces are public
        Node owner = type.getParentNode().orElse(null);
        boolean interfaceMember = owner instanceof AnnotationDeclaration
                || (owner instanceof ClassOrInterfaceDeclaration enclosing && enclosing.isInterface());
        List<String> access = interfaceMember ? List.of("public")
                : type.getModifiers().stream().map(modifier -> modifier.getKeyword().asString())
                        .filter(ACCESS::contains).toList();
        return new MethodInfo(type.getNameAsString(), JavaParserTypeNames.binaryName(type), 0, 0, "",
                access, parameters, "", List.of(), List.of(), List.of(), null);
    }
}
