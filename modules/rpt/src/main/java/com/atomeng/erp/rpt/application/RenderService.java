package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.application.port.PdfConverterPort;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.ReportType;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.hwpx.HwpxBinder;
import com.atomeng.erp.rpt.domain.hwpx.HwpxPackage;
import com.atomeng.erp.rpt.domain.hwpx.HwpxValidator;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel;
import com.atomeng.erp.rpt.domain.layout.AnchorLayout;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 렌더링 (FR-RPT-012, 06-IMPL Step 8 · 9 일부): 섹션/블록 + 등급 + 망도 PNG → HWPX(템플릿 바인딩 · 검증) →
 * PDF(변환 포트: Gotenberg 또는 스텁) → anchors.json(블록 → 페이지 · rect · 텍스트 해시). 결과는 rpt-renders 에 저장하고
 * render_job(DONE, content_hash) · revision_anchor 를 남긴다. 이번 단계는 동기 처리(Redis 큐 · 워커 · PDF/A 는 후속).
 */
@Service
public class RenderService {

    private final RevisionSupport sup;
    private final RptJdbc rpt;
    private final RptStorage storage;
    private final PdfConverterPort pdf;

    public RenderService(RevisionSupport sup, RptStorage storage, PdfConverterPort pdf) {
        this.sup = sup;
        this.rpt = sup.jdbc();
        this.storage = storage;
        this.pdf = pdf;
    }

