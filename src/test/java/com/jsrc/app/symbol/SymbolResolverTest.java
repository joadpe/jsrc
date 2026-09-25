package com.jsrc.app.symbol;

import com.jsrc.app.model.TypeId;
import com.jsrc.app.symbol.SymbolResolver.Context;
import com.jsrc.app.symbol.SymbolResolver.Resolution;
import com.jsrc.app.symbol.SymbolResolver.MethodSymbol;
import com.jsrc.app.symbol.SymbolResolver.TypeSymbol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SymbolResolverTest {

    @Test
    void explicitImportResolvesHomonymousType() {
        TypeSymbol sales = type("sales", "Service");
        TypeSymbol support = type("support", "Service");
        SymbolResolver resolver = new SymbolResolver(List.of(sales, support));
        Context context = new Context("client", List.of("sales.Service"), null);

        Resolution<TypeSymbol> result = resolver.resolveType("Service", context);

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals("sales.Service", ((TypeSymbol) found.value()).id().canonicalName());
        assertEquals(SymbolResolver.Evidence.EXPLICIT_IMPORT, found.evidence());
    }

    @Test
    void samePackageResolvesHomonymousTypeWithoutImport() {
        TypeSymbol sales = type("sales", "Service");
        TypeSymbol support = type("support", "Service");
        SymbolResolver resolver = new SymbolResolver(List.of(sales, support));

        Resolution<TypeSymbol> result = resolver.resolveType(
                "Service", new Context("support", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals("support.Service", ((TypeSymbol) found.value()).id().canonicalName());
        assertEquals(SymbolResolver.Evidence.SAME_PACKAGE, found.evidence());
    }

    @Test
    void wildcardImportsDeclareAmbiguityWithQualifiedSuggestions() {
        TypeSymbol sales = type("sales", "Service");
        TypeSymbol support = type("support", "Service");
        SymbolResolver resolver = new SymbolResolver(List.of(support, sales));
        Context context = new Context(
                "client", List.of("support.*", "sales.*"), null);

        Resolution<TypeSymbol> result = resolver.resolveType("Service", context);

        var ambiguous = assertInstanceOf(Resolution.Ambiguous.class, result);
        assertEquals(List.of("sales.Service", "support.Service"), ambiguous.suggestions());
    }

    @Test
    void enclosingTypeResolvesNestedClass() {
        TypeSymbol nested = type("com.app", "Outer$Inner");
        SymbolResolver resolver = new SymbolResolver(List.of(nested));
        Context context = new Context(
                "com.app", List.of(), TypeId.from("com.app", "Outer"));

        Resolution<TypeSymbol> result = resolver.resolveType("Inner", context);

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals("com.app.Outer$Inner",
                ((TypeSymbol) found.value()).id().canonicalName());
        assertEquals(SymbolResolver.Evidence.ENCLOSING_TYPE, found.evidence());
    }

    @Test
    void unqualifiedHomonymsRemainAmbiguous() {
        SymbolResolver resolver = new SymbolResolver(List.of(
                type("support", "Service"),
                type("sales", "Service")));

        Resolution<TypeSymbol> result = resolver.resolveType("Service", Context.empty());

        var ambiguous = assertInstanceOf(Resolution.Ambiguous.class, result);
        assertEquals(List.of("sales.Service", "support.Service"), ambiguous.suggestions());
    }

    @Test
    void methodResolutionUsesGenericErasureToSelectEqualArityOverload() {
        TypeId owner = TypeId.from("com.app", "Service");
        TypeSymbol service = new TypeSymbol(owner, List.of(), List.of(), List.of(
                method(owner, "process", "List<String>"),
                method(owner, "process", "Set<String>")));
        SymbolResolver resolver = new SymbolResolver(List.of(service));

        Resolution<MethodSymbol> result = resolver.resolveMethod(
                "Service", "process", List.of("List<Integer>"),
                new Context("com.app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(List.of("List<String>"),
                ((MethodSymbol) found.value()).parameterTypes());
    }

    @Test
    void methodResolutionPrefersExactPrimitiveOverBoxedOverload() {
        TypeId owner = TypeId.from("com.app", "Service");
        TypeSymbol service = new TypeSymbol(owner, List.of(), List.of(), List.of(
                method(owner, "process", "int"),
                method(owner, "process", "Integer")));
        SymbolResolver resolver = new SymbolResolver(List.of(service));

        Resolution<MethodSymbol> result = resolver.resolveMethod(
                "Service", "process", List.of("int"),
                new Context("com.app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(List.of("int"), ((MethodSymbol) found.value()).parameterTypes());
    }

    @Test
    void methodResolutionPrefersExactReferenceOverObjectOverload() {
        TypeId owner = TypeId.from("com.app", "Service");
        TypeSymbol service = new TypeSymbol(owner, List.of(), List.of(), List.of(
                method(owner, "process", "Object"),
                method(owner, "process", "String")));
        SymbolResolver resolver = new SymbolResolver(List.of(service));

        Resolution<MethodSymbol> result = resolver.resolveMethod(
                "Service", "process", List.of("String"),
                new Context("com.app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(List.of("String"), ((MethodSymbol) found.value()).parameterTypes());
    }

    @Test
    void methodResolutionFindsInheritedDeclaration() {
        TypeId baseId = TypeId.from("base", "Base");
        TypeId childId = TypeId.from("child", "Child");
        TypeSymbol base = new TypeSymbol(baseId, List.of(), List.of(),
                List.of(method(baseId, "process", "String")));
        TypeSymbol child = new TypeSymbol(
                childId, List.of("base.Base"), List.of("base.Base"), List.of());
        SymbolResolver resolver = new SymbolResolver(List.of(base, child));

        Resolution<MethodSymbol> result = resolver.resolveMethod(
                "Child", "process", List.of("String"),
                new Context("child", List.of("base.Base"), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals("base.Base", ((MethodSymbol) found.value()).owner().canonicalName());
        assertEquals(SymbolResolver.Evidence.INHERITED, found.evidence());
    }

    @Test
    void superclassMethodPrecedesInterfaceDefault() {
        TypeId baseId = TypeId.from("app", "Base");
        TypeId contractId = TypeId.from("app", "Contract");
        TypeId childId = TypeId.from("app", "Child");
        TypeSymbol base = new TypeSymbol(baseId, List.of(), List.of(), List.of(),
                List.of(method(baseId, "process", "String")));
        TypeSymbol contract = new TypeSymbol(
                contractId, List.of(), List.of(), List.of(),
                List.of(method(contractId, "process", "String")));
        TypeSymbol child = new TypeSymbol(
                childId, List.of(), List.of("Base"), List.of("Contract"), List.of());
        SymbolResolver resolver = new SymbolResolver(List.of(base, contract, child));

        var result = resolver.resolveMethod(
                "Child", "process", List.of("String"),
                new Context("app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(baseId, ((MethodSymbol) found.value()).owner());
    }

    @Test
    void privateSuperclassMethodIsNotInherited() {
        TypeId baseId = TypeId.from("app", "Base");
        TypeId childId = TypeId.from("app", "Child");
        MethodSymbol privateMethod = new MethodSymbol(
                "process", List.of("String"), baseId, 1, 2,
                SymbolResolver.Access.PRIVATE);
        TypeSymbol base = new TypeSymbol(
                baseId, List.of(), List.of(), List.of(), List.of(privateMethod));
        TypeSymbol child = new TypeSymbol(
                childId, List.of(), List.of("Base"), List.of(), List.of());
        SymbolResolver resolver = new SymbolResolver(List.of(base, child));

        var result = resolver.resolveMethod(
                "Child", "process", List.of("String"),
                new Context("app", List.of(), null));

        assertInstanceOf(Resolution.Unresolved.class, result);
    }

    @Test
    void inheritedGenericParameterIsSubstituted() {
        TypeId baseId = TypeId.from("app", "Base");
        TypeId childId = TypeId.from("app", "Child");
        TypeSymbol base = new TypeSymbol(baseId, List.of(), List.of(), List.of(),
                List.of(method(baseId, "process", "T")));
        TypeSymbol child = new TypeSymbol(
                childId, List.of(), List.of("Base<String>"), List.of(), List.of());
        SymbolResolver resolver = new SymbolResolver(List.of(base, child));

        var result = resolver.resolveMethod(
                "Child", "process", List.of("String"),
                new Context("app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(List.of("String"), ((MethodSymbol) found.value()).parameterTypes());
    }

    @Test
    void maximallySpecificInterfaceMethodWins() {
        TypeId baseId = TypeId.from("app", "BaseContract");
        TypeId specificId = TypeId.from("app", "SpecificContract");
        TypeId childId = TypeId.from("app", "Child");
        TypeSymbol base = new TypeSymbol(baseId, List.of(), List.of(), List.of(),
                List.of(method(baseId, "process", "String")));
        TypeSymbol specific = new TypeSymbol(
                specificId, List.of(), List.of("BaseContract"), List.of(),
                List.of(method(specificId, "process", "String")));
        TypeSymbol child = new TypeSymbol(
                childId, List.of(), List.of(),
                List.of("BaseContract", "SpecificContract"), List.of());
        SymbolResolver resolver = new SymbolResolver(List.of(base, specific, child));

        var result = resolver.resolveMethod(
                "Child", "process", List.of("String"),
                new Context("app", List.of(), null));

        var found = assertInstanceOf(Resolution.Found.class, result);
        assertEquals(specificId, ((MethodSymbol) found.value()).owner());
    }

    private TypeSymbol type(String packageName, String name) {
        return new TypeSymbol(TypeId.from(packageName, name), List.of(), List.of(), List.of());
    }

    private MethodSymbol method(TypeId owner, String name, String... parameterTypes) {
        return new MethodSymbol(name, List.of(parameterTypes), owner, 1, 2);
    }
}
