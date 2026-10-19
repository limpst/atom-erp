package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.eqp.api.CalibrationValidityPort;
import com.atomeng.erp.fld.api.FldSurveyQuery;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.MemberGroup;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스냅샷 동결 · 사전검증 (FR-RPT-002 · 003, BR-RPT-002 · 003, 06-IMPL Step 3 · 4).
 * <ul>
 *   <li>FLD 공개 API 가 아직 없어 결함 · 부재 · 동 데이터는 요청 본문(합성 FLD export)으로 받는다</li>
 *   <li>정규 JSON → SHA-256 → rpt-snapshots 저장 + rpt.snapshot(append-only) · 점검일 기준 rule 버전 고정</li>
 *   <li>사전검증(스냅샷 문서만 대상 — 재현성): BLOCKING 이 있으면 SNAPSHOT_FROZEN → DRAFT(A6) 이고 compose 불가</li>
 *   <li>검교정 유효성은 eqp::api {@link CalibrationValidityPort} 단일 진입점(BR-RPT-003, TC-RPT-003)</li>
 * </ul>
 */
@Service
public class SnapshotService {

    public static final Set<String> MEMBER_TYPES = Set.of("FOUNDATION", "COLUMN", "BEAM", "SLAB", "WALL", "ROOF");
    public static final Set<String> DEFECT_TYPES = Set.of("CRACK", "SPALL", "REBAR_EXPOSURE", "EFFLORESCENCE", "LEAK");

    public record Issue(String ruleCode, String severity, Map<String, Object> targetRef, String message) {}

    private final RevisionSupport sup;
    private final RptJdbc rpt;
    private final RuleTableService rules;
    private final RptStorage storage;
    private final CalibrationValidityPort calibration;
    private final OutboxPublisher outbox;
    private final FldSurveyQuery fldQuery;
    private final FacilityOwnershipGuard ownership;

    public SnapshotService(RevisionSupport sup, RuleTableService rules, RptStorage storage,
                           CalibrationValidityPort calibration, OutboxPublisher outbox, FldSurveyQuery fldQuery,
                           FacilityOwnershipGuard ownership) {
        this.fldQuery = fldQuery;
        this.ownership = ownership;
        this.sup = sup;
        this.rpt = sup.jdbc();
        this.rules = rules;
        this.storage = storage;
        this.calibration = calibration;
        this.outbox = outbox;
    }

    @Transactional
    public Map<String, Object> freeze(UUID reportId, SnapshotDocument input, String facilityName) {
        return freeze(reportId, input, facilityName, null);
    }

    /** FLD 결함 유형(A1.3) → RPT 상태평가 결함 유형. 표에 없는 유형(COR · HCB · DEF · TLT · DMG · FIN)은 스냅샷 제외 + 워터마크 기록 */
    static final Map<String, String> FLD_DEFECT_TYPES = Map.of("CRK", "CRACK", "SPA", "SPALL", "SPL", "SPALL",
            "RBX", "REBAR_EXPOSURE", "EFF", "EFFLORESCENCE", "LEK", "LEAK");
    /** FAC 부재 유형 → RPT 부재 유형(STAIR · ETC · 미연결 = 미분류 → VAL-MEMBER-UNCLASSIFIED, BR-FLD-001) */
    static final Map<String, String> FAC_MEMBER_TYPES = Map.of("COL", "COLUMN", "GIR", "BEAM", "BEAM", "BEAM",
            "SLAB", "SLAB", "WALL", "WALL", "FDN", "FOUNDATION");

