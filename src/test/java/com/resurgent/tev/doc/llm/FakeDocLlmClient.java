package com.resurgent.tev.doc.llm;

import java.util.ArrayList;
import java.util.List;

/** Deterministic in-memory {@link DocLlmClient} for tests; never touches a network. */
public final class FakeDocLlmClient implements DocLlmClient {

    private final String content;
    private final DocLlmException failure;

    private int calls;
    private final List<String> systems = new ArrayList<>();
    private String lastSystem;
    private String lastUser;
    private int lastMaxTokens;

    /** Replies with {@code content} to every call. */
    public FakeDocLlmClient(String content) {
        this.content = content;
        this.failure = null;
    }

    private FakeDocLlmClient(String content, DocLlmException failure) {
        this.content = content;
        this.failure = failure;
    }

    /** Always fails, so callers' fallback paths can be exercised. */
    public static FakeDocLlmClient failing(String message) {
        return new FakeDocLlmClient("", new DocLlmException(message));
    }

    /** Increments the call counter without consuming a reply (for a chain of fakes). */
    public int calls() {
        return calls;
    }

    /** Calls whose system prompt contains {@code needle} — counts one step of a pipeline. */
    public int callsWithSystemContaining(String needle) {
        int matching = 0;
        for (String system : systems) {
            if (system.contains(needle)) {
                matching++;
            }
        }
        return matching;
    }

    public String lastSystem() {
        return lastSystem;
    }

    public String lastUser() {
        return lastUser;
    }

    public int lastMaxTokens() {
        return lastMaxTokens;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public Completion complete(String system, String user, int maxTokens) {
        calls++;
        systems.add(system);
        lastSystem = system;
        lastUser = user;
        lastMaxTokens = maxTokens;
        if (failure != null) {
            throw failure;
        }
        return new Completion(content, "fake-model", 100, 50, 0.002, 12);
    }
}
