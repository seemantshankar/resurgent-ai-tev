package com.resurgent.tev.doc.layout.ocr;

import java.util.List;

/**
 * Maps characters on an OCR page to an Indic pack code, or {@code eng}.
 * Devanagari is reported as {@code hin}. Marathi shares that script and is
 * selected only when a language-pack probe asks for {@code mar}.
 */
public final class OcrScript {

    private static final Script[] SCRIPTS = {
            new Script(Character.UnicodeBlock.DEVANAGARI, "hin"),
            new Script(Character.UnicodeBlock.GUJARATI, "guj"),
            new Script(Character.UnicodeBlock.BENGALI, "ben"),
            new Script(Character.UnicodeBlock.TAMIL, "tam"),
            new Script(Character.UnicodeBlock.TELUGU, "tel"),
            new Script(Character.UnicodeBlock.KANNADA, "kan"),
            new Script(Character.UnicodeBlock.MALAYALAM, "mal"),
            new Script(Character.UnicodeBlock.GURMUKHI, "pan"),
            new Script(Character.UnicodeBlock.ORIYA, "ori")
    };

    private OcrScript() {}

    /** Dominant script on the page, or null when the page has no letters. */
    public static String detect(List<LocalOcrClient.OcrWord> words) {
        int[] counts = new int[SCRIPTS.length];
        int latin = 0;
        for (LocalOcrClient.OcrWord word : words) {
            String text = word.text();
            for (int i = 0; i < text.length();) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
                boolean indic = false;
                if (block != null) {
                    for (int s = 0; s < SCRIPTS.length; s++) {
                        if (SCRIPTS[s].block == block) {
                            counts[s]++;
                            indic = true;
                            break;
                        }
                    }
                }
                if (!indic && Character.isLetter(cp) && cp < 0x250) {
                    latin++;
                }
            }
        }
        int best = 0;
        String code = null;
        for (int s = 0; s < SCRIPTS.length; s++) {
            if (counts[s] > best) {
                best = counts[s];
                code = SCRIPTS[s].pack;
            }
        }
        if (code != null) {
            return code;
        }
        return latin > 0 ? "eng" : null;
    }

    private record Script(Character.UnicodeBlock block, String pack) {}
}
