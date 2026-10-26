package com.atomeng.erp.ptl.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.ptl.domain.WatermarkStamp;
import com.atomeng.erp.rpt.api.RptPublishedDocumentQuery;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 납품 보고서 열람 · 워터마크 다운로드 (FR-PTL-006 · BR-PTL-003 · 012, 09-IMPL Step 6 일부 · 7).
 * 목록 · 상세는 ptl.published_report(RLS) 만 읽는다 — 다른 고객사 행은 DB 가 돌려주지 않는다.
 * 다운로드: RLS 로 행 확인 → rpt::api 로 WORM 원본(해시 재검증) → 고객 id · 시각 · 다운로드 id 워터마크 → 다운로드 감사
 * (ptl.report_download, append-only) + 접근 로그 + core 감사 로그(EXPORT).
 */
@Service
public class PortalReportService {

    public static final String SUPERSEDED_BANNER = "이 보고서는 정정본으로 대체되었습니다. 최신 정정본을 확인하세요(열람만 가능).";

    public record Download(UUID downloadId, String fileName, byte[] pdf, String sha256) {}

    private final JdbcTemplate jdbc;
    private final PortalRls rls;
    private final PortalAccessLog accessLog;
    private final RptPublishedDocumentQuery rptDocs;
    private final PdfWatermarker watermarker;
    private final AuditLog audit;
    private final Clock clock;
    private final PortalDownloadRateLimiter rateLimiter;

