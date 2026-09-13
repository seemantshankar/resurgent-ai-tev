package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.discover.DiscoverService;
import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.nomenclature.NomenclatureCatalog;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Packet classification application service: Layer A disposition for one parse run.
 * Consumes derived Packets; does not rewrite Candidate geometry.
 */
public final class ClassifyService {

    private final ClassifierLlm llm;
    private final DiscoverService discover;

    public ClassifyService(ClassifierLlm llm) {
        this(llm, new DiscoverService());
    }

    public ClassifyService(ClassifierLlm llm, DiscoverService discover) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.discover = Objects.requireNonNull(discover, "discover");
    }

    public ClassifySummary classify(Path dbPath, long parseRunId) throws ClassifyException {
        Objects.requireNonNull(dbPath, "dbPath");
        Path absolute = dbPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new ClassifyException("database not found: " + absolute);
        }
        try (WorkspaceDatabase db = WorkspaceDatabase.open(absolute)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (!repo.parseRunExists(parseRunId)) {
                throw new ClassifyException("parse run not found: " + parseRunId);
            }
            long mandateId = repo.selectParseRunMandateId(parseRunId);
            OntologySlice slice = new NomenclatureCatalog(repo).sliceForMandate(mandateId);
            List<CandidateRow> candidates = repo.selectCandidatesForParseRun(parseRunId);
            if (candidates.isEmpty()) {
                throw new ClassifyException("no Candidates for parse run " + parseRunId
                        + "; run discover first");
            }

            db.connection().setAutoCommit(false);
            try {
                repo.deletePacketDispositionsForParseRun(parseRunId);
                Map<Long, LayerAJudgment> judged = new HashMap<>();
                Map<Long, LayerAJudgment> coverageByWorksheet = new HashMap<>();
                int coverageParents = 0;
                for (CandidateRow candidate : orderForLayerA(candidates)) {
                    boolean cheapPass = "coverage_parent".equals(candidate.candidateKind());
                    if (cheapPass) {
                        coverageParents++;
                    }
                    Packet packet = discover.buildPacket(repo, candidate.candidateId());
                    Packet redacted = PacketRedactor.redact(packet, cheapPass);
                    LayerAJudgment parent = cheapPass
                            ? null
                            : parentContext(candidate, judged, coverageByWorksheet);
                    LayerAJudgment judgment = requireJudgment(
                            llm.classifyLayerA(new LayerAPrompt(redacted, slice, parent, cheapPass)),
                            candidate.candidateId());
                    judged.put(candidate.candidateId(), judgment);
                    if (cheapPass) {
                        coverageByWorksheet.put(candidate.worksheetId(), judgment);
                    }
                    repo.insertPacketDisposition(new PacketDisposition(
                            candidate.candidateId(),
                            parseRunId,
                            judgment.scheduleFamily(),
                            judgment.triage(),
                            judgment.relevance(),
                            judgment.rowLabels(),
                            judgment.columnHeaders(),
                            judgment.packetDefaultHead(),
                            candidate.parentCandidateId(),
                            cheapPass));
                }
                repo.commit();
                return new ClassifySummary(parseRunId, judged.size(), coverageParents);
            } catch (ClassifyException e) {
                repo.rollback();
                throw e;
            } catch (Exception e) {
                repo.rollback();
                throw new ClassifyException("classify failed: " + e.getMessage(), e);
            } finally {
                db.connection().setAutoCommit(true);
            }
        } catch (ClassifyException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            throw new ClassifyException("classify failed: " + msg, e);
        }
    }

    private static List<CandidateRow> orderForLayerA(List<CandidateRow> candidates) {
        List<CandidateRow> ordered = new ArrayList<>();
        for (CandidateRow candidate : candidates) {
            if ("coverage_parent".equals(candidate.candidateKind())) {
                ordered.add(candidate);
            }
        }
        for (CandidateRow candidate : candidates) {
            if (!"coverage_parent".equals(candidate.candidateKind())) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    private static LayerAJudgment parentContext(
            CandidateRow candidate,
            Map<Long, LayerAJudgment> judged,
            Map<Long, LayerAJudgment> coverageByWorksheet) {
        if (candidate.parentCandidateId() != null) {
            LayerAJudgment parent = judged.get(candidate.parentCandidateId());
            if (parent != null) {
                return parent;
            }
        }
        return coverageByWorksheet.get(candidate.worksheetId());
    }

    private static LayerAJudgment requireJudgment(LayerAJudgment judgment, long candidateId)
            throws ClassifyException {
        if (judgment == null
                || judgment.scheduleFamily() == null || judgment.scheduleFamily().isBlank()
                || !Triage.isKnown(judgment.triage())
                || !Relevance.isKnown(judgment.relevance())) {
            throw new ClassifyException(
                    "LLM returned an invalid Layer A judgment for candidate " + candidateId);
        }
        if (Triage.isSoft(judgment.triage()) && !Relevance.NOISE.equals(judgment.relevance())) {
            return new LayerAJudgment(
                    judgment.scheduleFamily(),
                    judgment.triage(),
                    Relevance.NOISE,
                    judgment.rowLabels(),
                    judgment.columnHeaders(),
                    judgment.packetDefaultHead());
        }
        return judgment;
    }
}
