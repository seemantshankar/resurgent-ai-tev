package com.resurgent.tev.parser.cli;

import com.resurgent.tev.parser.review.ReviewService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Thin Picocli adapter over {@link ReviewService}. */
@Command(name = "review", description = "List and resolve cost-head mapping and total proposals",
        subcommands = {ReviewCommand.ListMappings.class, ReviewCommand.ShowMapping.class,
                ReviewCommand.AcceptMapping.class, ReviewCommand.RejectMapping.class,
                ReviewCommand.ListTotals.class, ReviewCommand.ShowTotal.class,
                ReviewCommand.AcceptTotal.class, ReviewCommand.RejectTotal.class,
                ReviewCommand.AddManual.class, ReviewCommand.AcceptManual.class,
                ReviewCommand.ChangeManual.class, ReviewCommand.WithdrawManual.class,
                ReviewCommand.ListDuplicates.class, ReviewCommand.ShowDuplicate.class,
                ReviewCommand.MarkDuplicate.class, ReviewCommand.MarkDistinct.class})
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

    @Command(name = "list-totals", description = "List pending cost-head total candidates")
    public static final class ListTotals implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            for (ReviewService.TotalReviewItem item : new ReviewService().listPendingTotals(db)) {
                spec.commandLine().getOut().printf("%d %s%n", item.reviewQueueId(), item.summary());
            }
            return 0;
        }
    }

    @Command(name = "show-total", description = "Show one cost-head total candidate")
    public static final class ShowTotal implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return new ReviewService().showTotal(db, reviewId)
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

    @Command(name = "accept-total", description = "Accept a pending cost-head total")
    public static final class AcceptTotal implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason") String reason;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return decideTotal(true, db, reviewId, actor, reason, spec);
        }
    }

    @Command(name = "reject-total", description = "Reject a pending cost-head total")
    public static final class RejectTotal implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason") String reason;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return decideTotal(false, db, reviewId, actor, reason, spec);
        }
    }

    @Command(name = "add-manual", description = "Add a pending manual contribution")
    public static final class AddManual implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--cost-head", required = true) String costHead;
        @Option(names = "--amount", required = true) BigDecimal amount;
        @Option(names = "--unit", required = true) String unit;
        @Option(names = "--currency", required = true) String currency;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Option(names = "--adjusts-contribution-id") Long adjustsContributionId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                long id = new ReviewService().addManual(db, costHead, amount, unit, currency, actor,
                        reason, adjustsContributionId);
                spec.commandLine().getOut().println(id);
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "accept-manual", description = "Accept a pending manual contribution")
    public static final class AcceptManual implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Parameters(index = "0") long manualId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                new ReviewService().acceptManual(db, manualId, actor, reason);
                spec.commandLine().getOut().println("Accepted");
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "change-manual", description = "Change a pending or accepted manual contribution")
    public static final class ChangeManual implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--amount", required = true) BigDecimal amount;
        @Option(names = "--unit", required = true) String unit;
        @Option(names = "--currency", required = true) String currency;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Parameters(index = "0") long manualId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                new ReviewService().changeManual(db, manualId, amount, unit, currency, actor, reason);
                spec.commandLine().getOut().println("Changed");
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "withdraw-manual", description = "Withdraw a manual contribution")
    public static final class WithdrawManual implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Parameters(index = "0") long manualId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                new ReviewService().withdrawManual(db, manualId, actor, reason);
                spec.commandLine().getOut().println("Withdrawn");
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list-duplicates", description = "List pending duplicate proposals")
    public static final class ListDuplicates implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            for (ReviewService.DuplicateReviewItem item : new ReviewService().listPendingDuplicates(db)) {
                spec.commandLine().getOut().printf("%d %s%n", item.reviewQueueId(), item.summary());
            }
            return 0;
        }
    }

    @Command(name = "show-duplicate", description = "Show one duplicate proposal")
    public static final class ShowDuplicate implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            return new ReviewService().showDuplicate(db, reviewId)
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

    @Command(name = "mark-duplicate", description = "Mark a proposal as Duplicate")
    public static final class MarkDuplicate implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Option(names = "--supersede", required = true) String supersede;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                new ReviewService().markDuplicate(db, reviewId, actor, reason, supersede);
                spec.commandLine().getOut().println("Duplicate");
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "mark-distinct", description = "Mark a proposal as Distinct")
    public static final class MarkDistinct implements Callable<Integer> {
        @Option(names = "--db", required = true) Path db;
        @Option(names = "--actor", required = true) String actor;
        @Option(names = "--reason", required = true) String reason;
        @Parameters(index = "0") long reviewId;
        @Spec CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try {
                new ReviewService().markDistinct(db, reviewId, actor, reason);
                spec.commandLine().getOut().println("Distinct");
                return 0;
            } catch (SQLException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return 1;
            }
        }
    }

    private static int decideTotal(boolean accept, Path db, long reviewId, String actor, String reason,
            CommandSpec spec) throws Exception {
        ReviewService review = new ReviewService();
        try {
            if (accept) {
                review.acceptTotal(db, reviewId, actor, reason);
                spec.commandLine().getOut().println("Accepted");
            } else {
                review.rejectTotal(db, reviewId, actor, reason);
                spec.commandLine().getOut().println("Rejected");
            }
            return 0;
        } catch (SQLException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
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
