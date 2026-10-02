package com.jsrc.app.command.reverse;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.output.TextFormatter;
import com.jsrc.app.review.ReviewReport;
import com.jsrc.app.review.ReviewService;
import java.nio.file.Path;

/** Offline review of local changes against a Git commit. */
public final class ReviewCommand implements Command {
    private final String ref;

    public ReviewCommand(String ref) {
        this.ref = ref;
    }

    @Override
    public int execute(CommandContext ctx) {
        ReviewReport report = new ReviewService(Path.of(ctx.rootPath()), ctx.parser(),
                ctx.callGraph(), ctx.javaFiles()).review(ref);
        if (ctx.formatter() instanceof TextFormatter) {
            System.out.print(report.toText(ctx.budgetContext()));
        } else {
            ctx.formatter().printResult(report.toMap(ctx.budgetContext()));
        }
        return report.files().size();
    }
}
