package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.review.ReviewService;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Thin Picocli adapter over {@link ReviewService}. */
@Command(name = "review", description = "List and resolve cost-head mapping proposals",
        subcommands = {ReviewCommand.ListMappings.class, ReviewCommand.ShowMapping.class,
                ReviewCommand.AcceptMapping.class, ReviewCommand.RejectMapping.class})
public final class ReviewCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    public ReviewCommand() {}

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "list", description = "List pending cost-head mapping proposals")
    public static final class ListMappings implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            for (ReviewService.MappingReviewItem item : new ReviewService().listPendingMappings(db)) {
                spec.commandLine().getOut().printf("%d %s%n", item.reviewQueueId(), item.summary());
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Show one mapping proposal")
    public static final class ShowMapping implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return new ReviewService().show(db, reviewId)
                    .map(item -> {
                        spec.commandLine().getOut().println(item.summary());
                        spec.commandLine().getOut().println(item.detail());
                        return 0;
                    })
                    .orElseGet(() -> {
                        spec.commandLine().getErr().println("review item not found: " + reviewId);
                        return 1;
                    });
        }
    }

    @Command(name = "accept", description = "Accept a pending mapping proposal")
    public static final class AcceptMapping implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason") String reason;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return decide(true, db, reviewId, actor, reason, spec);
        }
    }

    @Command(name = "reject", description = "Reject a pending mapping proposal")
    public static final class RejectMapping implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason") String reason;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return decide(false, db, reviewId, actor, reason, spec);
        }
    }

    private static int decide(boolean accept, Path db, long reviewId, String actor, String reason,
            CommandSpec spec) throws Exception {
        ReviewService review = new ReviewService();
        try {
            if (accept) {
                review.acceptMapping(db, reviewId, actor, reason);
                spec.commandLine().getOut().println("Accepted");
            } else {
                review.rejectMapping(db, reviewId, actor, reason);
                spec.commandLine().getOut().println("Rejected");
            }
            return 0;
        } catch (SQLException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
    }
}