    /**
     * fldRoundId 가 있으면 결함은 요청 본문 대신 fld::api {@link FldSurveyQuery#export} 에서 읽는다(06-IMPL Step 3 "요청 본문 → 모듈 API").
     * 동 · 부재 그룹(부재 수)은 FAC/HRQ 공개 API 전까지 요청 본문. 측정값은 FLD 확정 값만(잠정 · AI 미수락 제외).
     */
    @Transactional
    public Map<String, Object> freeze(UUID reportId, SnapshotDocument input, String facilityName, UUID fldRoundId) {
        Map<String, Object> report = rpt.lock("report", reportId);
        Map<String, Object> cur = rpt.jdbc().queryForMap(
                "SELECT id FROM rpt.report_revision WHERE report_id = ? AND rev_no = ?", reportId, report.get("current_rev"));
        Ctx c = sup.lock(RptJdbc.uuid(cur, "id"), "rpt:report:create");
        sup.requireStatus(c, ReportStatus.DRAFT, ReportStatus.SNAPSHOT_FROZEN, ReportStatus.COMPOSING);
        if (input.buildings() != null) {
            input.buildings().forEach(b -> ownership.requireSameClient(c.projectId(), b.buildingId()));   // SEC-RPT-04
        }

        Map<String, Object> watermarks = new LinkedHashMap<>();
        watermarks.put("fld", "request-body");
        if (fldRoundId != null) {
            if (!input.defects().isEmpty()) {
                throw new DomainException(RptCodes.INVALID, "defects 와 fldRoundId 를 함께 줄 수 없습니다");
            }
            FldSurveyQuery.RoundSummary round = fldQuery.round(fldRoundId)
                    .orElseThrow(() -> new DomainException(RptCodes.INVALID, "FLD 조사 회차 없음: " + fldRoundId));
            if (!round.projectId().equals(c.projectId())) {
                throw new DomainException(RptCodes.INVALID, "보고서 프로젝트의 조사 회차가 아닙니다");
            }
            FldSurveyQuery.SurveyExport ex = fldQuery.export(fldRoundId);
            List<Defect> defects = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            List<SnapshotDocument.Building> blds = input.buildings();
            for (FldSurveyQuery.DefectExport d : ex.defects()) {
                String type = FLD_DEFECT_TYPES.get(d.typeCode());
                if (type == null) {
                    skipped.add(d.code());
                    continue;
                }
                String bcode = blds.stream().filter(b -> d.buildingId().equals(b.buildingId()))
                        .map(SnapshotDocument.Building::code).findFirst()
                        .orElse(blds.size() == 1 ? blds.get(0).code() : null);
                defects.add(new Defect(d.code(), bcode, d.floorCode(),
                        d.memberType() == null ? null : FAC_MEMBER_TYPES.get(d.memberType()), d.memberCode(), type,
                        d.widthMm(), d.lengthM(), d.areaM2(), d.widthEquipmentId(), d.widthMeasuredOn(), null, d.memo()));
            }
            input = new SnapshotDocument(input.schemaVersion(), input.meta(), input.buildings(), input.memberGroups(),
                    defects);
            watermarks.put("fld", "fld::api");
            watermarks.put("roundId", fldRoundId.toString());
            watermarks.put("roundStatus", round.status());
            watermarks.put("asOfSeq", ex.asOfSeq());
            watermarks.put("skippedDefects", skipped);
        }
        String fc = RptJdbc.str(report, "facility_class");
        var inspection = RptJdbc.localDate(report.get("inspection_date"));
        RuleTableService.Resolved pinned = rules.resolve(fc, inspection);
        SnapshotDocument doc = input.withMeta(new SnapshotDocument.Meta(reportId, RptJdbc.str(report, "report_type"),
                facilityName == null ? RptJdbc.str(report, "title") : facilityName, fc, inspection));
        byte[] json = RptJson.canonicalBytes(doc);
        String sha = com.atomeng.common.core.util.Hashing.sha256Hex(json);
        int ver = rpt.jdbc().queryForObject("SELECT coalesce(max(snapshot_ver), 0) + 1 FROM rpt.snapshot WHERE report_id = ?",
                Integer.class, reportId);
        String key = reportId + "/" + ver + ".json";
        storage.put(RptStorage.SNAPSHOTS, key, json, "application/json");

        List<Issue> issues = validate(doc, c.projectId());
        int blocking = (int) issues.stream().filter(i -> i.severity().equals("BLOCKING")).count();
        UUID snapId = UuidV7.next();
        rpt.jdbc().update("""
                INSERT INTO rpt.snapshot(id, report_id, revision_id, snapshot_ver, object_key, sha256, document,
                                         source_watermarks, rule_set_versions, defect_count, blocking_count, frozen_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)""",
                snapId, reportId, c.revId(), ver, key, sha, new String(json, java.nio.charset.StandardCharsets.UTF_8),
                RptJdbc.json(watermarks), RptJdbc.json(pinned.tables().pinned()),
                doc.defects().size(), blocking, sup.actorId());
        for (Issue i : issues) {
            rpt.jdbc().update("""
                    INSERT INTO rpt.validation_issue(id, revision_id, snapshot_id, rule_code, severity, target_ref, message)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""", UuidV7.next(), c.revId(), snapId, i.ruleCode(), i.severity(),
                    RptJdbc.json(i.targetRef()), i.message());
        }
        rpt.jdbc().update("UPDATE rpt.report_revision SET snapshot_id = ? WHERE id = ?", snapId, c.revId());
        // A6: DRAFT → SNAPSHOT_FROZEN, BLOCKING 이면 다시 DRAFT / 작성 중 재동결은 COMPOSING 유지
        if (c.status() == ReportStatus.DRAFT) {
            sup.transition(c, ReportStatus.SNAPSHOT_FROZEN);
            if (blocking > 0) {
                sup.transition(sup.lock(c.revId(), "rpt:report:create"), ReportStatus.DRAFT);
            }
        } else if (c.status() == ReportStatus.SNAPSHOT_FROZEN && blocking > 0) {
            sup.transition(c, ReportStatus.DRAFT);
        } else {
            sup.touch(c.revId());
        }
        outbox.publish(new RptEvents.SnapshotFrozen(reportId, c.revId(), ver, sha, blocking));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("reportId", reportId);
        v.put("revisionId", c.revId());
        v.put("snapshotId", snapId);
        v.put("snapshotVer", ver);
        v.put("sha256", sha);
        v.put("ruleSetVersions", pinned.tables().pinned());
        v.put("sourceWatermarks", watermarks);
        v.put("defectCount", doc.defects().size());
        v.put("blockingCount", blocking);
        v.put("status", RptJdbc.str(rpt.get("report_revision", c.revId()), "status"));
        v.put("issues", issues);
        return v;
    }

