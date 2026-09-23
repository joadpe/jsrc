package com.jsrc.app.cli;

import java.io.InputStream;
import java.util.Properties;
import java.util.function.Supplier;
import picocli.CommandLine.IVersionProvider;

/** Provides the CLI version from Maven-filtered build metadata. */
public final class JsrcVersionProvider implements IVersionProvider {

    private static final String VERSION_RESOURCE = "/jsrc-version.properties";
    private final Supplier<InputStream> resourceLoader;

    public JsrcVersionProvider() {
        this(() -> JsrcVersionProvider.class.getResourceAsStream(VERSION_RESOURCE));
    }

    JsrcVersionProvider(Supplier<InputStream> resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String[] getVersion() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = resourceLoader.get()) {
            if (input == null) {
                throw new IllegalStateException("Missing version resource: " + VERSION_RESOURCE);
            }
            properties.load(input);
        }

        String version = properties.getProperty("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Missing version property in " + VERSION_RESOURCE);
        }
        return new String[] {"jsrc " + version};
    }
}
