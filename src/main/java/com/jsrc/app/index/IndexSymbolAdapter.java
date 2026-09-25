package com.jsrc.app.index;

import com.jsrc.app.model.TypeId;
import com.jsrc.app.symbol.SymbolResolver;
import com.jsrc.app.symbol.SymbolResolver.MethodSymbol;
import com.jsrc.app.symbol.SymbolResolver.TypeSymbol;
import com.jsrc.app.util.SignatureUtils;
import java.util.ArrayList;
import java.util.List;

/** Builds the common symbol resolver from persisted index entries. */
public final class IndexSymbolAdapter {

    private IndexSymbolAdapter() {}

    public static SymbolResolver create(List<IndexEntry> entries) {
        List<TypeSymbol> types = new ArrayList<>();
        for (IndexEntry entry : entries) {
            for (IndexedClass indexedClass : entry.classes()) {
                TypeId typeId = TypeId.from(
                        indexedClass.packageName(), indexedClass.name());
                List<MethodSymbol> methods = indexedClass.methods().stream()
                        .map(method -> new MethodSymbol(
                                method.name(),
                                SignatureUtils.extractParameterTypes(method.signature()),
                                typeId,
                                method.startLine(),
                                method.endLine(),
                                access(method.signature())))
                        .toList();
                types.add(new TypeSymbol(
                        typeId,
                        indexedClass.imports(),
                        indexedClass.superClass(),
                        indexedClass.interfaces(),
                        methods));
            }
        }
        return new SymbolResolver(types);
    }

    private static SymbolResolver.Access access(String signature) {
        if (signature == null) return SymbolResolver.Access.PACKAGE;
        if (signature.matches(".*\\bprivate\\b.*")) return SymbolResolver.Access.PRIVATE;
        if (signature.matches(".*\\bprotected\\b.*")) return SymbolResolver.Access.PROTECTED;
        if (signature.matches(".*\\bpublic\\b.*")) return SymbolResolver.Access.PUBLIC;
        return SymbolResolver.Access.PACKAGE;
    }
}
