package com.atomeng.erp.rpt.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.ReportType;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.drawing.DefectMapPng;
import com.atomeng.erp.rpt.domain.drawing.DxfR12Writer;
import com.atomeng.erp.rpt.domain.grade.GradeEngine;
import com.atomeng.erp.rpt.domain.grade.GradeResult;
import com.atomeng.erp.rpt.domain.grade.QuantityAggregator;
import com.atomeng.erp.rpt.domain.grade.SafetyGrade;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 생성(compose) · 등급 확정 (FR-RPT-004~007 · 011, BR-RPT-001 · 002 · 005, 06-IMPL Step 5 · 6 · 7).
 * <ol>
 *   <li>BLOCKING 사전검증이 있으면 409 RPT-409-002(TC-RPT-003)</li>
 *   <li>스냅샷에 고정된 rule 버전으로 등급 산정(순수 엔진) → grade_result · member_rating(근거 포함)</li>
 *   <li>결함물량표 · 층별 외관조사망도(DXF R12 + 미리보기 PNG) → drawing_sheet</li>
 *   <li>섹션/블록 자동 생성(수치 블록은 잠금) — 사람이 고친 섹션(source=HUMAN)은 재생성 때 유지</li>
 *   <li>리비전 최초 compose 면 {@code rpt.report.drafted}(v0.2) 1회</li>
 * </ol>
 */
@Service
public class ComposeService {

    /** 표준 섹션 키 · 제목 · 순서 */
    public static final List<String[]> SECTIONS = List.of(
            new String[] {"ch1.overview", "1. 개요"},
            new String[] {"ch4.quantities", "4. 결함물량표"},
            new String[] {"ch5.drawings", "5. 외관조사망도"},
            new String[] {"ch6.condition", "6. 상태평가 및 안전등급"},
            new String[] {"ch7.opinion", "7. 종합 의견"});

    private final RevisionSupport sup;
    private final RptJdbc rpt;
    private final SnapshotService snapshots;
    private final RuleTableService rules;
    private final GradeEngine engine;
    private final RptStorage storage;
    private final SectionService sections;
    private final OutboxPublisher outbox;
    private final AuditLog audit;