    @Transactional
    public Map<String, Object> render(UUID revId) {
        Ctx c = sup.lock(revId, "rpt:report:update");
        sup.requireStatus(c, ReportStatus.COMPOSING, ReportStatus.IN_REVIEW, ReportStatus.APPROVED);
        RenderModel model = model(c);
        Map<String, Object> tv = rpt.get("template_version", RptJdbc.uuid(c.report(), "template_version_id"));
        HwpxPackage template = HwpxPackage.fromClasspath(RptJdbc.str(tv, "package_ref"));
        byte[] hwpx = HwpxBinder.bind(template, model).toZip();
        HwpxValidator.Report report = HwpxValidator.validate(hwpx);
        if (!report.valid()) {
            throw new DomainException(RptCodes.HWPX_INVALID, "생성한 HWPX 검증 실패: " + report.errors())
                    .with("errors", report.errors());
        }
        AnchorLayout.AnchorSet anchors = AnchorLayout.layout(revId, model);
        PdfConverterPort.Result pdfResult = pdf.convert("report.hwpx", hwpx, anchors.pageCount());
        byte[] anchorsJson = RptJson.canonicalBytes(anchors);
        UUID jobId = UuidV7.next();
        String base = revId + "/" + jobId + "/";
        storage.put(RptStorage.RENDERS, base + "report.hwpx", hwpx, "application/hwp+zip");
        storage.put(RptStorage.RENDERS, base + "report.pdf", pdfResult.pdf(), "application/pdf");
        storage.put(RptStorage.RENDERS, base + "anchors.json", anchorsJson, "application/json");
        String contentHash = sup.contentHash(revId);
        String hwpxSha = Hashing.sha256Hex(hwpx);
        String pdfSha = Hashing.sha256Hex(pdfResult.pdf());
        rpt.jdbc().update("""
                INSERT INTO rpt.render_job(id, revision_id, kind, status, progress, attempts, content_hash, converter,
                                           hwpx_object_key, hwpx_sha256, pdf_object_key, pdf_sha256, page_count,
                                           started_at, finished_at, created_by)
                VALUES (?, ?, 'PDF', 'DONE', 100, 1, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?)""",
                jobId, revId, contentHash, pdfResult.converter(), base + "report.hwpx", hwpxSha, base + "report.pdf",
                pdfSha, pdfResult.pageCount() > 0 ? pdfResult.pageCount() : anchors.pageCount(), sup.actorId());
        rpt.jdbc().update("DELETE FROM rpt.revision_anchor WHERE revision_id = ?", revId);
        rpt.jdbc().update("""
                INSERT INTO rpt.revision_anchor(revision_id, render_job_id, anchors, anchors_sha256, page_count, source)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)""", revId, jobId, new String(anchorsJson, StandardCharsets.UTF_8),
                Hashing.sha256Hex(anchorsJson), anchors.pageCount(), anchors.source());
        sup.touch(revId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("jobId", jobId);
        v.put("status", "DONE");
        v.put("converter", pdfResult.converter());
        v.put("hwpxSha256", hwpxSha);
        v.put("pdfSha256", pdfSha);
        v.put("pageCount", anchors.pageCount());
        v.put("anchorCount", anchors.anchors().size());
        v.put("contentHash", contentHash);
        v.put("hwpx", Map.of("paragraphs", report.paragraphs(), "tables", report.tables(), "pictures", report.pictures()));
        return v;
    }

    /** 바인딩 모델 — 표시 문자열은 모두 DB(스냅샷 파생) 값 */
    RenderModel model(Ctx c) {
        UUID revId = c.revId();
        Map<String, Object> report = c.report();
        Map<String, String> values = new HashMap<>();
        values.put("report.title", RptJdbc.str(report, "title"));
        values.put("report.typeName", ReportType.valueOf(RptJdbc.str(report, "report_type")).displayName());
        values.put("report.revNo", String.valueOf(c.revNo()));
        values.put("inspection.date", String.valueOf(RptJdbc.localDate(report.get("inspection_date"))));
        List<Map<String, Object>> g = rpt.jdbc().queryForList("SELECT * FROM rpt.grade_result WHERE revision_id = ?", revId);
        if (g.isEmpty()) {
            throw new DomainException(RptCodes.STATE, "compose 전에는 렌더할 수 없습니다");
        }
        values.put("grade.computed", RptJdbc.str(g.get(0), "computed_grade"));
        values.put("grade.final", g.get(0).get("final_grade") == null ? "미확정" : RptJdbc.str(g.get(0), "final_grade"));
        values.put("grade.weightedScore", RptJson.plain((java.math.BigDecimal) g.get(0).get("weighted_score"))
                .toPlainString());
        Map<String, Object> snap = rpt.get("snapshot", c.snapshotId());
        JsonNode doc = RptJdbc.tree(rpt.jdbc().queryForObject("SELECT document::text FROM rpt.snapshot WHERE id = ?",
                String.class, c.snapshotId()));
        values.put("facility.name", doc.path("meta").path("facilityName").asText(RptJdbc.str(report, "title")));
        List<Map<String, String>> ruleRows = new ArrayList<>();
        JsonNode pinned = RptJdbc.tree(rpt.jdbc().queryForObject(
                "SELECT rule_set_versions::text FROM rpt.snapshot WHERE id = ?", String.class, snap.get("id")));
        pinned.properties().forEach(e -> {
            String ref = e.getValue().asText();
            List<Map<String, Object>> rs = rpt.jdbc().queryForList("""
                    SELECT effective_from FROM rpt.rule_set WHERE rule_kind = ? AND facility_class = ? AND version = ?""",
                    e.getKey(), ref.substring(0, ref.indexOf('@')), ref.substring(ref.indexOf('@') + 1));
            ruleRows.add(Map.of("kind", e.getKey(), "version", ref,
                    "effectiveFrom", rs.isEmpty() ? "" : String.valueOf(RptJdbc.localDate(rs.get(0).get("effective_from")))));
        });
        List<RenderModel.Section> sections = new ArrayList<>();
        Map<String, RenderModel.Image> images = new LinkedHashMap<>();
        for (Map<String, Object> s : rpt.jdbc().queryForList(
                "SELECT id, section_key, title FROM rpt.section WHERE revision_id = ? ORDER BY order_no", revId)) {
            List<RenderModel.Block> blocks = new ArrayList<>();
            for (Map<String, Object> b : rpt.jdbc().queryForList(
                    "SELECT id, kind, text, data::text AS data FROM rpt.block WHERE section_id = ? ORDER BY order_no",
                    s.get("id"))) {
                String kind = RptJdbc.str(b, "kind");
                JsonNode data = RptJdbc.tree(b.get("data"));
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                String imageKey = null;
                if ("TABLE".equals(kind) && data != null) {
                    data.path("columns").forEach(x -> columns.add(x.asText()));
                    data.path("rows").forEach(r -> {
                        List<String> row = new ArrayList<>();
                        r.forEach(x -> row.add(x.asText()));
                        rows.add(row);
                    });
                }
                if ("IMAGE".equals(kind) && data != null) {
                    imageKey = data.path("objectKey").asText();
                    if (!images.containsKey(imageKey)) {
                        byte[] png = storage.get(RptStorage.RENDERS, imageKey);
                        int[] wh = size(png);
                        images.put(imageKey, new RenderModel.Image(imageKey, png, wh[0], wh[1]));
                    }
                }
                blocks.add(new RenderModel.Block(RptJdbc.uuid(b, "id"), kind, (String) b.get("text"),
                        List.copyOf(columns), List.copyOf(rows), imageKey));
            }
            sections.add(new RenderModel.Section(RptJdbc.str(s, "section_key"), RptJdbc.str(s, "title"),
                    List.copyOf(blocks)));
        }
        return new RenderModel(Map.copyOf(values), Map.of("ruleRows", List.copyOf(ruleRows)), List.copyOf(sections),
                Map.copyOf(images));
    }

    private static int[] size(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            return img == null ? new int[] {1200, 800} : new int[] {img.getWidth(), img.getHeight()};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 최신 렌더 산출물(HWPX · PDF) 다운로드 */
    @Transactional(readOnly = true)
    public byte[] file(UUID revId, String kind) {
        Ctx c = sup.read(revId, "rpt:report:read");
        if (c.status().isFrozen()) {
            String key = "pdf".equals(kind) ? RptJdbc.str(c.rev(), "pdf_object_key") : RptJdbc.str(c.rev(), "hwpx_object_key");
            return storage.get(RptStorage.PUBLISHED, key);
        }
        Map<String, Object> job = latestDone(revId).orElseThrow(() -> new DomainException(RptCodes.NOT_FOUND, "렌더 결과 없음"));
        return storage.get(RptStorage.RENDERS, RptJdbc.str(job, "pdf".equals(kind) ? "pdf_object_key" : "hwpx_object_key"));
    }

    java.util.Optional<Map<String, Object>> latestDone(UUID revId) {
        return rpt.jdbc().queryForList("""
                SELECT * FROM rpt.render_job WHERE revision_id = ? AND status = 'DONE' ORDER BY created_at DESC, id DESC
                 LIMIT 1""", revId).stream().findFirst();
    }

    /** 최신 렌더가 현재 본문과 같은지(편집 후 재렌더 강제) */
    void requireFreshRender(Ctx c) {
        Map<String, Object> job = latestDone(c.revId()).orElseThrow(() -> new DomainException(RptCodes.RENDER_STALE,
                "렌더(PDF) 결과가 없습니다 — 먼저 렌더하세요"));
        if (!sup.contentHash(c.revId()).equals(RptJdbc.str(job, "content_hash"))) {
            throw new DomainException(RptCodes.RENDER_STALE, "본문 · 등급이 렌더 이후 바뀌었습니다 — 다시 렌더하세요");
        }
    }
}
