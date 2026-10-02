package com.jsrc.app.review;

import com.jsrc.app.ExitCode;
import com.jsrc.app.exception.JsrcException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads local Git changes without interpreting filenames as lines or shell arguments. */
public final class GitChangeReader {
    public record Change(String status, String path, String oldPath, String before, String after) {
        public boolean javaSource() {
            return path.endsWith(".java") || oldPath != null && oldPath.endsWith(".java");
        }
    }

    public record Snapshot(String oid, List<Change> changes) {}

    private final Path root;

    public GitChangeReader(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Snapshot read(String ref) {
        String requested = ref == null || ref.isBlank() ? "HEAD" : ref;
        String oid = new String(run("rev-parse", "--verify", "--end-of-options", requested + "^{commit}"),
                StandardCharsets.UTF_8).trim();
        byte[] diff = run("diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "-M", oid, "--");
        List<String> fields = nulFields(diff);
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < fields.size();) {
            String status = fields.get(i++);
            String oldPath = null;
            String path = fields.get(i++);
            if (status.startsWith("R") || status.startsWith("C")) {
                oldPath = path;
                path = fields.get(i++);
            }
            String kind = status.substring(0, 1);
            boolean javaSource = path.endsWith(".java") || oldPath != null && oldPath.endsWith(".java");
            String before = !javaSource || "A".equals(kind) ? null : blob(oid, oldPath == null ? path : oldPath);
            String after = !javaSource || "D".equals(kind) ? null : readWorktree(path);
            changes.add(new Change(kind.equals("R") ? "renamed" : switch (kind) {
                case "A" -> "added";
                case "D" -> "deleted";
                default -> "modified";
            }, path, oldPath, before, after));
        }
        for (String path : nulFields(run("ls-files", "--others", "--exclude-standard", "-z"))) {
            changes.add(new Change("untracked", path, null, null,
                    path.endsWith(".java") ? readWorktree(path) : null));
        }
        // Git cannot detect an unstaged move because its destination is still untracked.
        // Reconcile only unique, byte-identical Java pairs; uncertain moves stay separate.
        List<Change> reconciled = new ArrayList<>(changes);
        for (Change deleted : changes) {
            if (!deleted.status().equals("deleted") || !deleted.javaSource()) continue;
            List<Change> matches = changes.stream().filter(candidate ->
                    candidate.status().equals("untracked") && candidate.javaSource()
                            && !candidate.path().equals(deleted.path())
                            && deleted.before().equals(candidate.after())).toList();
            if (matches.size() == 1) {
                Change added = matches.getFirst();
                long matchingDeletes = changes.stream().filter(candidate ->
                        candidate.status().equals("deleted") && candidate.javaSource()
                                && candidate.before().equals(added.after())).count();
                if (matchingDeletes == 1) {
                    reconciled.remove(deleted);
                    reconciled.remove(added);
                    reconciled.add(new Change("renamed", added.path(), deleted.path(),
                            deleted.before(), added.after()));
                }
            }
        }
        changes = reconciled;
        changes.sort(java.util.Comparator.comparing(Change::path));
        return new Snapshot(oid, List.copyOf(changes));
    }

    private String blob(String oid, String path) {
        return new String(run("show", oid + ":" + path), StandardCharsets.UTF_8);
    }

    private String readWorktree(String path) {
        try {
            return java.nio.file.Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new JsrcException(ExitCode.IO_ERROR, "Cannot read changed file: " + path, ex);
        }
    }

    private byte[] run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile()).start();
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new JsrcException(ExitCode.BAD_USAGE,
                        "Git " + args[0] + " failed: " + new String(err, StandardCharsets.UTF_8).trim());
            }
            return out;
        } catch (IOException ex) {
            throw new JsrcException(ExitCode.IO_ERROR, "Cannot run Git: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JsrcException(ExitCode.IO_ERROR, "Git interrupted", ex);
        }
    }

    private static List<String> nulFields(byte[] bytes) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                fields.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        return fields;
    }
}
