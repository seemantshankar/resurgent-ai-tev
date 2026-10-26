package com.resurgent.tev.doc.cli;

import com.resurgent.tev.doc.platform.PlatformRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code tev-doc withdraw}: mark one published pack or checklist version
 * withdrawn. The registry row stays. A mandate that already has the file
 * keeps it.
 */
@Command(name = "withdraw",
        description = "Mark one published pack or checklist version withdrawn")
public final class DocWithdrawCommand implements Callable<Integer> {

    @Option(names = "--platform",
            description = "Platform registry. Opened only when this path is given.")
    Path platform;

    @Option(names = "--pack", description = "Pack code@version to withdraw")
    String pack;

    @Option(names = "--industry", description = "Industry of the pack version")
    String industry;

    @Option(names = "--checklist", description = "Checklist code@version to withdraw")
    String checklist;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        try {
            if (platform == null) {
                throw new IllegalArgumentException("--platform is required");
            }
            boolean packNamed = pack != null && !pack.isBlank();
            boolean checklistNamed = checklist != null && !checklist.isBlank();
            if (packNamed == checklistNamed) {
                throw new IllegalArgumentException(
                        "name one pack version or one checklist version");
            }
            if (packNamed) {
                if (industry == null || industry.isBlank()) {
                    throw new IllegalArgumentException("--industry is required for a pack");
                }
                String[] identity = codeAndVersion(pack, "pack");
                requireRegistry(platform);
                try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
                    registry.withdrawPack(industry.trim(), identity[0], identity[1]);
                }
                out.printf("withdrew pack %s industry %s status withdrawn%n",
                        pack.trim(), industry.trim());
            } else {
                String[] identity = codeAndVersion(checklist, "checklist");
                requireRegistry(platform);
                try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
                    registry.withdrawChecklist(identity[0], identity[1]);
                }
                out.printf("withdrew checklist %s status withdrawn%n", checklist.trim());
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("withdraw rejected: " + e.getMessage());
            return 3;
        } catch (Exception e) {
            err.println("withdraw failed: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        }
    }

    private static void requireRegistry(Path platform) {
        if (!Files.isRegularFile(platform)) {
            throw new IllegalArgumentException("platform registry does not exist: " + platform);
        }
    }

    private static String[] codeAndVersion(String identity, String kind) {
        String trimmed = identity.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            throw new IllegalArgumentException(kind + " must name code@version");
        }
        return new String[] {trimmed.substring(0, at), trimmed.substring(at + 1)};
    }
}
