package com.resurgent.tev.doc.platform;

import com.resurgent.tev.doc.checklist.ChecklistDefinition;
import com.resurgent.tev.doc.checklist.ChecklistStore;
import com.resurgent.tev.doc.induce.PackStore;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import com.resurgent.tev.doc.platform.PlatformRegistry.PublishedAsset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Copies the highest published pack and checklist into a mandate folder the
 * first time a command is pointed at a registry. A missing registry is left
 * uncreated. A newer local file is kept, and a version file that already
 * exists is not rewritten.
 */
public final class PublishedCopy {

    private PublishedCopy() {}

    /** Copies packs and checklists when {@code platform} is an existing registry. */
    public static void copyInto(Path db, Path platform) throws Exception {
        copyPacks(db, platform);
        copyChecklists(db, platform);
    }

    /** Copies published packs that are newer than both the seed and the local file. */
    public static void copyPacks(Path db, Path platform) throws Exception {
        if (platform == null || !Files.isRegularFile(platform)) {
            return;
        }
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
            Map<String, PublishedAsset> published = new HashMap<>();
            for (PublishedAsset asset : registry.visiblePacks()) {
                PublishedAsset current = published.get(asset.code());
                if (current == null || PackStore.compareVersions(asset.version(), current.version()) > 0) {
                    published.put(asset.code(), asset);
                }
            }
            for (PublishedAsset asset : published.values()) {
                if (!newerThan(asset.version(), seeds.get(asset.code()))
                        || !newerThan(asset.version(), local.get(asset.code()))) {
                    continue;
                }
                store.installBytes(asset.industry(), asset.yaml());
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
            Map<String, PublishedAsset> published = new HashMap<>();
            for (PublishedAsset asset : registry.visibleChecklists()) {
                if (asset.code() == null || "in.spine".equals(asset.code())) {
                    continue;
                }
                PublishedAsset current = published.get(asset.code());
                if (current == null || PackStore.compareVersions(asset.version(), current.version()) > 0) {
                    published.put(asset.code(), asset);
                }
            }
            for (PublishedAsset asset : published.values()) {
                if (!newerThan(asset.version(), local.get(asset.code()))) {
                    continue;
                }
                Path file = store.checklistsRoot().resolve(asset.code() + "@" + asset.version() + ".yaml");
                if (Files.isRegularFile(file)) {
                    continue;
                }
                Files.createDirectories(store.checklistsRoot());
                Files.write(file, asset.yaml());
            }
        }
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
