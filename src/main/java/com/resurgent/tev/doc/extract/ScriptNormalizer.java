package com.resurgent.tev.doc.extract;

import java.util.Locale;

/**
 * Keeps the source script for provenance and produces a Latin matching form for
 * values, line keys, and entity names. Devanagari, Gujarati, and Tamil digits
 * become ASCII digits. A trailing schwa is dropped so {@code राम शर्मा} matches
 * {@code Ram Sharma}.
 */
public final class ScriptNormalizer {

    private ScriptNormalizer() {}

    /** Language of the first Indic script in {@code text}, or {@code en}. */
    public static String lang(String text) {
        if (text == null || text.isBlank()) {
            return "en";
        }
        boolean devanagari = false;
        boolean marathi = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x0B80 && cp <= 0x0BFF) {
                return "ta";
            }
            if (cp >= 0x0A80 && cp <= 0x0AFF) {
                return "gu";
            }
            if (cp == 0x0933) {
                marathi = true;
            }
            if (cp >= 0x0900 && cp <= 0x097F) {
                devanagari = true;
            }
        }
        if (marathi) {
            return "mr";
        }
        if (devanagari) {
            return "hi";
        }
        return "en";
    }

    /**
     * Latin, lower-cased, single-spaced form used to group one schedule line and
     * to match an entity across scripts.
     */
    public static String matchKey(String text) {
        String latin = transliterate(text);
        if (latin == null) {
            return "";
        }
        return latin.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * Copies Indic letters and digits in {@code text} to Latin. Latin text is
     * returned unchanged.
     */
    public static String transliterate(String text) {
        if (text == null || text.isEmpty() || !containsIndic(text)) {
            return text;
        }
        String prepared = replaceUnitWords(text);
        StringBuilder out = new StringBuilder();
        boolean pendingSchwa = false;
        for (int i = 0; i < prepared.length(); ) {
            int cp = prepared.codePointAt(i);
            i += Character.charCount(cp);
            if (isNukta(cp)) {
                continue;
            }
            if (isVirama(cp)) {
                pendingSchwa = false;
                continue;
            }
            String digit = digit(cp);
            if (digit != null) {
                if (pendingSchwa) {
                    pendingSchwa = false;
                }
                out.append(digit);
                continue;
            }
            String matra = vowelSign(cp);
            if (matra != null) {
                pendingSchwa = false;
                out.append(matra);
                continue;
            }
            String independent = independentVowel(cp);
            if (independent != null) {
                if (pendingSchwa) {
                    pendingSchwa = false;
                }
                out.append(independent);
                continue;
            }
            String consonant = consonant(cp);
            if (consonant != null) {
                if (pendingSchwa) {
                    out.append('a');
                }
                out.append(consonant);
                pendingSchwa = true;
                continue;
            }
            if (isAnusvara(cp)) {
                pendingSchwa = false;
                out.append('n');
                continue;
            }
            if (pendingSchwa) {
                pendingSchwa = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** Transliterates the candidate's matching slots and records {@code lang_source}. */
    public static void apply(FactCandidate candidate) {
        String sample = join(candidate.valueText(), candidate.lineKey(),
                candidate.subjectEntity(), candidate.siteName());
        if (!sample.isBlank()) {
            candidate.langSource(lang(sample));
        }
        candidate.valueText(transliterate(candidate.valueText()));
        candidate.lineKey(transliterate(candidate.lineKey()));
        candidate.subjectEntity(transliterate(candidate.subjectEntity()));
        candidate.siteName(transliterate(candidate.siteName()));
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(part);
            }
        }
        return out.toString();
    }

    private static boolean containsIndic(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x0900 && cp <= 0x097F)
                    || (cp >= 0x0A80 && cp <= 0x0AFF)
                    || (cp >= 0x0B80 && cp <= 0x0BFF)) {
                return true;
            }
        }
        return false;
    }

    /** Hindi and Gujarati area words, longest first, swapped for a Latin unit token. */
    private static String replaceUnitWords(String text) {
        return text.replace("\u0935\u0930\u094d\u0917 \u092e\u0940\u091f\u0930", " sq m")
                .replace("\u090f\u0915\u0921\u093c", " acre")
                .replace("\u090f\u0915\u095c", " acre")
                .replace("\u0939\u0947\u0915\u094d\u091f\u0947\u092f\u0930", " hectare")
                .replace("\u0917\u0941\u0902\u0920\u093e", " guntha")
                .replace("\u092c\u0940\u0918\u093e", " bigha");
    }

    private static boolean isVirama(int cp) {
        return cp == 0x094D || cp == 0x0ACD || cp == 0x0BCD;
    }

    private static boolean isNukta(int cp) {
        return cp == 0x093C || cp == 0x0ABC;
    }

    private static boolean isAnusvara(int cp) {
        return cp == 0x0902 || cp == 0x0A82;
    }

    private static String digit(int cp) {
        if (cp >= 0x0966 && cp <= 0x096F) {
            return Integer.toString(cp - 0x0966);
        }
        if (cp >= 0x0AE6 && cp <= 0x0AEF) {
            return Integer.toString(cp - 0x0AE6);
        }
        if (cp >= 0x0BE6 && cp <= 0x0BEF) {
            return Integer.toString(cp - 0x0BE6);
        }
        return null;
    }

    /** Gujarati letters sit 0x180 above the Devanagari letter they correspond to. */
    private static int asDevanagari(int cp) {
        if (cp >= 0x0A80 && cp <= 0x0AFF) {
            return cp - 0x180;
        }
        return cp;
    }

    private static String independentVowel(int cp) {
        if (cp >= 0x0B85 && cp <= 0x0B94) {
            return switch (cp) {
                case 0x0B85, 0x0B86 -> "a";
                case 0x0B87, 0x0B88 -> "i";
                case 0x0B89, 0x0B8A -> "u";
                case 0x0B8E, 0x0B8F -> "e";
                case 0x0B90 -> "ai";
                case 0x0B92, 0x0B93 -> "o";
                case 0x0B94 -> "au";
                default -> null;
            };
        }
        return switch (asDevanagari(cp)) {
            case 0x0905, 0x0906 -> "a";
            case 0x0907, 0x0908 -> "i";
            case 0x0909, 0x090A -> "u";
            case 0x090B -> "ri";
            case 0x090F, 0x0910 -> cp == 0x0910 || asDevanagari(cp) == 0x0910 ? "ai" : "e";
            case 0x0913 -> "au";
            case 0x0912 -> "o";
            default -> null;
        };
    }

    private static String vowelSign(int cp) {
        if (cp >= 0x0BBE && cp <= 0x0BCC) {
            return switch (cp) {
                case 0x0BBE -> "a";
                case 0x0BBF, 0x0BC0 -> "i";
                case 0x0BC1, 0x0BC2 -> "u";
                case 0x0BC6, 0x0BC7 -> "e";
                case 0x0BC8 -> "ai";
                case 0x0BCA, 0x0BCB -> "o";
                case 0x0BCC -> "au";
                default -> null;
            };
        }
        return switch (asDevanagari(cp)) {
            case 0x093E -> "a";
            case 0x093F, 0x0940 -> "i";
            case 0x0941, 0x0942 -> "u";
            case 0x0943 -> "ri";
            case 0x0947 -> "e";
            case 0x0948 -> "ai";
            case 0x094B -> "o";
            case 0x094C -> "au";
            default -> null;
        };
    }

    private static String consonant(int cp) {
        String tamil = tamilConsonant(cp);
        if (tamil != null) {
            return tamil;
        }
        return switch (asDevanagari(cp)) {
            case 0x0915 -> "k";
            case 0x0916 -> "kh";
            case 0x0917 -> "g";
            case 0x0918 -> "gh";
            case 0x0919 -> "ng";
            case 0x091A -> "ch";
            case 0x091B -> "chh";
            case 0x091C -> "j";
            case 0x091D -> "jh";
            case 0x091E -> "ny";
            case 0x091F, 0x0924 -> "t";
            case 0x0920, 0x0925 -> "th";
            case 0x0921, 0x0926 -> "d";
            case 0x0922, 0x0927 -> "dh";
            case 0x0923, 0x0928, 0x0929 -> "n";
            case 0x092A -> "p";
            case 0x092B -> "ph";
            case 0x092C -> "b";
            case 0x092D -> "bh";
            case 0x092E -> "m";
            case 0x092F -> "y";
            case 0x0930, 0x0931 -> "r";
            case 0x0932, 0x0933, 0x0934 -> "l";
            case 0x0935 -> "v";
            case 0x0936, 0x0937 -> "sh";
            case 0x0938 -> "s";
            case 0x0939 -> "h";
            case 0x0958 -> "k";
            case 0x095B -> "z";
            case 0x095C, 0x095D -> "r";
            default -> null;
        };
    }

    private static String tamilConsonant(int cp) {
        return switch (cp) {
            case 0x0B95 -> "k";
            case 0x0B99 -> "ng";
            case 0x0B9A -> "ch";
            case 0x0B9E -> "ny";
            case 0x0B9F, 0x0BA4 -> "t";
            case 0x0BA3, 0x0BA8, 0x0BA9 -> "n";
            case 0x0BAA -> "p";
            case 0x0BAE -> "m";
            case 0x0BAF -> "y";
            case 0x0BB0, 0x0BB1 -> "r";
            case 0x0BB2, 0x0BB3, 0x0BB4 -> "l";
            case 0x0BB5 -> "v";
            case 0x0BB7 -> "sh";
            case 0x0BB8 -> "s";
            case 0x0BB9 -> "h";
            default -> null;
        };
    }
}
