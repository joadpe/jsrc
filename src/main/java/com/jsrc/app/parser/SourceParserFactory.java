package com.jsrc.app.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.jsrc.app.project.SourceLevel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Supplies a parser configured for the declared source level of each file. */
public final class SourceParserFactory {

    private final Map<Path, SourceLevel> levels;
    private final Map<Integer, JavaParser> parsers = new HashMap<>();

    public SourceParserFactory(Map<Path, SourceLevel> levels) {
        this.levels = levels.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toAbsolutePath().normalize(),
                        Map.Entry::getValue));
    }

    public JavaParser forFile(Path file) {
        SourceLevel level = levels.get(file.toAbsolutePath().normalize());
        int version = level == null ? 21 : level.version();
        return parsers.computeIfAbsent(version, SourceParserFactory::create);
    }

    private static JavaParser create(int version) {
        var languageLevel = ParserConfiguration.LanguageLevel.valueOf("JAVA_" + version);
        return new JavaParser(new ParserConfiguration().setLanguageLevel(languageLevel));
    }
}
