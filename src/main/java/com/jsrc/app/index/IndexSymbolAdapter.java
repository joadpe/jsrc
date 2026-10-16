package com.jsrc.app.index;

import com.jsrc.app.model.TypeId;
import com.jsrc.app.symbol.SymbolResolver;
import com.jsrc.app.symbol.SymbolResolver.MethodSymbol;
import com.jsrc.app.symbol.SymbolResolver.TypeSymbol;
import com.jsrc.app.util.SignatureUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Builds the common symbol resolver from persisted index entries. */
public final class IndexSymbolAdapter {

    private static final Pattern PRIVATE = Pattern.compile(".*\\bprivate\\b.*");
    private static final Pattern PROTECTED = Pattern.compile(".*\\bprotected\\b.*");
    private static final Pattern PUBLIC = Pattern.compile(".*\\bpublic\\b.*");

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
        if (PRIVATE.matcher(signature).matches()) return SymbolResolver.Access.PRIVATE;
        if (PROTECTED.matcher(signature).matches()) return SymbolResolver.Access.PROTECTED;
        if (PUBLIC.matcher(signature).matches()) return SymbolResolver.Access.PUBLIC;
        return SymbolResolver.Access.PACKAGE;
    }
}