    public ComposeService(RevisionSupport sup, SnapshotService snapshots, RuleTableService rules, GradeEngine engine,
                          RptStorage storage, SectionService sections, OutboxPublisher outbox, AuditLog audit) {
        this.sup = sup;
        this.rpt = sup.jdbc();
        this.snapshots = snapshots;
        this.rules = rules;
        this.engine = engine;
        this.storage = storage;
        this.sections = sections;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> compose(UUID revId) {
        Ctx c = sup.lock(revId, "rpt:report:update");
        sup.requireStatus(c, ReportStatus.SNAPSHOT_FROZEN, ReportStatus.COMPOSING);
        Map<String, Object> snap = rpt.get("snapshot", c.snapshotId());
        int blocking = RptJdbc.integer(snap, "blocking_count");
        if (blocking > 0) {
            throw new DomainException(RptCodes.BLOCKING_ISSUES,
                    "BLOCKING 사전검증 이슈 %d건 — 원천(FLD) 보완 후 재동결하세요".formatted(blocking))
                    .with("blockingCount", blocking);
        }
        SnapshotDocument doc = snapshots.document(c.snapshotId());
        RuleTableService.Resolved pinned = rules.load(RptJdbc.tree(rpt.jdbc().queryForObject(
                "SELECT rule_set_versions::text FROM rpt.snapshot WHERE id = ?", String.class, c.snapshotId())));
        GradeResult grade = engine.compute(doc, pinned.tables());

        // 등급 결과(재생성 시 교체, 확정 등급은 산정 결과가 같을 때만 유지)
        List<Map<String, Object>> prev = rpt.jdbc().queryForList(
                "SELECT id, computed_grade, final_grade, override_reason FROM rpt.grade_result WHERE revision_id = ?", revId);
        rpt.jdbc().update("DELETE FROM rpt.member_rating WHERE grade_result_id IN "
                + "(SELECT id FROM rpt.grade_result WHERE revision_id = ?)", revId);
        rpt.jdbc().update("DELETE FROM rpt.grade_result WHERE revision_id = ?", revId);
        UUID gid = UuidV7.next();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("floorScores", grade.floorScores());
        scope.put("buildingScores", grade.buildingScores());
        scope.put("appliedFloorRules", grade.appliedFloorRules());
        rpt.jdbc().update("""
                INSERT INTO rpt.grade_result(id, revision_id, rule_set_versions, scope_tree, weighted_score, computed_grade,
                                             result_sha256)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""", gid, revId, RptJdbc.json(grade.ruleVersions()),
                RptJdbc.json(scope), grade.facilityScore(), grade.computedGrade(), grade.sha256());
        for (GradeResult.MemberRating m : grade.memberRatings()) {
            rpt.jdbc().update("""
                    INSERT INTO rpt.member_rating(id, grade_result_id, building_code, floor_code, member_type, member_count,
                                                  rating, score, weight, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""", UuidV7.next(), gid, m.buildingCode(), m.floorCode(),
                    m.memberType(), m.memberCount(), m.rating(), m.score(), m.weight(), RptJdbc.json(m.evidence()));
        }
        if (!prev.isEmpty() && prev.get(0).get("final_grade") != null
                && grade.computedGrade().equals(RptJdbc.str(prev.get(0), "computed_grade"))) {
            rpt.jdbc().update("UPDATE rpt.grade_result SET final_grade = ?, override_reason = ? WHERE id = ?",
                    RptJdbc.str(prev.get(0), "final_grade"), prev.get(0).get("override_reason"), gid);
        }

        // 망도: 결함이 있는 (동, 층)마다 DXF + PNG
        rpt.jdbc().update("DELETE FROM rpt.drawing_sheet WHERE revision_id = ?", revId);
        Map<String, List<Defect>> byFloor = new TreeMap<>();
        for (Defect d : doc.defects()) {
            byFloor.computeIfAbsent(d.buildingCode() + "\u0000" + d.floorCode(), k -> new ArrayList<>()).add(d);
        }
        List<Map<String, String>> drawings = new ArrayList<>();
        for (var e : byFloor.entrySet()) {
            String[] k = e.getKey().split("\u0000", -1);
            DxfR12Writer.Sheet sheet = DxfR12Writer.write(k[0], k[1], e.getValue());
            DefectMapPng.Png png = DefectMapPng.render(e.getValue(), 1200);
            byte[] dxf = sheet.dxf().getBytes(StandardCharsets.US_ASCII);
            String base = revId + "/drawings/" + k[0] + "-" + k[1];
            storage.put(RptStorage.RENDERS, base + ".dxf", dxf, "application/dxf");
            storage.put(RptStorage.RENDERS, base + ".png", png.bytes(), "image/png");
            rpt.jdbc().update("""
                    INSERT INTO rpt.drawing_sheet(id, revision_id, building_code, floor_code, dxf_object_key, dxf_sha256,
                                                  png_object_key, entity_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), revId, k[0], k[1], base + ".dxf",
                    Hashing.sha256Hex(dxf), base + ".png", sheet.entityCount());
            drawings.add(Map.of("buildingCode", k[0], "floorCode", k[1], "key", base + ".png",
                    "caption", "%s %s 외관조사망도 — 결함 %d건".formatted(k[0], k[1], e.getValue().size())));
        }

        // 섹션 · 블록
        Map<String, Object> report = c.report();
        ReportType type = ReportType.valueOf(RptJdbc.str(report, "report_type"));
        Map<String, List<SectionService.NewBlock>> content = new LinkedHashMap<>();
        Set<String> floors = new HashSet<>();
        doc.memberGroups().forEach(g -> floors.add(g.buildingCode() + "/" + g.floorCode()));
        content.put("ch1.overview", List.of(SectionService.NewBlock.locked("PARAGRAPH",
                "%s %s — 점검일 %s, 조사 동 %d개 · 층 %d개 · 부재 그룹 %d개 · 결함 %d건(스냅샷 v%d, SHA-256 %s…)."
                        .formatted(doc.meta().facilityName(), type.displayName(), doc.meta().inspectionDate(),
                                doc.buildings().size(), floors.size(), doc.memberGroups().size(), doc.defects().size(),
                                RptJdbc.integer(snap, "snapshot_ver"), RptJdbc.str(snap, "sha256").substring(0, 12)),
                Map.of("bound", List.of("meta.facilityName", "meta.inspectionDate", "defects.length")))));
        List<List<String>> qrows = new ArrayList<>();
        for (QuantityAggregator.Row r : QuantityAggregator.aggregate(doc)) {
            qrows.add(List.of(r.buildingCode(), r.floorCode(), r.memberType(), r.defectType(),
                    String.valueOf(r.count()), r.lengthM().toPlainString(), r.areaM2().toPlainString()));
        }
        content.put("ch4.quantities", List.of(SectionService.NewBlock.table(
                List.of("동", "층", "부재", "결함", "개소", "길이(m)", "면적(m²)"), qrows)));
        List<SectionService.NewBlock> drawingBlocks = new ArrayList<>();
        for (Map<String, String> d : drawings) {
            drawingBlocks.add(SectionService.NewBlock.image(d.get("caption"), d.get("key")));
        }
        if (drawingBlocks.isEmpty()) {
            drawingBlocks.add(SectionService.NewBlock.locked("PARAGRAPH", "기록된 결함이 없어 외관조사망도를 생략합니다.", null));
        }
        content.put("ch5.drawings", drawingBlocks);
        List<List<String>> grows = new ArrayList<>();
        for (GradeResult.MemberRating m : grade.memberRatings()) {
            StringBuilder ev = new StringBuilder();
            for (GradeResult.Evidence e : m.evidence()) {
                if (!e.defectIds().isEmpty()) {
                    ev.append(ev.isEmpty() ? "" : "; ").append(e.indicator()).append(' ')
                            .append(RptJson.plain(e.value()).toPlainString()).append(e.unit().equals("mm") ? "mm" : "")
                            .append("→").append(e.rating());
                }
            }
            grows.add(List.of(m.buildingCode(), m.floorCode(), m.memberType(), String.valueOf(m.memberCount()),
                    m.rating(), RptJson.plain(m.score()).toPlainString(), m.weight().toPlainString(),
                    ev.isEmpty() ? "결함 없음" : ev.toString()));
        }
        String summary = "가중 점수 %s → 산정 등급 %s (rule %s)%s".formatted(grade.facilityScore().toPlainString(),
                grade.computedGrade(), String.join(", ", grade.ruleVersions().values().stream().distinct().toList()),
                grade.appliedFloorRules().isEmpty() ? "" : " · 하한 규칙 " + String.join(", ", grade.appliedFloorRules()));
        content.put("ch6.condition", List.of(
                SectionService.NewBlock.table(List.of("동", "층", "부재", "부재 수", "등급", "점수", "가중치", "판정 근거"), grows),
                SectionService.NewBlock.locked("PARAGRAPH", summary, Map.of("bound", List.of("grade.facilityScore",
                        "grade.computedGrade")))));
        content.put("ch7.opinion", List.of(SectionService.NewBlock.editable("PARAGRAPH",
                "결함 %d건을 조사하였으며 상태평가 결과는 위 표와 같다. 보수·보강 우선순위와 종합 의견은 책임기술자가 작성한다."
                        .formatted(doc.defects().size()))));
        sections.regenerate(revId, SECTIONS, content);

        boolean first = c.rev().get("drafted_at") == null;
        Instant now = sup.clock().instant();
        if (first) {
            rpt.jdbc().update("UPDATE rpt.report_revision SET drafted_at = ? WHERE id = ?", RptJdbc.ts(now), revId);
        }
        if (c.status() == ReportStatus.SNAPSHOT_FROZEN) {
            sup.transition(c, ReportStatus.COMPOSING);
        } else {
            sup.touch(revId);
        }
        sup.storeContentHash(revId);
        if (first) {
            outbox.publish(new RptEvents.ReportDrafted(c.reportId(), revId, c.projectId(), c.revNo(), now));
        }
        Map<String, Object> v = new LinkedHashMap<>(grade(revId, false));
        v.put("status", RptJdbc.str(rpt.get("report_revision", revId), "status"));
        v.put("drawings", drawings.size());
        v.put("drafted", first);
        return v;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> grade(UUID revId) {
        return grade(revId, true);
    }

    private Map<String, Object> grade(UUID revId, boolean checkAccess) {
        if (checkAccess) {
            sup.read(revId, "rpt:report:read");
        }
        List<Map<String, Object>> rows = rpt.jdbc().queryForList("SELECT * FROM rpt.grade_result WHERE revision_id = ?",
                revId);
        if (rows.isEmpty()) {
            throw new DomainException(RptCodes.NOT_FOUND, "등급 산정 전입니다(compose 필요)");
        }
        Map<String, Object> g = rows.get(0);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("revisionId", revId);
        v.put("ruleSet", RptJdbc.tree(g.get("rule_set_versions")));
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> m : rpt.jdbc().queryForList("""
                SELECT building_code, floor_code, member_type, member_count, rating, score, weight, evidence::text AS evidence
                  FROM rpt.member_rating WHERE grade_result_id = ? ORDER BY building_code, floor_code, member_type""",
                g.get("id"))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("buildingCode", m.get("building_code"));
            x.put("floorCode", m.get("floor_code"));
            x.put("memberType", m.get("member_type"));
            x.put("count", m.get("member_count"));
            x.put("rating", RptJdbc.str(m, "rating"));
            x.put("score", m.get("score"));
            x.put("weight", m.get("weight"));
            x.put("evidence", RptJdbc.tree(m.get("evidence")));
            members.add(x);
        }
        v.put("members", members);
        v.put("scopeTree", RptJdbc.tree(g.get("scope_tree")));
        v.put("weightedScore", g.get("weighted_score"));
        v.put("computedGrade", RptJdbc.str(g, "computed_grade"));
        v.put("finalGrade", RptJdbc.str(g, "final_grade"));
        v.put("overridden", g.get("final_grade") != null
                && !RptJdbc.str(g, "final_grade").equals(RptJdbc.str(g, "computed_grade")));
        v.put("overrideReason", g.get("override_reason"));
        v.put("resultSha256", RptJdbc.str(g, "result_sha256"));
        v.put("decidedBy", g.get("decided_by"));
        return v;
    }

    /**
     * 책임기술자 등급 확정 / override (BR-RPT-005): 산정과 다르면 사유 100자 이상 + 근거 결함 1건 이상(스냅샷에 있는 번호),
     * 감사 로그(before/after). If-Match = 리비전 ETag.
     */
    @Transactional
    public Map<String, Object> decide(UUID revId, long expectedVersion, String finalGrade, String reason,
                                      List<String> evidenceDefectIds) {
        Ctx c = sup.lock(revId, "rpt:grade:decide");
        IfMatch.check(expectedVersion, RptJdbc.version(c.rev()));
        sup.requireStatus(c, ReportStatus.COMPOSING);
        Map<String, Object> g = rpt.jdbc().queryForList("SELECT * FROM rpt.grade_result WHERE revision_id = ?", revId)
                .stream().findFirst().orElseThrow(() -> new DomainException(RptCodes.STATE, "등급 산정 전입니다"));
        SafetyGrade fin = SafetyGrade.valueOf(finalGrade);
        String computed = RptJdbc.str(g, "computed_grade");
        List<String> evidence = evidenceDefectIds == null ? List.of() : evidenceDefectIds;
        if (!fin.name().equals(computed)) {
            if (reason == null || reason.strip().codePointCount(0, reason.strip().length()) < 100 || evidence.isEmpty()) {
                throw new DomainException(RptCodes.OVERRIDE_REASON,
                        "산정 등급(%s)과 다르게 확정하려면 사유 100자 이상과 근거 결함 1건 이상이 필요합니다".formatted(computed));
            }
            Set<String> known = new HashSet<>();
            snapshots.document(c.snapshotId()).defects().forEach(d -> known.add(d.id()));
            List<String> unknown = evidence.stream().filter(e -> !known.contains(e)).toList();
            if (!unknown.isEmpty()) {
                throw new DomainException(RptCodes.OVERRIDE_REASON, "스냅샷에 없는 근거 결함: " + unknown);
            }
        }
        boolean overridden = !fin.name().equals(computed);
        rpt.jdbc().update("""
                UPDATE rpt.grade_result SET final_grade = ?, override_reason = ?, override_evidence = ?,
                       decided_by = ?, decided_at = now() WHERE id = ?""", fin.name(), overridden ? reason.strip() : null,
                overridden ? evidence.toArray(String[]::new) : null, sup.actorId(), g.get("id"));
        sup.touch(revId);
        sup.storeContentHash(revId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("finalGrade", g.get("final_grade"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("computedGrade", computed);
        after.put("finalGrade", fin.name());
        after.put("overridden", overridden);
        audit.append("RPT", AuditLog.Action.APPROVE, "GradeResult", RptJdbc.uuid(g, "id"), before, after);
        Map<String, Object> v = new LinkedHashMap<>(grade(revId, false));
        v.put("revisionVersion", RptJdbc.version(rpt.get("report_revision", revId)));
        return v;
    }

    @Transactional(readOnly = true)
    public byte[] drawingDxf(UUID revId, String buildingCode, String floorCode) {
        sup.read(revId, "rpt:report:read");
        List<Map<String, Object>> rows = rpt.jdbc().queryForList("""
                SELECT dxf_object_key FROM rpt.drawing_sheet WHERE revision_id = ? AND building_code = ? AND floor_code = ?""",
                revId, buildingCode, floorCode);
        if (rows.isEmpty()) {
            throw new DomainException(RptCodes.NOT_FOUND, "망도 없음: %s %s".formatted(buildingCode, floorCode));
        }
        return storage.get(RptStorage.RENDERS, RptJdbc.str(rows.get(0), "dxf_object_key"));
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
