package com.resurgent.tev.doc.llm;

import java.util.List;

/** Deterministic in-memory {@link DocLlmClient} for tests; never touches a network. */
public final class FakeDocLlmClient implements DocLlmClient {

    private final List<String> contents;
    private final DocLlmException failure;

    private int calls;
    private String lastSystem;
    private String lastUser;
    private int lastMaxTokens;

    /** Replies with {@code content} to every call. */
    public FakeDocLlmClient(String content) {
        this(List.of(content), null);
    }

    private FakeDocLlmClient(List<String> contents, DocLlmException failure) {
        this.contents = List.copyOf(contents);
        this.failure = failure;
    }

    /**
     * Replies with each content once, in call order; the last content repeats for any
     * further calls. Lets one fake serve a multi-document run where every document's
     * model read differs.
     */
    public static FakeDocLlmClient scripted(String... contents) {
        if (contents == null || contents.length == 0) {
            throw new IllegalArgumentException("scripted() needs at least one reply");
        }
        return new FakeDocLlmClient(List.of(contents), null);
    }

    /** Always fails, so callers' fallback paths can be exercised. */
    public static FakeDocLlmClient failing(String message) {
        return new FakeDocLlmClient(List.of(""), new DocLlmException(message));
    }

    /** Increments the call counter without consuming a reply (for a chain of fakes). */
    public int calls() {
        return calls;
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
        lastSystem = system;
        lastUser = user;
        lastMaxTokens = maxTokens;
        if (failure != null) {
            calls++;
            throw failure;
        }
        String content = contents.get(Math.min(calls, contents.size() - 1));
        calls++;
        return new Completion(content, "fake-model", 100, 50, 0.002, 12);
    }
}
