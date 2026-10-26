package com.atomeng.erp.rpt.application;

import com.atomeng.erp.eqp.api.CalibrationValidityPort;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptRevisionQuery;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * rpt::api {@link RptRevisionQuery} 구현 — QAR 이 쓰는 공개 조회(권한 검사는 호출 모듈의 웹 경계 책임).
 */
@Service
public class RptRevisionQueryService implements RptRevisionQuery {

    private final RptJdbc rpt;
    private final RevisionSupport sup;
    private final RenderService render;
    private final SnapshotService snapshots;
    private final CalibrationValidityPort calibration;

    public RptRevisionQueryService(RptJdbc rpt, RevisionSupport sup, RenderService render, SnapshotService snapshots,
                                   CalibrationValidityPort calibration) {
        this.rpt = rpt;
        this.sup = sup;
        this.render = render;
        this.snapshots = snapshots;
        this.calibration = calibration;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevisionInfo> revision(UUID revisionId) {
        return rpt.row("report_revision", revisionId, false).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevisionInfo> currentRevision(UUID reportId) {
        return rpt.jdbc().queryForList("SELECT * FROM rpt.report_revision WHERE report_id = ? ORDER BY rev_no DESC LIMIT 1",
                reportId).stream().findFirst().map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Authorship> authorship(UUID revisionId) {
        return rpt.row("report_revision", revisionId, false).map(rev -> {
            Map<String, Object> report = rpt.get("report", RptJdbc.uuid(rev, "report_id"));
            UUID decidedBy = rpt.jdbc().queryForList("SELECT decided_by FROM rpt.grade_result WHERE revision_id = ?",
                    UUID.class, revisionId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return new Authorship(RptJdbc.uuid(report, "created_by"), RptJdbc.uuid(rev, "created_by"), decidedBy);
        });
    }

    private RevisionInfo info(Map<String, Object> rev) {
        UUID revId = RptJdbc.uuid(rev, "id");
        Map<String, Object> report = rpt.get("report", RptJdbc.uuid(rev, "report_id"));
        Integer snapVer = rev.get("snapshot_id") == null ? null
                : RptJdbc.integer(rpt.get("snapshot", RptJdbc.uuid(rev, "snapshot_id")), "snapshot_ver");
        String contentHash = sup.contentHash(revId);
        Optional<Map<String, Object>> job = render.latestDone(revId);
        String pdfSha = job.map(j -> RptJdbc.str(j, "pdf_sha256")).orElse(null);
        boolean fresh = job.map(j -> contentHash.equals(RptJdbc.str(j, "content_hash"))).orElse(false);
        return new RevisionInfo(revId, RptJdbc.uuid(report, "id"), RptJdbc.uuid(report, "project_id"),
                RptJdbc.uuid(report, "facility_id"), RptJdbc.str(report, "report_type"), RptJdbc.integer(rev, "rev_no"),
                RptJdbc.str(rev, "status"), snapVer, contentHash, pdfSha, fresh,
                RptJdbc.localDate(report.get("inspection_date")));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevisionContent> content(UUID revisionId) {
        Optional<RevisionInfo> info = revision(revisionId);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        RevisionInfo ri = info.get();
        List<Section> sections = new ArrayList<>();
        for (Map<String, Object> s : rpt.jdbc().queryForList(
                "SELECT id, section_key, title, order_no, source FROM rpt.section WHERE revision_id = ? ORDER BY order_no",
                revisionId)) {
            List<Block> blocks = new ArrayList<>();
            for (Map<String, Object> b : rpt.jdbc().queryForList("""
                    SELECT id, section_key, order_no, kind, text, data::text AS data, locked, source FROM rpt.block
                     WHERE section_id = ? ORDER BY order_no""", s.get("id"))) {
                JsonNode data = RptJdbc.tree(b.get("data"));
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                List<String> bound = new ArrayList<>();
                if (data != null) {
                    data.path("columns").forEach(c -> columns.add(c.asText()));
                    data.path("rows").forEach(r -> {
                        List<String> row = new ArrayList<>();
                        r.forEach(x -> row.add(x.asText()));
                        rows.add(List.copyOf(row));
                    });
                    data.path("bound").forEach(x -> bound.add(x.asText()));
                }
                blocks.add(new Block(RptJdbc.uuid(b, "id"), RptJdbc.str(b, "section_key"), RptJdbc.integer(b, "order_no"),
                        RptJdbc.str(b, "kind"), RptJdbc.str(b, "text"), Boolean.TRUE.equals(b.get("locked")),
                        RptJdbc.str(b, "source"), List.copyOf(columns), List.copyOf(rows), List.copyOf(bound)));
            }
            sections.add(new Section(RptJdbc.str(s, "section_key"), RptJdbc.str(s, "title"),
                    RptJdbc.integer(s, "order_no"), RptJdbc.str(s, "source"), List.copyOf(blocks)));
        }
        Grade grade = rpt.jdbc().queryForList("""
                SELECT id, computed_grade, final_grade, override_reason, override_evidence, weighted_score
                  FROM rpt.grade_result WHERE revision_id = ?""", revisionId).stream().findFirst()
                .map(this::grade).orElse(null);
        List<MemberGroup> groups = new ArrayList<>();
        List<Defect> defects = new ArrayList<>();
        String facilityName = null;
        Map<String, Object> rev = rpt.get("report_revision", revisionId);
        if (rev.get("snapshot_id") != null) {
            SnapshotDocument doc = snapshots.document(RptJdbc.uuid(rev, "snapshot_id"));
            facilityName = doc.meta() == null ? null : doc.meta().facilityName();
            doc.memberGroups().forEach(g -> groups.add(new MemberGroup(g.buildingCode(), g.floorCode(), g.memberType(),
                    g.memberCount())));
            for (SnapshotDocument.Defect d : doc.defects()) {
                Boolean valid = null;
                String reason = null;
                if (d.equipmentId() != null && d.measuredOn() != null) {
                    CalibrationValidityPort.ValidityResult v = calibration.validate(d.equipmentId(), d.measuredOn(),
                            ri.projectId());
                    valid = v.valid();
                    reason = v.reason();
                }
                defects.add(new Defect(d.id(), d.buildingCode(), d.floorCode(), d.memberType(), d.memberId(),
                        d.defectType(), d.widthMm(), d.lengthM(), d.areaM2(), d.equipmentId(), d.measuredOn(), valid,
                        reason));
            }
        }
        List<DrawingSheet> drawings = rpt.jdbc().queryForList("""
                SELECT building_code, floor_code, entity_count FROM rpt.drawing_sheet WHERE revision_id = ?
                 ORDER BY building_code, floor_code""", revisionId).stream()
                .map(d -> new DrawingSheet(RptJdbc.str(d, "building_code"), RptJdbc.str(d, "floor_code"),
                        RptJdbc.integer(d, "entity_count"))).toList();
        return Optional.of(new RevisionContent(ri, facilityName, List.copyOf(sections), grade, List.copyOf(groups),
                List.copyOf(defects), drawings));
    }

    private Grade grade(Map<String, Object> g) {
        List<MemberRating> members = new ArrayList<>();
        for (Map<String, Object> m : rpt.jdbc().queryForList("""
                SELECT building_code, floor_code, member_type, member_count, rating, evidence::text AS evidence
                  FROM rpt.member_rating WHERE grade_result_id = ? ORDER BY building_code, floor_code, member_type""",
                g.get("id"))) {
            JsonNode ev = RptJdbc.tree(m.get("evidence"));
            TreeSet<String> ids = new TreeSet<>();
            int n = 0;
            if (ev != null) {
                for (JsonNode e : ev) {
                    n++;
                    e.path("defectIds").forEach(x -> ids.add(x.asText()));
                }
            }
            members.add(new MemberRating(RptJdbc.str(m, "building_code"), RptJdbc.str(m, "floor_code"),
                    RptJdbc.str(m, "member_type"), RptJdbc.integer(m, "member_count"), RptJdbc.str(m, "rating"), n,
                    List.copyOf(ids)));
        }
        List<String> evidence = new ArrayList<>();
        if (g.get("override_evidence") instanceof java.sql.Array a) {
            try {
                for (Object o : (Object[]) a.getArray()) {
                    evidence.add(String.valueOf(o));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return new Grade(RptJdbc.str(g, "computed_grade"), RptJdbc.str(g, "final_grade"), RptJdbc.str(g, "override_reason"),
                List.copyOf(evidence), (BigDecimal) g.get("weighted_score"), List.copyOf(members));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnchorSet> anchors(UUID revisionId) {
        List<Map<String, Object>> rows = rpt.jdbc().queryForList(
                "SELECT anchors::text AS anchors, anchors_sha256 FROM rpt.revision_anchor WHERE revision_id = ?", revisionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        JsonNode n = RptJdbc.tree(rows.get(0).get("anchors"));
        List<Anchor> anchors = new ArrayList<>();
        for (JsonNode a : n.path("anchors")) {
            List<Rect> rects = new ArrayList<>();
            for (JsonNode r : a.path("rects")) {
                rects.add(new Rect(r.path("page").asInt(), r.path("x").decimalValue(), r.path("y").decimalValue(),
                        r.path("w").decimalValue(), r.path("h").decimalValue()));
            }
            anchors.add(new Anchor(UUID.fromString(a.path("blockId").asText()), a.path("sectionKey").asText(),
                    a.path("kind").asText(), a.path("page").asInt(), List.copyOf(rects), a.path("textHash").asText()));
        }
        return Optional.of(new AnchorSet(revisionId, n.path("source").asText(), n.path("unit").asText(),
                n.path("pageCount").asInt(), RptJdbc.str(rows.get(0), "anchors_sha256"), List.copyOf(anchors)));
    }

    private static final class Acc {
        final TreeSet<String> sources = new TreeSet<>();
        int count;
        Instant first;
        Instant last;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SectionEditors> sectionEditors(UUID revisionId) {
        Map<String, Map<UUID, Acc>> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : rpt.jdbc().queryForList("""
                SELECT e.section_key, e.editor_id, e.source, e.edited_at
                  FROM rpt.block_edit e LEFT JOIN rpt.section s ON s.revision_id = e.revision_id AND s.section_key = e.section_key
                 WHERE e.revision_id = ? ORDER BY coalesce(s.order_no, 999), e.section_key, e.id""", revisionId)) {
            Instant at = RptJdbc.instant(r.get("edited_at"));
            Acc a = acc.computeIfAbsent(RptJdbc.str(r, "section_key"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(RptJdbc.uuid(r, "editor_id"), k -> new Acc());
            a.sources.add(RptJdbc.str(r, "source"));
            a.count++;
            if (a.first == null) {
                a.first = at;
            }
            a.last = at;
        }
        List<SectionEditors> out = new ArrayList<>();
        acc.forEach((key, editors) -> {
            List<Editor> list = new ArrayList<>();
            editors.forEach((id, a) -> list.add(new Editor(id, List.copyOf(a.sources), a.count, a.first, a.last)));
            out.add(new SectionEditors(key, List.copyOf(list)));
        });
        return out;
    }

    /** REST 응답용(같은 내용) */
    public JsonNode anchorsJson(UUID revisionId) {
        return anchors(revisionId).map(RptJson::tree).orElse(null);
    }
}
