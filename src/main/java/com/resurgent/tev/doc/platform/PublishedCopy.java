package com.resurgent.tev.doc.platform;

import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.platform.PlatformRegistry.PublishedVersion;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the highest published pack and checklist into a mandate folder the
 * first time a command is pointed at a registry. A missing registry is left
 * uncreated. A newer local file is kept, and a version file that already
 * exists is not rewritten. A published pack is copied only when its industry
 * is one of this mandate's industries. A mandate that has not recorded an
 * industry yet still receives a code when only one industry has published it.
 */
public final class PublishedCopy {

    private PublishedCopy() {}

    /** Copies packs and checklists when {@code platform} is an existing registry. */
    public static void copyInto(Path db, Path platform) throws Exception {
        copyInto(db, platform, 0L);
    }

    /** Same as {@link #copyInto(Path, Path)} for one mandate's industries. */
    public static void copyInto(Path db, Path platform, long mandateId) throws Exception {
        copyPacks(db, platform, mandateId);
        copyChecklists(db, platform);
    }

    /** Copies published packs that are newer than both the seed and the local file. */
    public static void copyPacks(Path db, Path platform) throws Exception {
        copyPacks(db, platform, 0L);
    }

    /**
     * Same as {@link #copyPacks(Path, Path)}, selecting a published pack only
     * when its industry is one of this mandate's industries.
     */
    public static void copyPacks(Path db, Path platform, long mandateId) throws Exception {
        if (platform == null || !Files.isRegularFile(platform)) {
            return;
        }
        Set<String> mandateIndustries = mandateIndustries(db, mandateId);
        Map<String, String> seeds = new HashMap<>();
        for (DocTypePack pack : PackLoader.loadSeedPacks()) {
            seeds.put(pack.code(), pack.version());
        }
        Path root = db.toAbsolutePath().getParent() == null
                ? Path.of("packs")
                : db.toAbsolutePath().getParent().resolve("packs");
        PackStore store = new PackStore(root);
        Map<String, String> local = new HashMap<>();
        for (DocTypePack pack : store.loadAll()) {
            keepHighest(local, pack.code(), pack.version());
        }
        try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
            Map<String, List<PublishedVersion>> byCode = new HashMap<>();
            for (PublishedVersion version : registry.publishedPacks()) {
                if (version.code() == null) {
                    continue;
                }
                byCode.computeIfAbsent(version.code(), ignored -> new ArrayList<>()).add(version);
            }
            for (List<PublishedVersion> versions : byCode.values()) {
                PublishedVersion chosen = choosePack(versions, mandateIndustries);
                if (chosen == null
                        || !newerThan(chosen.version(), seeds.get(chosen.code()))
                        || !newerThan(chosen.version(), local.get(chosen.code()))) {
                    continue;
                }
                store.installBytes(chosen.industry(), chosen.yaml());
            }
        }
    }

    /** Copies published industry checklists. The country spine stays the shipped file. */
    public static void copyChecklists(Path db, Path platform) throws Exception {
        if (platform == null || !Files.isRegularFile(platform)) {
            return;
        }
        ChecklistStore store = new ChecklistStore(ChecklistStore.storeRoot(db));
        Map<String, String> local = new HashMap<>();
        for (ChecklistDefinition definition : store.loadAll()) {
            keepHighest(local, definition.code(), definition.version());
        }
        try (PlatformRegistry registry = PlatformRegistry.open(platform)) {
            Map<String, PublishedVersion> published = new HashMap<>();
            for (PublishedVersion version : registry.publishedChecklists()) {
                if (version.code() == null || ChecklistDefinition.isSpineCode(version.code())) {
                    continue;
                }
                published.put(version.code(), preferHigher(published.get(version.code()), version));
            }
            for (PublishedVersion version : published.values()) {
                if (!newerThan(version.version(), local.get(version.code()))) {
                    continue;
                }
                Path file = store.checklistsRoot().resolve(version.code() + "@" + version.version() + ".yaml");
                if (Files.isRegularFile(file)) {
                    continue;
                }
                Files.createDirectories(store.checklistsRoot());
                Files.write(file, version.yaml());
            }
        }
    }

    /**
     * The highest version of this code the mandate may copy. A mandate with
     * recorded industries sees only those. A mandate with none does not guess
     * between two industries' packs.
     */
    private static PublishedVersion choosePack(List<PublishedVersion> versions,
            Set<String> mandateIndustries) {
        if (mandateIndustries.isEmpty() && industriesOf(versions).size() > 1) {
            return null;
        }
        PublishedVersion chosen = null;
        for (PublishedVersion version : versions) {
            if (!mandateIndustries.isEmpty()
                    && !mandateIndustries.contains(industryKey(version.industry()))) {
                continue;
            }
            chosen = preferHigher(chosen, version);
        }
        return chosen;
    }

    private static Set<String> industriesOf(List<PublishedVersion> versions) {
        Set<String> industries = new LinkedHashSet<>();
        for (PublishedVersion version : versions) {
            String key = industryKey(version.industry());
            if (!key.isEmpty()) {
                industries.add(key);
            }
        }
        return industries;
    }

    private static Set<String> mandateIndustries(Path db, long mandateId) throws SQLException {
        if (mandateId <= 0 || db == null || !Files.isRegularFile(db)) {
            return Set.of();
        }
        try (WorkspaceDatabase workspace = WorkspaceDatabase.open(db)) {
            String joined = PlatformRegistry.industries(workspace.connection(), mandateId);
            if (joined == null || joined.isBlank()) {
                return Set.of();
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String part : joined.split(",")) {
                String key = industryKey(part);
                if (!key.isEmpty()) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }

    /** Same industry whether the mandate stored a space or the pack stored an underscore. */
    private static String industryKey(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static PublishedVersion preferHigher(PublishedVersion current, PublishedVersion candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || PackStore.compareVersions(candidate.version(), current.version()) > 0) {
            return candidate;
        }
        return current;
    }

    private static void keepHighest(Map<String, String> versions, String code, String version) {
        String current = versions.get(code);
        if (current == null || PackStore.compareVersions(version, current) > 0) {
            versions.put(code, version);
        }
    }

    private static boolean newerThan(String candidate, String current) {
        return current == null || PackStore.compareVersions(candidate, current) > 0;
    }
}
