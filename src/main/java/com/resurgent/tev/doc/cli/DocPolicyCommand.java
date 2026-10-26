package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.db.MandatePolicyRepository;
import com.resurgent.tev.doc.platform.PlatformRegistry;
import com.resurgent.tev.doc.platform.SpendCeiling;
import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc policy}: set a mandate's data residency and spend ceiling,
 * and, when pointed at a registry, the platform default ceiling.
 */
@Command(name = "policy",
        description = "Set data residency and the spend ceiling for a mandate")
public final class DocPolicyCommand implements Callable<Integer> {

    @Option(names = "--mandate", required = true, description = "Mandate id")
    long mandateId;

    @Option(names = "--db", required = true, description = "Path to the SQLite workspace database")
    Path db;

    @Option(names = "--platform",
            description = "Platform registry. Created when this path is new.")
    Path platform;

    @Option(names = "--data-residency",
            description = "cloud_allowed|local_only")
    String dataResidency;

    private boolean spendCeilingSet;
    private Double spendCeiling;

    @Option(names = "--spend-ceiling",
            description = "Mandate spend ceiling in USD. Zero allows no cloud call.")
    void setSpendCeiling(double value) {
        spendCeilingSet = true;
        spendCeiling = value;
    }

    private boolean platformCeilingSet;
    private Double platformCeiling;

    @Option(names = "--platform-ceiling",
            description = "Platform default spend ceiling in USD. Requires --platform.")
    void setPlatformCeiling(double value) {
        platformCeilingSet = true;
        platformCeiling = value;
    }

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (platformCeilingSet && platform == null) {
                throw new IllegalArgumentException(
                        "--platform-ceiling requires --platform");
            }
            if (dataResidency != null
                    && !DataResidency.CLOUD_ALLOWED.equals(dataResidency)
                    && !DataResidency.LOCAL_ONLY.equals(dataResidency)) {
                throw new IllegalArgumentException(
                        "data residency must be cloud_allowed or local_only");
            }
            if (spendCeilingSet && spendCeiling != null
                    && (!Double.isFinite(spendCeiling) || spendCeiling < 0)) {
                throw new IllegalArgumentException(
                        "spend ceiling must be finite and zero or greater");
            }
            if (platformCeilingSet && platformCeiling != null
                    && (!Double.isFinite(platformCeiling) || platformCeiling < 0)) {
                throw new IllegalArgumentException(
                        "platform ceiling must be finite and zero or greater");
            }
            try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
                var connection = workspace.connection();
                MandatePolicyRepository policy = new MandatePolicyRepository(connection);
                boolean transaction = false;
                try {
                    connection.setAutoCommit(false);
                    transaction = true;
                    String industry = PlatformRegistry.industries(connection, mandateId);
                    if (platform != null) {
                        try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
                            applyWorkspacePolicy(policy);
                            if (platformCeilingSet) {
                                registry.setDefaultCeiling(platformCeiling);
                            }
                            registry.recordMandate(mandateId, db, industry);
                            connection.commit();
                            transaction = false;
                            String residency = policy.dataResidency(mandateId);
                            Double mandateCeiling = policy.spendCeiling(mandateId);
                            PlatformRegistry.MandateView view =
                                    registry.mandate(mandateId).orElseThrow();
                            Double platformDefault = registry.defaultCeiling();
                            print(out, view.mandateId(), view.workspacePath(), view.industry(),
                                    view.status(), residency, mandateCeiling, platformDefault, true);
                        }
                    } else {
                        applyWorkspacePolicy(policy);
                        connection.commit();
                        transaction = false;
                        String residency = policy.dataResidency(mandateId);
                        Double mandateCeiling = policy.spendCeiling(mandateId);
                        print(out, mandateId, db.toAbsolutePath().normalize().toString(), industry,
                                "open", residency, mandateCeiling, null, false);
                    }
                } catch (Exception e) {
                    if (transaction) {
                        connection.rollback();
                    }
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("policy rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("policy failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private static void print(java.io.PrintWriter out, long mandateId, String path, String industry,
            String status, String residency, Double mandateCeiling, Double platformCeiling,
            boolean namedRegistry) {
        out.printf("mandate %d path %s industry %s status %s%n",
                mandateId, path, industry == null || industry.isBlank() ? "unset" : industry, status);
        out.printf("residency %s%n",
                residency == null ? DataResidency.CLOUD_ALLOWED : residency);
        out.printf("mandate spend ceiling %s%n", formatCeiling(mandateCeiling));
        if (namedRegistry) {
            out.printf("platform spend ceiling %s%n", formatCeiling(platformCeiling));
        }
        Double effective = SpendCeiling.effective(mandateCeiling,
                namedRegistry ? platformCeiling : null);
        out.printf("effective spend ceiling %s%n", formatCeiling(effective));
    }

    private void applyWorkspacePolicy(MandatePolicyRepository policy) throws java.sql.SQLException {
        if (dataResidency != null) {
            policy.upsert(mandateId, dataResidency);
        }
        if (spendCeilingSet) {
            policy.setSpendCeiling(mandateId, spendCeiling);
        }
    }

    private static String formatCeiling(Double ceiling) {
        if (ceiling == null) {
            return "unset";
        }
        if (ceiling == Math.rint(ceiling)) {
            return Long.toString(ceiling.longValue());
        }
        return Double.toString(ceiling);
    }
}
