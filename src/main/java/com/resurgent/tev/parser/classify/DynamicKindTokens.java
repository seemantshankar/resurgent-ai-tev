package com.resurgent.tev.parser.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent dictionary of row-label meanings the LLM has settled, scoped to the schedule
 * family of the region the label sits in. "Fire Fighting Work" is money inside an expenses
 * schedule; the same words elsewhere may not be, so the key is
 * {@code (scheduleFamily, canonicalRowPhrase)}, never a bare word.
 *
 * <p>Learning is two-stage. {@link #observe} stages one observation per <em>row</em>
 * (workbook, sheet, row) in {@code learned_candidates.json}; {@link #persist} re-evaluates every
 * candidate and publishes only those with enough independent, consistent evidence to
 * {@code terms.json}. Contested keys go to {@code quarantine.json}. Lookups read only the
 * terms loaded at construction, so one run's results never depend on its own learning order.
 */
public class DynamicKindTokens {

    static final String DIR_PROPERTY = "tev.dictionary.dir";
    static final String LEARN_PROPERTY = "tev.dictionary.learn";
    static final int SCHEMA_VERSION = 2;

    static final Set<String> LEARNABLE_KINDS = Set.of(
            ReadingOutcome.MONEY,
            ReadingOutcome.PERCENT,
            ReadingOutcome.QUANTITY,
            ReadingOutcome.COUNT,
            ReadingOutcome.RATIO);

    /** Promote when this many distinct workbooks agree, or ... */
    static final int MIN_WORKBOOKS = 2;
    /** ... this many rows across at least two sheets agree. */
    static final int MIN_ROWS = 3;
    static final double MIN_ROW_PURITY = 0.80;
    static final double MIN_DOMINANCE = 0.95;
    static final int MAX_CANDIDATES = 20_000;
    static final int MAX_TERMS = 5_000;
    private static final int MAX_SAMPLES = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();

    /** One promoted dictionary entry. */
    public record LearnedTerm(String family, String phrase, String kind, String unit) {}

    private final Path dir;
    private final boolean learnEnabled;
    private final Map<String, LearnedTerm> terms = new ConcurrentHashMap<>();
    private final Map<String, Candidate> delta = new ConcurrentHashMap<>();
    private final Set<String> promotedThisRun = ConcurrentHashMap.newKeySet();
    private volatile int stagedCount;
    private volatile int contestedCount;

    public DynamicKindTokens() {
        this(defaultDir());
    }

    public DynamicKindTokens(Path dir) {
        this.dir = dir;
        this.learnEnabled = !"false".equalsIgnoreCase(System.getProperty(LEARN_PROPERTY));
        retireV1Files();
        loadTerms();
    }

    private static Path defaultDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".tev-parser", "dictionaries");
    }

    // ---- lookup ---------------------------------------------------------------------------

    /** The learned meaning of this row label inside this schedule family, if any. */
    public Optional<LearnedTerm> lookup(String family, String rawRowLabel) {
        String f = normalizeFamily(family);
        if (f.isEmpty() || terms.isEmpty()) {
            return Optional.empty();
        }
        String phrase = LabelPhrase.canonical(rawRowLabel);
        if (phrase.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(terms.get(key(f, phrase)));
    }

    // ---- learning -------------------------------------------------------------------------

    /**
     * Stage one LLM-settled cell as evidence for its row label. Returns whether it was staged;
     * a rejected observation (unknown family, non-learnable kind or phrase) changes nothing.
     */
    public boolean observe(
            String family,
            String rawRowLabel,
            String kind,
            String unit,
            String workbookKey,
            String sheetName,
            int rowNum) {
        if (!learnEnabled || kind == null || !LEARNABLE_KINDS.contains(kind)) {
            return false;
        }
        String f = normalizeFamily(family);
        if (f.isEmpty() || workbookKey == null || workbookKey.isBlank() || sheetName == null) {
            return false;
        }
        String phrase = LabelPhrase.canonical(rawRowLabel);
        if (!LabelPhrase.isLearnable(phrase)) {
            return false;
        }
        Candidate c = delta.computeIfAbsent(key(f, phrase), k -> new Candidate(f, phrase));
        c.record(workbookKey.replace('|', '/') + "|" + sheetName.replace('|', '/') + "|" + rowNum, kind, unit, rawRowLabel);
        return true;
    }

    // ---- persistence ----------------------------------------------------------------------

    /** Merge this run's observations into the shared files. Never throws. */
    public void persist() {
        if (!learnEnabled || delta.isEmpty()) {
            return;
        }
        JVM_LOCK.lock();
        try {
            Files.createDirectories(dir);
            try (FileChannel channel = FileChannel.open(
                            dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Map<String, Candidate> all = readCandidates();
                for (Map.Entry<String, Candidate> e : delta.entrySet()) {
                    Candidate existing = all.get(e.getKey());
                    if (existing != null) {
                        existing.merge(e.getValue());
                    } else if (all.size() < MAX_CANDIDATES) {
                        all.put(e.getKey(), e.getValue());
                    } else {
                        System.err.println("[dynamic-dict] Candidate cap reached; not staging " + e.getKey());
                    }
                }
                publish(all);
                delta.clear();
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[dynamic-dict] Error persisting dictionary: " + e.getMessage());
        } finally {
            JVM_LOCK.unlock();
        }
    }

    private void publish(Map<String, Candidate> all) throws IOException {
        Map<String, LearnedTerm> promoted = new TreeMap<>();
        List<Candidate> contested = new ArrayList<>();
        int staged = 0;
        for (Map.Entry<String, Candidate> e : all.entrySet()) {
            Verdict v = e.getValue().evaluate();
            switch (v.state) {
                case PROMOTED -> {
                    if (promoted.size() < MAX_TERMS) {
                        promoted.put(e.getKey(), new LearnedTerm(
                                e.getValue().family, e.getValue().phrase, v.kind, v.unit));
                    }
                }
                case CONTESTED -> contested.add(e.getValue());
                default -> staged++;
            }
        }
        for (String k : promoted.keySet()) {
            if (!terms.containsKey(k)) {
                promotedThisRun.add(k);
            }
        }
        stagedCount = staged;
        contestedCount = contested.size();

        ObjectNode candidatesRoot = MAPPER.createObjectNode();
        candidatesRoot.put("schemaVersion", SCHEMA_VERSION);
        ObjectNode candidatesNode = candidatesRoot.putObject("candidates");
        new TreeMap<>(all).forEach((k, c) -> candidatesNode.set(k, c.toJson()));
        writeAtomically(dir.resolve("learned_candidates.json"), candidatesRoot);

        ObjectNode termsRoot = MAPPER.createObjectNode();
        termsRoot.put("schemaVersion", SCHEMA_VERSION);
        ArrayNode termsArray = termsRoot.putArray("terms");
        for (LearnedTerm t : promoted.values()) {
            ObjectNode n = termsArray.addObject();
            n.put("family", t.family());
            n.put("phrase", t.phrase());
            n.put("kind", t.kind());
            n.put("unit", t.unit());
        }
        writeAtomically(dir.resolve("terms.json"), termsRoot);

        ObjectNode quarantineRoot = MAPPER.createObjectNode();
        quarantineRoot.put("schemaVersion", SCHEMA_VERSION);
        ArrayNode quarantineArray = quarantineRoot.putArray("contested");
        contested.stream()
                .sorted((a, b) -> key(a.family, a.phrase).compareTo(key(b.family, b.phrase)))
                .forEach(c -> quarantineArray.add(c.toJson().put("family", c.family).put("phrase", c.phrase)));
        writeAtomically(dir.resolve("quarantine.json"), quarantineRoot);
    }

    private static void writeAtomically(Path target, JsonNode content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, Candidate> readCandidates() {
        Path path = dir.resolve("learned_candidates.json");
        Map<String, Candidate> out = new HashMap<>();
        JsonNode root = readJson(path);
        if (root == null || !root.path("candidates").isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.get("candidates").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Candidate c = Candidate.fromJson(e.getValue());
            if (c != null) {
                out.put(e.getKey(), c);
            }
        }
        return out;
    }

    private void loadTerms() {
        JsonNode root = readJson(dir.resolve("terms.json"));
        if (root == null || !root.path("terms").isArray()) {
            return;
        }
        for (JsonNode n : root.get("terms")) {
            String family = normalizeFamily(n.path("family").asText(""));
            String phrase = n.path("phrase").asText("");
            String kind = n.path("kind").asText("");
            if (family.isEmpty() || !LabelPhrase.isLearnable(phrase) || !LEARNABLE_KINDS.contains(kind)) {
                continue;
            }
            terms.put(key(family, phrase), new LearnedTerm(family, phrase, kind, n.path("unit").asText("")));
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
    }

    /** Parsed file, or {@code null} when absent. A corrupt file is set aside, never overwritten. */
    private JsonNode readJson(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            System.err.println("[dynamic-dict] Warning: could not read " + path.getFileName() + ": " + e.getMessage());
            try {
                Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis()));
            } catch (IOException ignored) {
                // Leave it in place; the next persist will still replace it atomically.
            }
            return null;
        }
    }

    /** The v1 word lists are junk (every label word under the cell's kind); keep, never read. */
    private void retireV1Files() {
        for (String name : new String[] {"money_terms.txt", "quantity_terms.txt", "percent_terms.txt"}) {
            Path old = dir.resolve(name);
            if (!Files.exists(old)) {
                continue;
            }
            Path bak = dir.resolve(name + ".v1.bak");
            if (Files.exists(bak)) {
                bak = dir.resolve(name + "." + System.currentTimeMillis() + ".v1.bak");
            }
            try {
                Files.move(old, bak);
                System.err.println("[dynamic-dict] Retired v1 dictionary " + name + " -> " + bak.getFileName());
            } catch (IOException e) {
                System.err.println("[dynamic-dict] Warning: could not retire " + name + ": " + e.getMessage());
            }
        }
    }

    // ---- reporting ------------------------------------------------------------------------

    /** Keys promoted to the live dictionary by this run's {@link #persist}. */
    public Set<String> getPromotedThisRun() {
        return new TreeSet<>(promotedThisRun);
    }

    public int termCount() {
        return terms.size();
    }

    public void printReport() {
        if (!learnEnabled) {
            System.err.println("[dynamic-dict] Learning disabled (" + LEARN_PROPERTY + "=false)");
            return;
        }
        System.err.println("[dynamic-dict] live terms loaded=" + terms.size()
                + " promoted this run=" + promotedThisRun.size()
                + " staged=" + stagedCount + " contested=" + contestedCount);
        promotedThisRun.stream().sorted().forEach(k -> System.err.println("  + " + k));
        System.err.flush();
    }

    // ---- keys -----------------------------------------------------------------------------

    private static String normalizeFamily(String family) {
        return family == null ? "" : family.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String key(String family, String phrase) {
        return family + "|" + phrase;
    }

    // ---- candidate evidence ---------------------------------------------------------------

    private enum State { STAGED, PROMOTED, CONTESTED }

    private record Verdict(State state, String kind, String unit) {}

    /** Evidence for one (family, phrase): per observed row, how many cells got each kind. */
    private static final class Candidate {
        final String family;
        final String phrase;
        final Map<String, Map<String, Integer>> rows = new LinkedHashMap<>();
        final Map<String, Map<String, Integer>> units = new LinkedHashMap<>();
        final Set<String> samples = new LinkedHashSet<>();
        String firstSeen = Instant.now().toString();
        String lastSeen = firstSeen;

        Candidate(String family, String phrase) {
            this.family = family;
            this.phrase = phrase;
        }

        synchronized void record(String rowId, String kind, String unit, String rawLabel) {
            rows.computeIfAbsent(rowId, r -> new LinkedHashMap<>()).merge(kind, 1, Integer::sum);
            if (unit != null && !unit.isBlank()) {
                units.computeIfAbsent(kind, k -> new LinkedHashMap<>()).merge(unit.trim(), 1, Integer::sum);
            }
            if (rawLabel != null && samples.size() < MAX_SAMPLES) {
                String s = rawLabel.strip();
                samples.add(s.length() > 80 ? s.substring(0, 80) : s);
            }
            lastSeen = Instant.now().toString();
        }

        synchronized void merge(Candidate other) {
            other.rows.forEach((row, kinds) -> kinds.forEach((kind, n) ->
                    rows.computeIfAbsent(row, r -> new LinkedHashMap<>()).merge(kind, n, Math::max)));
            other.units.forEach((kind, byUnit) -> byUnit.forEach((unit, n) ->
                    units.computeIfAbsent(kind, k -> new LinkedHashMap<>()).merge(unit, n, Math::max)));
            for (String s : other.samples) {
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(s);
                }
            }
            lastSeen = other.lastSeen;
        }

        synchronized Verdict evaluate() {
            Map<String, Integer> rowsByKind = new HashMap<>();
            Map<String, Set<String>> workbooksByKind = new HashMap<>();
            Map<String, Set<String>> sheetsByKind = new HashMap<>();
            for (Map.Entry<String, Map<String, Integer>> row : rows.entrySet()) {
                int total = row.getValue().values().stream().mapToInt(Integer::intValue).sum();
                Map.Entry<String, Integer> top = row.getValue().entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (top == null || total == 0 || (double) top.getValue() / total < MIN_ROW_PURITY) {
                    continue; // the LLM disagreed with itself across this row's cells
                }
                String kind = top.getKey();
                String[] id = row.getKey().split("\\|", 3);
                rowsByKind.merge(kind, 1, Integer::sum);
                workbooksByKind.computeIfAbsent(kind, k -> new HashSet<>()).add(id[0]);
                sheetsByKind.computeIfAbsent(kind, k -> new HashSet<>()).add(id[0] + "|" + id[1]);
            }
            int totalRows = rowsByKind.values().stream().mapToInt(Integer::intValue).sum();
            if (totalRows == 0) {
                return new Verdict(State.STAGED, null, "");
            }
            Map.Entry<String, Integer> dominant = rowsByKind.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow();
            String kind = dominant.getKey();
            int otherRows = totalRows - dominant.getValue();
            double dominance = (double) dominant.getValue() / totalRows;
            if (otherRows > 0 && (otherRows >= 2 || dominance < MIN_DOMINANCE)) {
                return new Verdict(State.CONTESTED, null, "");
            }
            boolean supported = workbooksByKind.get(kind).size() >= MIN_WORKBOOKS
                    || (dominant.getValue() >= MIN_ROWS && sheetsByKind.get(kind).size() >= 2);
            if (!supported || otherRows > 0) {
                return new Verdict(State.STAGED, kind, "");
            }
            return new Verdict(State.PROMOTED, kind, dominantUnit(kind));
        }

        private String dominantUnit(String kind) {
            if (!ReadingOutcome.QUANTITY.equals(kind) && !ReadingOutcome.COUNT.equals(kind)) {
                return "";
            }
            return units.getOrDefault(kind, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");
        }

        synchronized ObjectNode toJson() {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("family", family);
            n.put("phrase", phrase);
            ObjectNode rowsNode = n.putObject("rows");
            rows.forEach((row, kinds) -> {
                ObjectNode k = rowsNode.putObject(row);
                kinds.forEach(k::put);
            });
            ObjectNode unitsNode = n.putObject("units");
            units.forEach((kind, byUnit) -> {
                ObjectNode u = unitsNode.putObject(kind);
                byUnit.forEach(u::put);
            });
            ArrayNode s = n.putArray("samples");
            samples.forEach(s::add);
            n.put("firstSeen", firstSeen);
            n.put("lastSeen", lastSeen);
            return n;
        }

        static Candidate fromJson(JsonNode n) {
            String family = n.path("family").asText("");
            String phrase = n.path("phrase").asText("");
            if (family.isEmpty() || phrase.isEmpty() || !n.path("rows").isObject()) {
                return null;
            }
            Candidate c = new Candidate(family, phrase);
            n.get("rows").fields().forEachRemaining(row -> {
                Map<String, Integer> kinds = new LinkedHashMap<>();
                row.getValue().fields().forEachRemaining(k -> kinds.put(k.getKey(), k.getValue().asInt(0)));
                c.rows.put(row.getKey(), kinds);
            });
            n.path("units").fields().forEachRemaining(u -> {
                Map<String, Integer> byUnit = new LinkedHashMap<>();
                u.getValue().fields().forEachRemaining(e -> byUnit.put(e.getKey(), e.getValue().asInt(0)));
                c.units.put(u.getKey(), byUnit);
            });
            n.path("samples").forEach(s -> c.samples.add(s.asText()));
            c.firstSeen = n.path("firstSeen").asText(c.firstSeen);
            c.lastSeen = n.path("lastSeen").asText(c.lastSeen);
            return c;
        }
    }
}
