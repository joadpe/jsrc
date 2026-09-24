package com.jsrc.app.cli;

import com.jsrc.app.output.JsonProtocol;
import picocli.CommandLine;

/** Parses and validates the JSON protocol before command execution starts. */
public final class JsonProtocolConverter implements CommandLine.ITypeConverter<JsonProtocol> {

    @Override
    public JsonProtocol convert(String value) {
        try {
            return JsonProtocol.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new CommandLine.TypeConversionException(exception.getMessage());
        }
    }
}
