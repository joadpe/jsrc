package com.jsrc.app.project;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Edges of the build-file readers: quoted Gradle levels, the pom buffer and XML declarations. */
class ProjectModelDetectorBoundaryTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    /** ProjectModelDetector decodes poms up to this size itself and streams larger ones. */
    private static final int BUFFERED_POM_BYTES = 16 * 1024 * 1024;
    private static final String POM_HEAD = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
            + "<version>1</version><properties><maven.compiler.source>1.8</maven.compiler.source>"
            + "</properties><!--";
    private static final String POM_TAIL = "--></project>\n";

    @TempDir
    Path projectRoot;

    @Test
    void doesNotReadGradleLevelFromUnclosedOrMismatchedQuotes() throws Exception {
        assertEquals("unknown", gradleVersion("sourceCompatibility = '11"));
        assertEquals("unknown", gradleVersion("sourceCompatibility = \"11-ea\""));
    }

    @Test
    void readsBareOneWithoutFailing() throws Exception {
        assertEquals("1", gradleVersion("sourceCompatibility = 1"));
    }

    @Test
    void decodesWindows1252PomOfExactlyTheBufferedSize() throws Exception {
        Files.write(projectRoot.resolve("pom.xml"), pomOfSize(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", " configuración ",
                BUFFERED_POM_BYTES, WINDOWS_1252));

        assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion());
    }

    @Test
    void parsesPomLargerThanTheBufferedSizeWhole() throws Exception {
        Files.write(projectRoot.resolve("pom.xml"), pomOfSize(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", " padding ",
                BUFFERED_POM_BYTES + 4096, StandardCharsets.UTF_8));

        assertEquals("8", new ProjectModelDetector().detect(projectRoot).javaVersion());
    }

    /**
     * "Á" is C3 81, valid UTF-8. Read as windows-1252 by jsrc it gives "Ã" and U+0081; the JDK
     * charset would give "Ã" and U+FFFD, so the name shows which decoder read the pom.
     */
    @Test
    void decodesEveryWindows1252DeclarationEvenForValidUtf8Bytes() throws Exception {
        for (String prolog : List.of(
                "<?xml version='1.0' encoding='windows-1252'?>\n",
                "<?xml version=\"1.0\" encoding=\"windows-1252\"?>\n",
                "<?xml version=\"1.0\" encoding =\"windows-1252\"?>\n",
                "<?xml version=\"1.0\" encoding= \"windows-1252\"?>\n",
                "<?xml version=\"1.0\" encoding=\"windows-1252\" standalone=\"yes\"?>\n")) {
            Files.write(projectRoot.resolve("pom.xml"), (prolog
                    + "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                    + "<artifactId>Á</artifactId><version>1</version></project>\n")
                    .getBytes(StandardCharsets.UTF_8));

            assertEquals("Ã\u0081",
                    new ProjectModelDetector().detect(projectRoot).modules().getFirst().name(), prolog);
        }
    }

    @Test
    void ignoresDeclarationLookalikeInsideAUtf8Pom() throws Exception {
        Files.write(projectRoot.resolve("pom.xml"), ("""
                <!-- <?xml version="1.0" encoding="windows-1252"?> -->
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>
                <artifactId>catálogo</artifactId><version>1</version></project>
                """).getBytes(StandardCharsets.UTF_8));

        assertEquals("catálogo",
                new ProjectModelDetector().detect(projectRoot).modules().getFirst().name());
    }

    @Test
    void ignoresDeclarationLookalikeInsideAWindows1252Pom() throws Exception {
        Files.write(projectRoot.resolve("pom.xml"), ("""
                <!-- <?xml version="1.0" encoding="ISO-8859-2"?> -->
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>
                <artifactId>catálogo</artifactId><version>1</version>
                <properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>
                """).getBytes(WINDOWS_1252));

        ProjectModel model = new ProjectModelDetector().detect(projectRoot);
        assertEquals("catálogo", model.modules().getFirst().name());
        assertEquals("8", model.javaVersion());
    }

    private String gradleVersion(String declaration) throws Exception {
        Files.writeString(projectRoot.resolve("build.gradle"),
                "plugins { id 'java' }\n" + declaration + "\n");
        return new ProjectModelDetector().detect(projectRoot).javaVersion();
    }

    /** A pom of exactly {@code size} bytes: prolog, head, a padded comment and the tail. */
    private static byte[] pomOfSize(String prolog, String comment, int size, Charset charset) {
        byte[] prefix = (prolog + POM_HEAD + comment).getBytes(charset);
        byte[] suffix = POM_TAIL.getBytes(charset);
        byte[] pom = new byte[size];
        Arrays.fill(pom, (byte) ' ');
        System.arraycopy(prefix, 0, pom, 0, prefix.length);
        System.arraycopy(suffix, 0, pom, size - suffix.length, suffix.length);
        return pom;
    }
}
