package com.jsrc.app.jfr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Runs JDK tools (jcmd, jfr) as subprocesses.
 * Detects tool availability and provides clear error messages.
 *
 * <p>Requires JDK 14+ with HotSpot runtime (not OpenJ9).
 * Both jcmd and jfr are bundled with standard JDK distributions.</p>
 */
public final class JfrRunner {

    private static final int PROCESS_TIMEOUT_SECONDS = 120;

    private JfrRunner() {}

    /**
     * Finds the path to a JDK tool (jcmd or jfr).
     * Searches PATH first, then JAVA_HOME/bin.
     *
     * @param tool tool name ("jcmd" or "jfr")
     * @return path to the tool
     * @throws JfrToolNotFoundException if tool is not found
     */
    public static String findTool(String tool) {
        Objects.requireNonNull(tool, "tool must not be null");

        // Try PATH first via 'which'
        try {
            var pb = new ProcessBuilder("which", tool);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && line != null && !line.isBlank()) {
                    return line.trim();
                }
            } finally {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Fall through to JAVA_HOME check
        }

        // Try JAVA_HOME/bin
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path toolPath = Path.of(javaHome, "bin", tool);
            if (Files.isExecutable(toolPath)) {
                return toolPath.toString();
            }
        }

        throw new JfrToolNotFoundException(tool);
    }

    /**
     * Lists visible JVM processes via jcmd (no arguments).
     *
     * @return list of lines, each containing PID and main class
     */
    public static List<String> listJvms() {
        String jcmd = findTool("jcmd");
        return runCommand(List.of(jcmd), "listing JVMs");
    }

    /**
     * Starts a JFR recording on a target JVM.
     *
     * @param pid      target JVM PID
     * @param duration recording duration (e.g. "30s", "1m")
     * @param output   output .jfr file path
     * @param settings JFR settings profile ("default" or "profile")
     * @return command output lines
     */
    public static List<String> startRecording(long pid, String duration, Path output, String settings) {
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        String jcmd = findTool("jcmd");
        String command = String.format("JFR.start duration=%s filename=%s settings=%s name=jsrc-recording",
                duration, output.toAbsolutePath(), settings);
        return runCommand(List.of(jcmd, String.valueOf(pid), command), "starting JFR recording");
    }

    /**
     * Stops an active JFR recording on a target JVM.
     *
     * @param pid target JVM PID
     * @return command output lines
     */
    public static List<String> stopRecording(long pid) {
        String jcmd = findTool("jcmd");
        return runCommand(List.of(jcmd, String.valueOf(pid), "JFR.stop", "name=jsrc-recording"),
                "stopping JFR recording");
    }

    /**
     * Reads a .jfr file and returns JSON output filtered by event types.
     *
     * @param jfrFile    path to the .jfr file
     * @param eventTypes JFR event types to include (e.g. "jdk.ExecutionSample")
     * @return raw JSON output from jfr print
     */
    public static String readJfr(Path jfrFile, List<String> eventTypes) {
        Objects.requireNonNull(jfrFile, "jfrFile must not be null");
        if (!Files.exists(jfrFile)) {
            throw new IllegalArgumentException("JFR file not found: " + jfrFile);
        }

        String jfr = findTool("jfr");
        List<String> cmd = new ArrayList<>();
        cmd.add(jfr);
        cmd.add("print");
        cmd.add("--json");
        if (eventTypes != null && !eventTypes.isEmpty()) {
            cmd.add("--events");
            cmd.add(String.join(",", eventTypes));
        }
        cmd.add("--stack-depth");
        cmd.add("64");
        cmd.add(jfrFile.toAbsolutePath().toString());

        List<String> lines = runCommand(cmd, "reading JFR file");
        return String.join("\n", lines);
    }

    private static List<String> runCommand(List<String> command, String description) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            List<String> output = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                 var errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
                while ((line = errReader.readLine()) != null) {
                    errors.add(line);
                }

                if (!p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("Process timed out after " + PROCESS_TIMEOUT_SECONDS + "s while " + description);
                }

                if (p.exitValue() != 0) {
                    String errorMsg = errors.isEmpty() ? "exit code " + p.exitValue() : String.join("\n", errors);
                    throw new IOException("Failed " + description + ": " + errorMsg);
                }
            } finally {
                p.destroyForcibly();
            }

            return output;
        } catch (IOException e) {
            throw new JfrExecutionException(description, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JfrExecutionException(description, e);
        }
    }
}
