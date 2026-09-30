package com.resurgent.tev.parser.classify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The natural key of a learned row label. Two spellings of one label ("Fire Fighting Work",
 * "FIRE-FIGHTING WORK ", "2. Fire fighting works (2.5% of civil cost)") canonicalise to one
 * phrase, which is what makes the dictionary's uniqueness real rather than exact-string.
 */
final class LabelPhrase {

    static final int MAX_TOKENS = 8;
    static final int MAX_LENGTH = 80;
    private static final int MIN_LETTERS = 4;

    private static final Pattern BALANCED_PAREN = Pattern.compile("\\([^()]*\\)");
    private static final Pattern OPEN_PAREN_TAIL = Pattern.compile("\\(.*$");
    private static final Pattern CLOSE_PAREN_HEAD = Pattern.compile("^[^()]*\\)");
    private static final Pattern AT_RATE = Pattern.compile("@\\s*[\\d.]+\\s*%?");
    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\s*(?:\\d+(?:\\.\\d+)*[.)]?|[a-z]{1,3}[.)])\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("[\\d]+");

    private static final Set<String> STOPLIST = loadStoplist();

    private LabelPhrase() {}

    /** Canonical phrase, or {@code ""} when the label has nothing left to key on. */
    static String canonical(String rawLabel) {
        String s = KindTokens.normalizeLabel(rawLabel);
        if (s.isEmpty()) {
            return "";
        }
        s = NUMBERED_PREFIX.matcher(s.replace("( ", "(").replace(" )", ")")).replaceFirst("");
        String before;
        do {
            before = s;
            s = BALANCED_PAREN.matcher(s).replaceAll(" ");
        } while (!s.equals(before));
        s = OPEN_PAREN_TAIL.matcher(s).replaceAll(" ");
        s = CLOSE_PAREN_HEAD.matcher(s).replaceAll(" ");
        s = AT_RATE.matcher(s).replaceAll(" ");
        s = s.replace("&", " and ").replace("%", " percent ");
        s = NON_WORD.matcher(s).replaceAll(" ").trim();
        List<String> tokens = new ArrayList<>();
        for (String token : s.split(" ")) {
            if (token.isEmpty() || NUMERIC_TOKEN.matcher(token).matches()) {
                continue;
            }
            tokens.add(singular(token));
        }
        return String.join(" ", tokens);
    }

    /** True when a canonical phrase is specific enough to be worth learning. */
    static boolean isLearnable(String canonical) {
        if (canonical == null || canonical.isEmpty() || canonical.length() > MAX_LENGTH) {
            return false;
        }
        String[] tokens = canonical.split(" ");
        if (tokens.length > MAX_TOKENS) {
            return false;
        }
        int letters = 0;
        int nonSpace = 0;
        boolean longToken = false;
        for (String token : tokens) {
            if (token.length() >= 3) {
                longToken = true;
            }
            for (char c : token.toCharArray()) {
                nonSpace++;
                if (Character.isLetter(c)) {
                    letters++;
                }
            }
        }
        if (!longToken || letters < MIN_LETTERS || letters < 0.6 * nonSpace) {
            return false;
        }
        return !STOPLIST.contains(canonical);
    }

    private static String singular(String token) {
        int n = token.length();
        if (n > 4 && token.endsWith("ies")) {
            return token.substring(0, n - 3) + "y";
        }
        if (n > 4 && (token.endsWith("sses") || token.endsWith("xes")
                || token.endsWith("ches") || token.endsWith("shes"))) {
            return token.substring(0, n - 2);
        }
        if (n > 3 && token.endsWith("s")
                && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, n - 1);
        }
        return token;
    }

    private static Set<String> loadStoplist() {
        Set<String> out = new HashSet<>();
        try (InputStream in = LabelPhrase.class.getResourceAsStream("dictionary-stoplist.txt")) {
            if (in == null) {
                return out;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(canonical(t));
                }
            }
        } catch (IOException e) {
            System.err.println("[dynamic-dict] Warning: could not read stoplist: " + e.getMessage());
        }
        return out;
    }
}
