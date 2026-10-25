package com.resurgent.tev.doc.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.FakeDocLlmClient;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Live classify tie-break (S2.12 step 4) against a fake {@link DocLlmClient}: only a
 * valid candidate pack wins, anything else declines, and usage is reported.
 */
class LlmClassifyTieBreakTest {

    private static final List<ClassifyTieBreak.ScoredPack> CANDIDATES = List.of(
            new ClassifyTieBreak.ScoredPack("agreement.ppa", "1.0.0", 0.0, List.of()),
            new ClassifyTieBreak.ScoredPack("quotation.vendor", "1.0.0", 0.0, List.of()));

    private static ClassifyTieBreak.TieBreakContext context() {
        return new ClassifyTieBreak.TieBreakContext(
                "agreement", "mystery.pdf",
                "Letter of intent regarding power offtake terms and conditions.",
                "general", CANDIDATES);
    }

    @Test
    void choosesAValidCandidatePackAndReportsUsage() {
        FakeDocLlmClient llm = new FakeDocLlmClient(
                "```json\n{\"pack_code\":\"agreement.ppa\",\"confidence\":0.41,"
                        + "\"reason\":\"power offtake language\"}\n```");
        List<DocLlmClient.Usage> usage = new ArrayList<>();
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(llm, usage::add);

        Optional<ClassifyTieBreak.TieBreakChoice> choice = tieBreak.resolve(context());

        assertThat(choice).isPresent();
        assertThat(choice.get().packCode()).isEqualTo("agreement.ppa");
        assertThat(choice.get().confidence()).isEqualTo(0.41);
        assertThat(choice.get().reason()).contains("power offtake");
        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).calls()).isEqualTo(1);
        assertThat(usage.get(0).promptTokens()).isEqualTo(100);
        // Labels/signals only: the prompt carries the page text and candidates, not a body.
        assertThat(llm.lastUser()).contains("agreement.ppa").contains("mystery.pdf");
    }

    @Test
    void declinesWhenModelSaysNone() {
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                new FakeDocLlmClient("{\"pack_code\":\"none\",\"reason\":\"no fit\"}"), null);
        assertThat(tieBreak.resolve(context())).isEmpty();
    }

    @Test
    void declinesWhenModelInventsAPackNotInCandidates() {
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                new FakeDocLlmClient("{\"pack_code\":\"identity.promoter_net_worth\"}"), null);
        assertThat(tieBreak.resolve(context())).isEmpty();
    }

    @Test
    void declinesOnMalformedReplyWithoutThrowing() {
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                new FakeDocLlmClient("I cannot help with that."), null);
        assertThat(tieBreak.resolve(context())).isEmpty();
    }

    @Test
    void declinesWhenTheClientFails() {
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                FakeDocLlmClient.failing("timeout"), null);
        assertThat(tieBreak.resolve(context())).isEmpty();
    }

    @Test
    void classifierDoesNotLetTieBreakAssignATypeThePagesDoNotState() throws Exception {
        List<DocTypePack> seeds = PackLoader.loadSeedPacks();
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                new FakeDocLlmClient("{\"pack_code\":\"agreement.ppa\",\"confidence\":0.6,"
                        + "\"reason\":\"power offtake language\"}"), null);
        DocTypeClassifier classifier = new DocTypeClassifier(tieBreak);

        ClassifyResult result = classifier.classify(ClassifyRequest.scoreOnly(
                "agreement", "mystery.pdf",
                "Letter of intent regarding power offtake terms and conditions.",
                null, seeds));

        assertThat(result.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
        assertThat(result.docTypeCode()).isEqualTo("unknown");
    }

    @Test
    void classifierStaysUnknownWhenTieBreakModelInventsPack() throws Exception {
        List<DocTypePack> seeds = PackLoader.loadSeedPacks();
        LlmClassifyTieBreak tieBreak = new LlmClassifyTieBreak(
                new FakeDocLlmClient("{\"pack_code\":\"report.not_a_pack\"}"), null);
        DocTypeClassifier classifier = new DocTypeClassifier(tieBreak);

        ClassifyResult result = classifier.classify(ClassifyRequest.scoreOnly(
                "agreement", "mystery.pdf",
                "Letter of intent regarding power offtake terms and conditions.",
                null, seeds));

        assertThat(result.status()).isEqualTo(ClassifyResult.Status.UNKNOWN);
        assertThat(result.packCode()).isNull();
    }
}
