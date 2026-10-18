package com.atomeng.erp.qar.application;

import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.rpt.api.RptRevisionQuery.AnchorSet;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/v1/qar/rounds/{roundId}/anchors (설계 B4 v0.2): 라운드 고정 리비전의 anchors.json(rpt::api
 * {@code RptRevisionQuery.anchors}) 프록시. ETag = {@code "revisionId:pdfSha256"}(제출 전이면 현재 렌더 해시) —
 * If-None-Match 일치면 304. anchors.json 이 없는 리비전은 섹션 목록(SECTION 폴백)을 돌려준다(TC-QAR-014).
 */
@Service
public class AnchorService {

    public record Result(String etag, boolean notModified, Map<String, Object> body) {}

    private final ReviewSupport sup;

    public AnchorService(ReviewSupport sup) {
        this.sup = sup;
    }

    @Transactional(readOnly = true)
    public Result anchors(UUID roundId, String ifNoneMatch) {
        Map<String, Object> round = sup.round(roundId);
        sup.reviewOfRound(round, "qar:review:read");
        UUID revisionId = QarJdbc.uuid(round, "revision_id");
        String pdfSha = QarJdbc.str(round, "pdf_sha256");
        if (pdfSha == null) {
            RevisionInfo info = sup.revision(revisionId);
            pdfSha = info.renderedPdfSha256() == null ? "none" : info.renderedPdfSha256();
        }
        String etag = "\"" + revisionId + ":" + pdfSha + "\"";
        if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || ifNoneMatch.equals("W/" + etag))) {
            return new Result(etag, true, null);
        }
        Optional<AnchorSet> set = sup.rpt().anchors(revisionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roundId", roundId);
        body.put("revisionId", revisionId);
        body.put("pdfSha256", pdfSha);
        if (set.isPresent()) {
            body.put("source", set.get().source());
            body.put("unit", set.get().unit());
            body.put("pageCount", set.get().pageCount());
            body.put("anchorsSha256", set.get().sha256());
            body.put("anchors", set.get().anchors());
        } else {
            RevisionContent content = sup.content(revisionId);
            List<Map<String, Object>> sections = new ArrayList<>();
            content.sections().forEach(s -> sections.add(Map.of("type", "SECTION", "sectionKey", s.sectionKey(),
                    "title", s.title())));
            body.put("source", "SECTION_FALLBACK");
            body.put("anchors", sections);
        }
        return new Result(etag, false, body);
    }
}
