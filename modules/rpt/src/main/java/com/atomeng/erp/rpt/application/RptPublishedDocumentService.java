package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptPublishedDocumentQuery;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** rpt::api {@link RptPublishedDocumentQuery} 구현 — 발행 상태 리비전만, PDF 는 WORM(reports) 원본 + 해시 재검증 */
@Service
public class RptPublishedDocumentService implements RptPublishedDocumentQuery {

    private final RptJdbc rpt;
    private final RptStorage storage;

    public RptPublishedDocumentService(RptJdbc rpt, RptStorage storage) {
        this.rpt = rpt;
        this.storage = storage;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedDocument> publishedDocument(UUID revisionId) {
        return row(revisionId).map(r -> new PublishedDocument(RptJdbc.uuid(r, "id"), RptJdbc.uuid(r, "report_id"),
                RptJdbc.integer(r, "rev_no"), RptJdbc.uuid(r, "project_id"), RptJdbc.uuid(r, "facility_id"),
                RptJdbc.str(r, "report_type"), RptJdbc.str(r, "title"), RptJdbc.localDate(r.get("inspection_date")),
                RptJdbc.str(r, "status"), RptJdbc.str(r, "final_grade"), RptJdbc.str(r, "pdf_sha256"),
                r.get("page_count") == null ? null : RptJdbc.integer(r, "page_count"),
                RptJdbc.instant(r.get("published_at")), RptJdbc.instant(r.get("delivered_at")),
                RptJdbc.uuid(r, "superseded_by"), RptJdbc.str(r, "change_note")));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] publishedPdf(UUID revisionId) {
        Map<String, Object> r = row(revisionId)
                .orElseThrow(() -> new IllegalArgumentException("발행본이 아닌 리비전: " + revisionId));
        byte[] pdf = storage.get(RptStorage.PUBLISHED, RptJdbc.str(r, "pdf_object_key"));
        if (!Hashing.sha256Hex(pdf).equals(RptJdbc.str(r, "pdf_sha256"))) {
            throw new IllegalStateException("발행본 PDF 해시 불일치(WORM 무결성): " + revisionId);
        }
        return pdf;
    }

    private Optional<Map<String, Object>> row(UUID revisionId) {
        return rpt.jdbc().queryForList("""
                SELECT v.id, v.report_id, v.rev_no, v.status, v.pdf_object_key, v.pdf_sha256, v.page_count, v.published_at,
                       v.delivered_at, v.superseded_by, v.change_note, r.project_id, r.facility_id, r.report_type, r.title,
                       r.inspection_date, g.final_grade
                  FROM rpt.report_revision v
                  JOIN rpt.report r ON r.id = v.report_id
                  LEFT JOIN rpt.grade_result g ON g.revision_id = v.id
                 WHERE v.id = ? AND v.status IN ('PUBLISHED','DELIVERED','SUPERSEDED')""", revisionId).stream().findFirst();
    }
}
