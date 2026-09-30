package com.resurgent.tev.parser.classify;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent dictionary of learned financial terms. Grows as LLM encounters new domain-specific
 * terminology, reducing LLM calls for subsequent workbooks.
 */
public class DynamicKindTokens {
    private static final Path DICT_DIR = Paths.get(System.getProperty("user.home"), ".tev-parser", "dictionaries");
    private static final Path MONEY_DICT = DICT_DIR.resolve("money_terms.txt");
    private static final Path QUANTITY_DICT = DICT_DIR.resolve("quantity_terms.txt");
    private static final Path PERCENT_DICT = DICT_DIR.resolve("percent_terms.txt");

    private final Map<String, Set<String>> learned = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> loadedTerms = new ConcurrentHashMap<>();

    public DynamicKindTokens() {
        loadedTerms.put("money", new HashSet<>());
        loadedTerms.put("quantity", new HashSet<>());
        loadedTerms.put("percent", new HashSet<>());
        loadExistingTerms();
    }

    private void loadExistingTerms() {
        try {
            Files.createDirectories(DICT_DIR);
            loadDict("money", MONEY_DICT);
            loadDict("quantity", QUANTITY_DICT);
            loadDict("percent", PERCENT_DICT);
        } catch (IOException e) {
            System.err.println("[dynamic-dict] Warning: Could not load existing dictionary: " + e.getMessage());
        }
    }

    private void loadDict(String type, Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Set<String> terms = loadedTerms.get(type);
            Files.lines(path)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(terms::add);
        } catch (IOException e) {
            System.err.println("[dynamic-dict] Warning: Could not load " + type + " dictionary: " + e.getMessage());
        }
    }

    /**
     * Check if a term matches any loaded dictionary (static + dynamic combined).
     */
    public boolean isMoneyTerm(String term) {
        return KindTokens.MONEY_TOKEN.matcher(term).find()
                || loadedTerms.get("money").stream().anyMatch(t -> term.toLowerCase().contains(t.toLowerCase()));
    }

    public boolean isQuantityTerm(String term) {
        return KindTokens.QUANTITY_TOKEN.matcher(term).find()
                || loadedTerms.get("quantity").stream().anyMatch(t -> term.toLowerCase().contains(t.toLowerCase()));
    }

    public boolean isPercentTerm(String term) {
        return KindTokens.PERCENT_TOKEN.matcher(term).find()
                || loadedTerms.get("percent").stream().anyMatch(t -> term.toLowerCase().contains(t.toLowerCase()));
    }

    /**
     * Learn a term from LLM classification result. Called when high-confidence LLM classification occurs.
     */
    public void learnTerm(String kind, String rowLabel, String columnLabel) {
        if (kind == null || kind.isEmpty()) {
            return;
        }

        Set<String> terms = learned.computeIfAbsent(kind, k -> new HashSet<>());

        // Extract meaningful terms from labels (skip common words)
        for (String term : extractTerms(rowLabel, columnLabel)) {
            // Only learn if not already in static dictionary
            if (!isAlreadyInStatic(kind, term)) {
                terms.add(term);
            }
        }
    }

    private boolean isAlreadyInStatic(String kind, String term) {
        return switch (kind) {
            case "money" -> KindTokens.MONEY_TOKEN.matcher(term).find();
            case "quantity" -> KindTokens.QUANTITY_TOKEN.matcher(term).find();
            case "percent" -> KindTokens.PERCENT_TOKEN.matcher(term).find();
            default -> false;
        };
    }

    private List<String> extractTerms(String rowLabel, String columnLabel) {
        List<String> terms = new ArrayList<>();
        String[] commonWords = {"the", "of", "and", "or", "a", "an", "for", "in", "to", "on", "at", "by", "with", "total", "all", "each", "per", "year", "month", "day", "week"};
        Set<String> common = new HashSet<>(Arrays.asList(commonWords));

        for (String label : new String[]{rowLabel, columnLabel}) {
            if (label == null || label.isEmpty()) continue;

            // Split on whitespace and special characters, keep multi-word phrases
            String[] words = label.split("[\\s\\-/_()\\[\\]]+");
            for (String word : words) {
                word = word.trim();
                if (word.length() > 2 && !common.contains(word.toLowerCase())) {
                    terms.add(word);
                }
            }
        }

        return terms;
    }

    /**
     * Persist learned terms to disk.
     */
    public void persist() {
        try {
            Files.createDirectories(DICT_DIR);
            persistDict("money", MONEY_DICT);
            persistDict("quantity", QUANTITY_DICT);
            persistDict("percent", PERCENT_DICT);
        } catch (IOException e) {
            System.err.println("[dynamic-dict] Error persisting dictionary: " + e.getMessage());
        }
    }

    private void persistDict(String type, Path path) throws IOException {
        Set<String> terms = learned.getOrDefault(type, new HashSet<>());
        if (terms.isEmpty()) {
            return;
        }

        // Load existing terms
        Set<String> allTerms = new HashSet<>(loadedTerms.get(type));
        allTerms.addAll(terms);

        // Write back
        try (PrintWriter writer = new PrintWriter(new FileWriter(path.toFile()))) {
            writer.println("# Auto-learned financial terms for " + type);
            writer.println("# Added on " + new Date());
            writer.println();
            allTerms.stream().sorted().forEach(writer::println);
        }
    }

    /**
     * Get summary of all learned terms this run.
     */
    public Map<String, Set<String>> getLearnedTerms() {
        return new HashMap<>(learned);
    }

    /**
     * Print a report of learned terms.
     */
    public void printReport() {
        if (learned.isEmpty()) {
            System.err.println("[dynamic-dict] No new terms learned this run");
            return;
        }

        System.err.println("\n[dynamic-dict] === LEARNED TERMS ===");
        for (String kind : new String[]{"money", "quantity", "percent"}) {
            Set<String> terms = learned.getOrDefault(kind, new HashSet<>());
            if (!terms.isEmpty()) {
                System.err.println("[dynamic-dict] " + kind.toUpperCase() + " (" + terms.size() + " new terms):");
                terms.stream().sorted().forEach(t -> System.err.println("  - " + t));
            }
        }
        System.err.println("[dynamic-dict] ===");
        System.err.flush();
    }
}
