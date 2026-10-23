package com.resurgent.tev.doc.layout.ocr;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * English plus the Indic packs layout asks Tesseract for.
 * {@code TEV_OCR_LANGS} (comma or {@code +} separated) replaces the installed-pack
 * probe. English is always included.
 */
public final class OcrLanguagePacks {

    public static final List<String> DEFAULT_INDIC = List.of(
            "hin", "mar", "guj", "ben", "tam", "tel", "kan", "mal", "pan", "ori");

    private OcrLanguagePacks() {}

    /**
     * @param envLangs raw {@code TEV_OCR_LANGS}, or null/blank to probe {@code installed}
     * @param installed returns whether a pack code is present; not called when
     *         {@code envLangs} is set
     * @return {@code eng} followed by the selected Indic codes, in probe order
     */
    public static List<String> select(String envLangs, Predicate<String> installed) {
        LinkedHashSet<String> langs = new LinkedHashSet<>();
        langs.add("eng");
        if (envLangs != null && !envLangs.isBlank()) {
            for (String part : envLangs.split("[,+]")) {
                String code = part.trim().toLowerCase(Locale.ROOT);
                if (code.isEmpty() || "eng".equals(code) || "en".equals(code)) {
                    continue;
                }
                langs.add(code);
            }
            return List.copyOf(langs);
        }
        for (String code : DEFAULT_INDIC) {
            if (installed.test(code)) {
                langs.add(code);
            }
        }
        return List.copyOf(langs);
    }
}
