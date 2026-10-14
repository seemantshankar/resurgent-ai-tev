package com.resurgent.tev.doc.llm;

/** Deterministic in-memory {@link DocLlmClient} for tests; never touches a network. */
public final class FakeDocLlmClient implements DocLlmClient {

    private final String content;
    private final DocLlmException failure;

    private int calls;
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
        lastSystem = system;
        lastUser = user;
        lastMaxTokens = maxTokens;
        if (failure != null) {
            throw failure;
        }
        return new Completion(content, "fake-model", 100, 50, 0.002, 12);
    }
}
