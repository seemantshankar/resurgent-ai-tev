package com.resurgent.tev.doc.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts a JSON object from an LLM reply that may be wrapped in prose, a ```json
 * fence, or truncated at the completion limit. Keeps adapters tolerant of chatty or
 * cut-off models without trusting them.
 */
public final class DocLlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses the first JSON object in {@code content}, repairing a truncated tail by
     * closing the containers that were open at the last complete element.
     *
     * @param content raw model reply (may be null)
     * @return the parsed object node
     * @throws DocLlmException when no usable JSON object is present
     */
    public static JsonNode object(String content) {
        String json = extractObjectText(content);
        if (json == null) {
            throw new DocLlmException("LLM reply contained no JSON object: " + shorten(content));
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new DocLlmException("LLM reply JSON was not an object");
            }
            return node;
        } catch (DocLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new DocLlmException("LLM reply was not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * The substring from the first '{' to its balanced '}' (skipping strings), or — when
     * the reply was cut off — the longest prefix that can be closed into valid JSON.
     * Returns null when there is no '{' at all.
     */
    static String extractObjectText(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        // last index (exclusive) just after a completed '}' or ']', plus the open stack there
        int lastSafe = -1;
        StringBuilder stackAtSafe = new StringBuilder();
        StringBuilder stack = new StringBuilder();
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    depth++;
                    stack.append(c);
                }
                case '}', ']' -> {
                    depth--;
                    if (stack.length() > 0) {
                        stack.setLength(stack.length() - 1);
                    }
                    if (depth == 0) {
                        return content.substring(start, i + 1);
                    }
                    lastSafe = i + 1;
                    stackAtSafe.setLength(0);
                    stackAtSafe.append(stack);
                }
                default -> {
                    // ignore
                }
            }
        }
        // unbalanced (truncated): close what was open at the last complete element
        if (lastSafe > start && stackAtSafe.length() > 0) {
            StringBuilder repaired = new StringBuilder(content.substring(start, lastSafe));
            for (int i = stackAtSafe.length() - 1; i >= 0; i--) {
                repaired.append(stackAtSafe.charAt(i) == '{' ? '}' : ']');
            }
            return repaired.toString();
        }
        return null;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 200 ? compact : compact.substring(0, 200) + "…";
    }

    private DocLlmJson() {}
}