    /** 사전검증 규칙(06-IMPL Step 4 의 기본 규칙 중 이번 단계 대상) — 스냅샷 문서만 본다 */
    List<Issue> validate(SnapshotDocument doc, UUID projectId) {
        List<Issue> out = new ArrayList<>();
        Set<String> buildings = new HashSet<>();
        doc.buildings().forEach(b -> buildings.add(b.code()));
        Set<String> groups = new HashSet<>();
        for (MemberGroup g : doc.memberGroups()) {
            groups.add(g.buildingCode() + "|" + g.floorCode() + "|" + g.memberType());
            if (!buildings.contains(g.buildingCode())) {
                out.add(new Issue("VAL-BUILDING-UNKNOWN", "BLOCKING", ref("member-group", g.buildingCode()),
                        "부재 그룹의 동 코드가 동 목록에 없음: " + g.buildingCode()));
            }
            if (g.memberCount() <= 0 || !MEMBER_TYPES.contains(g.memberType())) {
                out.add(new Issue("VAL-MEMBER-GROUP-INVALID", "BLOCKING", ref("member-group", g.buildingCode()),
                        "부재 그룹 오류(부재 수 1 이상 · 부재유형 %s): %s/%s/%s".formatted(MEMBER_TYPES, g.buildingCode(),
                                g.floorCode(), g.memberType())));
            }
        }
        if (doc.memberGroups().isEmpty()) {
            out.add(new Issue("VAL-NO-MEMBER-GROUP", "BLOCKING", ref("snapshot", "memberGroups"),
                    "부재 그룹이 없어 상태평가를 할 수 없음"));
        }
        Set<String> ids = new HashSet<>();
        for (Defect d : doc.defects()) {
            if (d.id() == null || !ids.add(d.id())) {
                out.add(new Issue("VAL-DEFECT-ID", "BLOCKING", ref("defect", String.valueOf(d.id())),
                        "결함 표시번호 누락 또는 중복: " + d.id()));
                continue;
            }
            if (d.memberType() == null || d.memberType().isBlank() || !MEMBER_TYPES.contains(d.memberType())) {
                out.add(new Issue("VAL-MEMBER-UNCLASSIFIED", "BLOCKING", ref("defect", d.id()),
                        "부재 유형 미지정 결함(상태평가 불가): " + d.id()));
            } else if (!groups.contains(d.buildingCode() + "|" + d.floorCode() + "|" + d.memberType())) {
                out.add(new Issue("VAL-GROUP-MISSING", "BLOCKING", ref("defect", d.id()),
                        "결함의 동·층·부재 그룹(부재 수)이 없음: " + d.id()));
            }
            if (!DEFECT_TYPES.contains(String.valueOf(d.defectType()))) {
                out.add(new Issue("VAL-DEFECT-TYPE", "BLOCKING", ref("defect", d.id()), "결함 유형 오류: " + d.defectType()));
            }
            if ("CRACK".equals(d.defectType()) && d.widthMm() == null) {
                out.add(new Issue("VAL-CRACK-WIDTH-NULL", "BLOCKING", ref("defect", d.id()),
                        "균열 결함인데 폭 미측정: " + d.id()));
            }
            if (d.equipmentId() != null && d.measuredOn() != null) {
                CalibrationValidityPort.ValidityResult v = calibration.validate(d.equipmentId(), d.measuredOn(), projectId);
                if (!v.valid()) {
                    out.add(new Issue("VAL-CALIBRATION-EXPIRED", "BLOCKING", ref("measurement", d.id()),
                            "측정 시점(%s) 검교정 무효 장비(%s) 측정값: %s (사유 %s)".formatted(d.measuredOn(),
                                    d.equipmentId(), d.id(), v.reason())));
                }
            }
            if (d.polyline() == null || d.polyline().isEmpty()) {
                out.add(new Issue("VAL-DRAWING-POLYLINE-MISSING", "WARNING", ref("defect", d.id()),
                        "도면 좌표 없음 — 망도는 격자 위치로 표시: " + d.id()));
            }
        }
        return out;
    }

