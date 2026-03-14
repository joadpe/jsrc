package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.jsrc.app.ExitCode;
import com.jsrc.app.index.CodebaseIndex;

public class IndexCommand implements Command {
    @Override
    public int execute(CommandContext ctx) {
        Path root = Paths.get(ctx.rootPath());
        System.err.printf("Indexing %d Java files under '%s'...%n", ctx.javaFiles().size(), ctx.rootPath());

        var existing = CodebaseIndex.load(root);
        var index = new CodebaseIndex();
        int reindexed = index.build(ctx.parser(), ctx.javaFiles(), root, existing);

        try {
            index.save(root);
            System.err.printf("Done. Indexed %d files (%d re-indexed, %d cached).%n",
                    ctx.javaFiles().size(), reindexed, ctx.javaFiles().size() - reindexed);
        } catch (IOException ex) {
            System.err.printf("Error saving index: %s%n", ex.getMessage());
            System.exit(ExitCode.IO_ERROR);
        }
        return 0;
    }
}