    public PortalReportService(JdbcTemplate jdbc, PortalRls rls, PortalAccessLog accessLog,
                               RptPublishedDocumentQuery rptDocs, PdfWatermarker watermarker, AuditLog audit,
                               Clock clock, PortalDownloadRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.rls = rls;
        this.accessLog = accessLog;
        this.rptDocs = rptDocs;
        this.watermarker = watermarker;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public List<Map<String, Object>> list(PortalAccess.Customer c) {
        return rls.asCustomer(c.customerId(), () -> jdbc.queryForList(
                "SELECT * FROM ptl.published_report ORDER BY delivered_at DESC, rev_no DESC")
                .stream().map(PortalReportService::view).toList());
    }

    @Transactional
    public Map<String, Object> get(PortalAccess.Customer c, UUID id) {
        Map<String, Object> row = rls.asCustomer(c.customerId(), () -> {
            Map<String, Object> r = find(id);
            if (r != null) {
                accessLog.appendInContext(c.customerId(), c.actorId(), "VIEW", "PublishedReport", id, null);
            }
            return r;
        });
        if (row == null) {
            throw hidden(c, id);
        }
        return view(row);
    }

    @Transactional
    public Download download(PortalAccess.Customer c, UUID id, String clientIp) {
        rateLimiter.acquire(c.customerId());   // PTL-03 고객사별 속도 제한(워터마크 작업 전에)
        Map<String, Object> row = rls.asCustomer(c.customerId(), () -> find(id));
        if (row == null) {
            throw hidden(c, id);
        }
        String status = (String) row.get("status");
        if ("SUPERSEDED".equals(status)) {
            throw new DomainException(PtlErrors.REPORT_SUPERSEDED, "정정본으로 대체된 보고서는 다운로드할 수 없습니다: " + id)
                    .with("supersededByRevisionId", row.get("superseded_by_revision_id"))
                    .with("banner", SUPERSEDED_BANNER);
        }
        if ("REVOKED".equals(status)) {
            throw new DomainException(PtlErrors.REPORT_REVOKED, "공개가 회수된 보고서입니다: " + id);
        }
        UUID revisionId = (UUID) row.get("revision_id");
        String expected = ((String) row.get("pdf_sha256")).strip();
        byte[] source;
        try {
            source = rptDocs.publishedPdf(revisionId);
        } catch (RuntimeException e) {
            throw new DomainException(PtlErrors.SOURCE_INTEGRITY, "발행본 원본을 확인할 수 없습니다: " + e.getMessage());
        }
        if (!Hashing.sha256Hex(source).equals(expected)) {
            throw new DomainException(PtlErrors.SOURCE_INTEGRITY, "발행본 원본 해시가 공개 시점과 다릅니다: " + revisionId);
        }
        Instant now = clock.instant();
        UUID downloadId = UuidV7.next();
        WatermarkStamp stamp = new WatermarkStamp(c.customerId(), downloadId, now);
        PdfWatermarker.Result out = watermarker.apply(source, stamp);
        rls.runAsCustomer(c.customerId(), () -> {
            jdbc.update("""
                    INSERT INTO ptl.report_download(id, customer_id, published_report_id, actor_id, downloaded_at,
                                                    watermark_text, source_sha256, output_sha256, client_ip)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", downloadId, c.customerId(), id, c.actorId(),
                    Timestamp.from(now), stamp.summary(), expected, out.sha256(), maskIp(clientIp));
            accessLog.appendInContext(c.customerId(), c.actorId(), "DOWNLOAD", "PublishedReport", id,
                    "download-id " + downloadId);
        });
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("downloadId", downloadId);
        after.put("customerId", c.customerId());
        after.put("revisionId", revisionId);
        after.put("outputSha256", out.sha256());
        audit.append("PTL", AuditLog.Action.EXPORT, "PublishedReport", id, null, after);
        String file = "report-%s-rev%s-%s.pdf".formatted(row.get("report_id"), row.get("rev_no"), downloadId);
        return new Download(downloadId, file, out.pdf(), out.sha256());
    }

    /** 내 고객사의 다운로드 감사 이력(FR-PTL-019 조직 접근 로그 일부) */
    @Transactional
    public List<Map<String, Object>> downloads(PortalAccess.Customer c) {
        return rls.asCustomer(c.customerId(), () -> jdbc.queryForList("""
                SELECT id AS "downloadId", published_report_id AS "reportId", actor_id AS "actorId",
                       downloaded_at AS "downloadedAt", output_sha256 AS "outputSha256"
                  FROM ptl.report_download ORDER BY downloaded_at DESC, id DESC""").stream().map(PtlRows::normalize)
                .toList());
    }

    private Map<String, Object> find(UUID id) {
        return jdbc.queryForList("SELECT * FROM ptl.published_report WHERE id = ?", id).stream().findFirst()
                .orElse(null);
    }

    private DomainException hidden(PortalAccess.Customer c, UUID id) {
        accessLog.denied(c, "PublishedReport", id, "NOT_FOUND_OR_OTHER_CUSTOMER");
        return new DomainException(PtlErrors.NOT_FOUND, "보고서 없음: " + id);
    }

    static Map<String, Object> view(Map<String, Object> raw) {
        Map<String, Object> r = PtlRows.normalize(raw);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("reportId", r.get("report_id"));
        m.put("revisionId", r.get("revision_id"));
        m.put("revNo", r.get("rev_no"));
        m.put("facilityId", r.get("facility_id"));
        m.put("reportType", r.get("report_type"));
        m.put("title", r.get("title"));
        m.put("inspectionDate", r.get("inspection_date"));
        m.put("safetyGrade", r.get("safety_grade") == null ? null : r.get("safety_grade").toString().strip());
        m.put("pageCount", r.get("page_count"));
        m.put("deliveredAt", r.get("delivered_at"));
        m.put("status", r.get("status"));
        boolean superseded = "SUPERSEDED".equals(r.get("status"));
        m.put("downloadable", "PUBLISHED".equals(r.get("status")));
        m.put("banner", superseded ? SUPERSEDED_BANNER : null);
        m.put("supersededByRevisionId", r.get("superseded_by_revision_id"));
        m.put("supersededReason", r.get("superseded_reason"));
        m.put("supersededAt", r.get("superseded_at"));
        return m;
    }

    /** IP 앞 2옥텟만 보관(개인정보 최소화, B7.1) */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String[] p = ip.split("\\.");
        if (p.length == 4) {
            return p[0] + "." + p[1] + ".x.x";
        }
        int colon = ip.indexOf(':', ip.indexOf(':') + 1);
        return (colon > 0 ? ip.substring(0, colon) : ip) + ":x";
    }
}
