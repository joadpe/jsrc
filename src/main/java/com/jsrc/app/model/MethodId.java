package com.jsrc.app.model;

import com.jsrc.app.util.SignatureUtils;
import java.util.List;
import java.util.Objects;

/** Stable method identity based on owner, name, and erased parameter types. */
public record MethodId(TypeId owner, String name, List<String> parameterTypes) {

    public MethodId {
        owner = Objects.requireNonNull(owner, "owner");
        name = Objects.requireNonNull(name, "name");
        parameterTypes = parameterTypes.stream()
                .map(SignatureUtils::eraseParameterType)
                .toList();
    }

    public String canonicalName() {
        return owner.canonicalName() + "#" + name
                + "(" + String.join(",", parameterTypes) + ")";
    }
}
