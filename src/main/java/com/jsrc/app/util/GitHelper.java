package com.jsrc.app.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;

/**
 * Git integration utilities. Used by todo (blame), debt (trend), changed commands.
 */
public class GitHelper {

    /**
     * Returns git blame info for a specific line.
     *
     * @return map with "author", "date", "commit" or empty if git not available
     */
    public static Map<String, String> blame(Path workdir, String file, int line) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "blame", "-L", line + "," + line, "--porcelain", file);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Map<String, String> result = new LinkedHashMap<>();
            String l;
            while ((l = reader.readLine()) != null) {
                if (l.startsWith("author ")) result.put("author", l.substring(7));
                if (l.startsWith("author-time ")) {
                    long epoch = Long.parseLong(l.substring(12));
                    result.put("date", java.time.Instant.ofEpochSecond(epoch)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate().toString());
                }
                if (l.startsWith("committer ")) result.putIfAbsent("author", l.substring(10));
            }
            p.waitFor();
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Returns list of files changed vs a git ref (default HEAD).
     */
    public static List<String> changedFiles(Path workdir, String ref) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", ref);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> files = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith(".java")) files.add(line);
            }
            p.waitFor();
            return files;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns git log entries for a file.
     */
    public static List<Map<String, String>> log(Path workdir, String file, int maxEntries) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "log", "--pretty=format:%H|%an|%ai|%s", "-" + maxEntries, "--", file);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<Map<String, String>> entries = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 4);
                if (parts.length >= 4) {
                    entries.add(Map.of(
                            "commit", parts[0],
                            "author", parts[1],
                            "date", parts[2],
                            "message", parts[3]
                    ));
                }
            }
            p.waitFor();
            return entries;
        } catch (Exception e) {
            return List.of();
        }
    }
}
