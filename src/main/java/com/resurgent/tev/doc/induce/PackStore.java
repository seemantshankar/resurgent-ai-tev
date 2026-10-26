package com.resurgent.tev.doc.induce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned pack store at {@code {db-parent}/packs/}:
 * {@code {industry_tag}/{code}@{version}.yaml} plus {@code registry.json}.
 * Re-induction of the same {@code (industry_tag, code)} bumps semver
 * (major when required fields change, minor otherwise). Old packs stay readable.
 */
public final class PackStore {

    /** Registry row (mirrors SQLite {@code doc_type_pack} in V47). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackRegistryEntry(
            String code,
            String version,
            String family,
            String fileRole,
            String industryTag,
            String fileHash,
            String yamlPath,
            List<String> sourceMandates,
            String inductionRunId,
            String status,
            String createdAt,
            List<String> sourceDocuments) {

        public PackRegistryEntry {
            sourceMandates = sourceMandates == null ? List.of() : List.copyOf(sourceMandates);
            sourceDocuments = sourceDocuments == null ? List.of() : List.copyOf(sourceDocuments);
            status = status == null ? "published" : status;
            createdAt = createdAt == null ? Instant.now().toString() : createdAt;
        }

        /** Registry entry without the M3 source documents (field induction). */
        public PackRegistryEntry(String code, String version, String family, String fileRole,
                String industryTag, String fileHash, String yamlPath, List<String> sourceMandates,
                String inductionRunId, String status, String createdAt) {
            this(code, version, family, fileRole, industryTag, fileHash, yamlPath, sourceMandates,
                    inductionRunId, status, createdAt, List.of());
        }
    }

    /** Result of a successful publish. */
    public record PublishedPack(
            DocTypePack pack,
            String industryTag,
            String version,
            Path yamlPath,
            String fileHash,
            PackRegistryEntry entry) {}

    /** Input to {@link #publish}. */
    public record PublishRequest(
            DocTypePack pack,
            String industryTag,
            String fileHash,
            List<String> sourceMandates,
            String inductionRunId,
            List<String> sourceDocuments) {

        public PublishRequest {
            Objects.requireNonNull(pack, "pack");
            sourceMandates = sourceMandates == null ? List.of() : List.copyOf(sourceMandates);
            sourceDocuments = sourceDocuments == null ? List.of() : List.copyOf(sourceDocuments);
        }

        /** Publish request without recorded source documents (field induction). */
        public PublishRequest(DocTypePack pack, String industryTag, String fileHash,
                List<String> sourceMandates, String inductionRunId) {
            this(pack, industryTag, fileHash, sourceMandates, inductionRunId, List.of());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    private final Path packsRoot;
    private final Path registryPath;

    /**
     * @param packsRoot directory that will hold {@code {industry}/code@version.yaml}
     *                  (typically {@code {db-parent}/packs})
     */
    public PackStore(Path packsRoot) {
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot").toAbsolutePath().normalize();
        this.registryPath = this.packsRoot.resolve("registry.json");
    }

    public Path packsRoot() {
        return packsRoot;
    }

    public Path registryPath() {
        return registryPath;
    }

    /**
     * Publishes a new version of a pack for {@code (industryTag, code)}.
     * Never overwrites an existing {@code code@version} file.
     */
    public synchronized PublishedPack publish(PublishRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        DocTypePack pack = request.pack();
        String industryTag = normalizeTag(request.industryTag());
        boolean requiredChanged = requiredFieldsChanged(industryTag, pack);
        String nextVersion = nextVersion(industryTag, pack.code(), requiredChanged);
        DocTypePack versioned = withVersion(pack, nextVersion);
        byte[] yaml = PackYamlWriter.writeBytes(versioned);
        String fileHash = request.fileHash() != null && !request.fileHash().isBlank()
                ? request.fileHash()
                : sha256Hex(yaml);

        Path industryDir = packsRoot.resolve(industryTag);
        Files.createDirectories(industryDir);
        Path yamlPath = industryDir.resolve(versioned.code() + "@" + nextVersion + ".yaml");
        if (Files.exists(yamlPath)) {
            throw new IOException("pack already published: " + yamlPath);
        }
        Files.write(yamlPath, yaml);

        PackRegistryEntry entry = new PackRegistryEntry(
                versioned.code(),
                nextVersion,
                versioned.family(),
                versioned.fileRole(),
                industryTag,
                fileHash,
                packsRoot.relativize(yamlPath).toString().replace('\\', '/'),
                request.sourceMandates(),
                request.inductionRunId(),
                "published",
                Instant.now().toString(),
                request.sourceDocuments());

        List<PackRegistryEntry> entries = new ArrayList<>(entries());
        entries.add(entry);
        writeRegistry(entries);

        return new PublishedPack(versioned, industryTag, nextVersion, yamlPath, fileHash, entry);
    }

    /**
     * Copies one already judged version into this store. The same version file
     * is never rewritten; a path that already exists is left as it is.
     */
    public synchronized PublishedPack installCopy(PublishRequest request, String version)
            throws IOException {
        Objects.requireNonNull(request, "request");
        String industryTag = normalizeTag(request.industryTag());
        String resolved = version == null || version.isBlank() ? "1.0.0" : version;
        DocTypePack versioned = withVersion(request.pack(), resolved);
        Path industryDir = packsRoot.resolve(industryTag);
        Path yamlPath = industryDir.resolve(versioned.code() + "@" + resolved + ".yaml");
        if (Files.isRegularFile(yamlPath)) {
            byte[] existing = Files.readAllBytes(yamlPath);
            return new PublishedPack(versioned, industryTag, resolved, yamlPath,
                    sha256Hex(existing), null);
        }
        byte[] yaml = PackYamlWriter.writeBytes(versioned);
        String fileHash = request.fileHash() != null && !request.fileHash().isBlank()
                ? request.fileHash()
                : sha256Hex(yaml);
        Files.createDirectories(industryDir);
        Files.write(yamlPath, yaml);
        PackRegistryEntry entry = new PackRegistryEntry(
                versioned.code(),
                resolved,
                versioned.family(),
                versioned.fileRole(),
                industryTag,
                fileHash,
                packsRoot.relativize(yamlPath).toString().replace('\\', '/'),
                request.sourceMandates(),
                request.inductionRunId(),
                "published",
                Instant.now().toString(),
                request.sourceDocuments());
        List<PackRegistryEntry> entries = new ArrayList<>(entries());
        entries.add(entry);
        writeRegistry(entries);
        return new PublishedPack(versioned, industryTag, resolved, yamlPath, fileHash, entry);
    }

    /**
     * Writes published YAML bytes into this store when that version file is absent.
     * An existing file is left untouched, bytes and all.
     */
    public synchronized void installBytes(String industryTag, byte[] yaml) throws IOException {
        DocTypePack pack;
        try {
            pack = PackLoader.load(new java.io.ByteArrayInputStream(yaml), "published-copy");
        } catch (PackLoader.PackValidationException e) {
            throw new IOException("published pack is not readable: " + e.getMessage(), e);
        }
        String tag = normalizeTag(industryTag == null || industryTag.isBlank()
                ? (pack.industryTags().isEmpty() ? "general" : pack.industryTags().get(0))
                : industryTag);
        Path industryDir = packsRoot.resolve(tag);
        Path yamlPath = industryDir.resolve(pack.code() + "@" + pack.version() + ".yaml");
        if (Files.isRegularFile(yamlPath)) {
            return;
        }
        Files.createDirectories(industryDir);
        Files.write(yamlPath, yaml);
        PackRegistryEntry entry = new PackRegistryEntry(
                pack.code(),
                pack.version(),
                pack.family(),
                pack.fileRole(),
                tag,
                sha256Hex(yaml),
                packsRoot.relativize(yamlPath).toString().replace('\\', '/'),
                List.of(),
                "published-copy",
                "published",
                Instant.now().toString(),
                List.of());
        List<PackRegistryEntry> entries = new ArrayList<>(entries());
        entries.add(entry);
        writeRegistry(entries);
    }

    /** Highest semver first. Missing versions compare as {@code 0.0.0}. */
    public static int compareVersions(String left, String right) {
        int[] a = parseSemver(left);
        int[] b = parseSemver(right);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Next version after {@code current}. A blank current is the first publish,
     * {@code 1.0.0}. A required-field change bumps the major; otherwise the minor.
     */
    public static String bumpVersion(String current, boolean requiredFieldsChanged) {
        if (current == null || current.isBlank()) {
            return "1.0.0";
        }
        int[] parts = parseSemver(current);
        if (requiredFieldsChanged) {
            return (parts[0] + 1) + ".0.0";
        }
        return parts[0] + "." + (parts[1] + 1) + ".0";
    }

    /**
     * Computes the next semver for {@code (industryTag, code)}.
     * First publish → {@code 1.0.0}; required-field change → major; else minor.
     */
    public String nextVersion(String industryTag, String code, boolean requiredFieldsChanged) {
        String tag = normalizeTag(industryTag);
        Optional<PackRegistryEntry> latest = latestEntry(tag, code);
        if (latest.isEmpty()) {
            return "1.0.0";
        }
        int[] parts = parseSemver(latest.get().version());
        if (requiredFieldsChanged) {
            return (parts[0] + 1) + ".0.0";
        }
        return parts[0] + "." + (parts[1] + 1) + ".0";
    }

    /**
     * True when the pack's required-field key set differs from the latest published
     * pack for the same {@code (industryTag, code)}.
     */
    public boolean requiredFieldsChanged(String industryTag, DocTypePack pack) {
        String tag = normalizeTag(industryTag);
        Optional<PackRegistryEntry> latest = latestEntry(tag, pack.code());
        if (latest.isEmpty()) {
            return true;
        }
        Optional<DocTypePack> prev = loadByEntry(latest.get());
        if (prev.isEmpty()) {
            return true;
        }
        Set<String> prevKeys = new HashSet<>();
        for (var f : prev.get().fields()) {
            if (f.required()) {
                prevKeys.add(f.key());
            }
        }
        Set<String> newKeys = new HashSet<>();
        for (var f : pack.fields()) {
            if (f.required()) {
                newKeys.add(f.key());
            }
        }
        return !prevKeys.equals(newKeys);
    }

    /** Loads a pack by code + version from the registry. */
    public Optional<DocTypePack> load(String code, String version) {
        return entries().stream()
                .filter(e -> e.code().equals(code) && e.version().equals(version))
                .findFirst()
                .flatMap(this::loadByEntry);
    }

    /** Latest published pack for {@code (industryTag, code)}. */
    public Optional<DocTypePack> latest(String industryTag, String code) {
        return latestEntry(normalizeTag(industryTag), code).flatMap(this::loadByEntry);
    }

    /** Latest registry entry for {@code (industryTag, code)}, if any. */
    public Optional<PackRegistryEntry> latestEntry(String industryTag, String code) {
        String tag = normalizeTag(industryTag);
        List<PackRegistryEntry> all = entries();
        for (int i = all.size() - 1; i >= 0; i--) {
            PackRegistryEntry e = all.get(i);
            if (e.code().equals(code) && tag.equals(e.industryTag()) && "published".equals(e.status())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Latest published registry entry for {@code code} across industry tags, in publish
     * order — the version a new clause type is added to when no tag is known upfront.
     */
    public Optional<PackRegistryEntry> latestEntryForCode(String code) {
        List<PackRegistryEntry> all = entries();
        for (int i = all.size() - 1; i >= 0; i--) {
            PackRegistryEntry e = all.get(i);
            if (e.code().equals(code) && "published".equals(e.status())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Latest published pack for {@code code} across industry tags. */
    public Optional<DocTypePack> latestForCode(String code) {
        return latestEntryForCode(code).flatMap(this::loadByEntry);
    }

    /** All registry entries in publish order. */
    public List<PackRegistryEntry> entries() {
        if (!Files.isRegularFile(registryPath)) {
            return List.of();
        }
        try {
            RegistryDoc doc = MAPPER.readValue(registryPath.toFile(), RegistryDoc.class);
            return doc.entries == null ? List.of() : List.copyOf(doc.entries);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read pack registry " + registryPath + ": "
                    + e.getMessage(), e);
        }
    }

    /** Reloads every published YAML so old packs stay readable. */
    public List<DocTypePack> loadAll() {
        List<DocTypePack> out = new ArrayList<>();
        for (PackRegistryEntry e : entries()) {
            if (!"published".equals(e.status())) {
                continue;
            }
            loadByEntry(e).ifPresent(out::add);
        }
        return out;
    }

    /** SHA-256 hex of the given bytes. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Optional<DocTypePack> loadByEntry(PackRegistryEntry e) {
        if (e.yamlPath() == null || e.yamlPath().isBlank()) {
            return Optional.empty();
        }
        Path file = packsRoot.resolve(e.yamlPath()).normalize();
        if (!file.startsWith(packsRoot) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(PackLoader.load(file));
        } catch (PackLoader.PackValidationException ex) {
            return Optional.empty();
        }
    }

    private void writeRegistry(List<PackRegistryEntry> entries) throws IOException {
        Files.createDirectories(packsRoot);
        RegistryDoc doc = new RegistryDoc();
        doc.entries = entries;
        MAPPER.writeValue(registryPath.toFile(), doc);
    }

    private static String normalizeTag(String industryTag) {
        String t = industryTag == null || industryTag.isBlank()
                ? "general"
                : industryTag.trim().toLowerCase(Locale.ROOT);
        return t.replaceAll("[^a-z0-9_]+", "_");
    }

    private static int[] parseSemver(String version) {
        String[] bits = (version == null ? "0.0.0" : version).split("\\.");
        int[] out = new int[] {0, 0, 0};
        for (int i = 0; i < Math.min(3, bits.length); i++) {
            try {
                out[i] = Integer.parseInt(bits[i].trim());
            } catch (NumberFormatException ignored) {
                // leave 0
            }
        }
        return out;
    }

    private static DocTypePack withVersion(DocTypePack pack, String version) {
        return new DocTypePack(
                pack.code(),
                version,
                pack.family(),
                pack.fileRole(),
                pack.industryTags(),
                pack.identitySignals(),
                pack.fields(),
                pack.tables(),
                pack.checklist(),
                pack.reportSections(),
                pack.documentDateKind(),
                pack.clauseSection(),
                pack.passages());
    }

    /** registry.json document shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RegistryDoc {
        public List<PackRegistryEntry> entries = new ArrayList<>();
    }
}