    private static Map<String, Object> ref(String type, String id) {
        return Map.of("module", "fld", "type", type, "id", id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> issues(UUID revId) {
        Ctx c = sup.read(revId, "rpt:report:read");
        if (c.snapshotId() == null) {
            return List.of();
        }
        return rpt.jdbc().queryForList("""
                SELECT rule_code AS "ruleCode", severity, target_ref::text AS "targetRef", message
                  FROM rpt.validation_issue WHERE snapshot_id = ? ORDER BY severity, rule_code, message""",
                c.snapshotId()).stream().map(m -> {
                    Map<String, Object> x = new LinkedHashMap<>(m);
                    x.put("targetRef", RptJdbc.tree(m.get("targetRef")));
                    return x;
                }).toList();
    }

    /** 동결 문서 로드(compose · 렌더 공용) */
    public SnapshotDocument document(UUID snapshotId) {
        if (snapshotId == null) {
            throw new DomainException(RptCodes.STATE, "스냅샷 동결 전입니다");
        }
        Map<String, Object> s = rpt.get("snapshot", snapshotId);
        JsonNode n = RptJdbc.tree(rpt.jdbc().queryForObject("SELECT document::text FROM rpt.snapshot WHERE id = ?",
                String.class, snapshotId));
        try {
            return RptJson.MAPPER.treeToValue(n, SnapshotDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException("스냅샷 문서 해석 실패: " + s.get("id"), e);
        }
    }
}
