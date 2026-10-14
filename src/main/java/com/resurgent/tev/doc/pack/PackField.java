package com.resurgent.tev.doc.pack;

import com.resurgent.tev.doc.extract.ValueKind;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One field leaf in a doc type pack. Paths are living ontology strings
 * (Architecture §9); {@code pathRoot} must be a frozen spine root.
 */
public record PackField(
        String key,
        String path,
        String pathRoot,
        ValueKind valueKind,
        boolean required,
        List<String> slots,
        String promptHint,
        List<String> synonyms) {

    public PackField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(pathRoot, "pathRoot");
        Objects.requireNonNull(valueKind, "valueKind");
        slots = slots == null ? List.of() : List.copyOf(slots);
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    /** Convenience factory for a required leaf without slots. */
    public static PackField required(String key, String path, String pathRoot, ValueKind kind) {
        return new PackField(key, path, pathRoot, kind, true, List.of(), null, List.of());
    }

    /** Convenience factory for an optional leaf without slots. */
    public static PackField optional(String key, String path, String pathRoot, ValueKind kind) {
        return new PackField(key, path, pathRoot, kind, false, List.of(), null, List.of());
    }

    /** Wire name of {@code pathRoot} (lowercase spine root). */
    public String pathRootWire() {
        return pathRoot.toLowerCase(Locale.ROOT);
    }
}
