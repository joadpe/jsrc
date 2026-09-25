package com.jsrc.app.cli;

import com.jsrc.app.project.SourceSet;
import picocli.CommandLine;

/** Converts stable CLI source-set names to their domain representation. */
public final class SourceSetConverter implements CommandLine.ITypeConverter<SourceSet> {
    @Override
    public SourceSet convert(String value) {
        return SourceSet.fromExternalName(value);
    }
}
