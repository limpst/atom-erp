package com.atomeng.erp.dem.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.domain.AdjacentMatcher;
import com.atomeng.erp.dem.domain.AdjacentMatcher.Defect;
import com.atomeng.erp.dem.domain.AdjacentMatcher.Thresholds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인접건축물 사전 · 사후 조사와 비교 (FR-DEM-018, 설계 B7.2, TC-DEM-011).
 * FLD 인접조사 모드 · {@code fld.adjacent-survey.completed} 가 아직 없어 조사 결과(결함 핀 좌표 · 균열폭 · 길이)는 DEM API 로 받는다.
 * 비교는 사후 조사일 기준 rule_param(거리 · 균열폭 · 길이 임계)을 고정해 결과와 함께 저장(append-only, 결과 SHA-256).
 */
@Service
public class AdjacentSurveyService {

    public record DefectInput(String defectNo, String floor, String memberRef, String defectType, BigDecimal x,
                              BigDecimal y, BigDecimal crackWidthMm, BigDecimal lengthMm, UUID photoId) {}

    public record Survey(UUID adjacentBuildingId, String adjacentName, String phase, LocalDate surveyedOn,
                         String drawingVersion, List<DefectInput> defects) {}

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DemJdbc dem;
    private final RuleParams rules;
    private final CurrentActor actor;
    private final ObjectMapper om;

    public AdjacentSurveyService(DemJdbc dem, RuleParams rules, CurrentActor actor, ObjectMapper om) {
        this.dem = dem;
        this.rules = rules;
        this.actor = actor;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> createSurvey(UUID projectId, Survey s) {
        dem.one("DEM 프로젝트", "SELECT project_id FROM dem.project WHERE project_id = ?", projectId);
        UUID by = actor.actorIdOrSystem();
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.adjacent_survey(id, project_id, adjacent_building_id, adjacent_name, phase, surveyed_on,
                                                drawing_version, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, s.adjacentBuildingId(), s.adjacentName(), s.phase(),
                DemJdbc.d(s.surveyedOn()), s.drawingVersion(), by);
        for (DefectInput d : s.defects() == null ? List.<DefectInput>of() : s.defects()) {
            dem.jdbc().update("""
                    INSERT INTO dem.adjacent_defect(id, survey_id, defect_no, floor, member_ref, defect_type, x_m, y_m,
                                                    crack_width_mm, length_mm, photo_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), id, d.defectNo(), d.floor(),
                    d.memberRef(), d.defectType(), d.x(), d.y(), d.crackWidthMm(), d.lengthMm(), d.photoId());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("projectId", projectId);
        m.put("phase", s.phase());
        m.put("defectCount", s.defects() == null ? 0 : s.defects().size());
        return m;
    }

    @Transactional
    public Map<String, Object> compare(UUID projectId, UUID preId, UUID postId) {
        var pre = survey(preId);
        var post = survey(postId);
        if (!projectId.equals(pre.get("project_id")) || !projectId.equals(post.get("project_id"))) {
            throw new DomainException(DemErrors.INVALID, "다른 프로젝트의 조사입니다");
        }
        if (!"PRE".equals(pre.get("phase")) || !"POST".equals(post.get("phase"))) {
            throw new DomainException(DemErrors.INVALID, "사전(PRE) · 사후(POST) 조사를 하나씩 지정해야 합니다");
        }
        if (!Objects.equals(pre.get("adjacent_building_id"), post.get("adjacent_building_id"))
                || !Objects.equals(pre.get("adjacent_name"), post.get("adjacent_name"))) {
            throw new DomainException(DemErrors.INVALID, "같은 인접 건축물의 사전 · 사후 조사가 아닙니다");
        }
        if (pre.get("drawing_version") != null && post.get("drawing_version") != null
                && !pre.get("drawing_version").equals(post.get("drawing_version"))) {
            throw new DomainException(DemErrors.INVALID, "사전 · 사후 조사의 도면 버전이 달라 좌표 매칭을 할 수 없습니다")
                    .with("reason", "DEM_DRAWING_VERSION_MISMATCH");
        }
        LocalDate asOf = DemJdbc.ld(post.get("surveyed_on"));
        Thresholds t = new Thresholds(
                rules.get("ADJ_MATCH_DISTANCE_M", asOf).path("m").decimalValue(),
                rules.get("ADJ_CRACK_WIDTH_GROWTH_MM", asOf).path("mm").decimalValue(),
                rules.get("ADJ_CRACK_LENGTH_GROWTH_PCT", asOf).path("pct").decimalValue());
        String building = pre.get("adjacent_building_id") != null ? pre.get("adjacent_building_id").toString()
                : (String) pre.get("adjacent_name");
        var result = AdjacentMatcher.compare(defects(preId, building), defects(postId, building), t);
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("maxDistanceM", t.maxDistanceM());
        thresholds.put("widthGrowthMm", t.widthGrowthMm());
        thresholds.put("lengthGrowthPct", t.lengthGrowthPct());
        thresholds.put("asOf", asOf.toString());
        String resultJson;
        try {
            resultJson = CANONICAL.writeValueAsString(om.convertValue(result.rows(), Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String sha = HashChain.sha256(new ByteArrayInputStream(resultJson.getBytes(StandardCharsets.UTF_8)));
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.adjacent_comparison(id, project_id, pre_survey_id, post_survey_id, thresholds, summary,
                                                    result, result_sha256, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)""", id, projectId, preId, postId,
                dem.json(thresholds), dem.json(result.summary()), resultJson, sha, actor.actorIdOrSystem());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("preSurveyId", preId);
        m.put("postSurveyId", postId);
        m.put("thresholds", thresholds);
        m.put("summary", result.summary());
        m.put("rows", result.rows());
        m.put("resultSha256", sha);
        return m;
    }

    private Map<String, Object> survey(UUID id) {
        return dem.one("인접조사", """
                SELECT id, project_id, adjacent_building_id, adjacent_name, phase, surveyed_on, drawing_version
                  FROM dem.adjacent_survey WHERE id = ?""", id);
    }

    private List<Defect> defects(UUID surveyId, String building) {
        return dem.jdbc().query("""
                SELECT id, defect_no, floor, member_ref, defect_type, x_m, y_m, crack_width_mm, length_mm
                  FROM dem.adjacent_defect WHERE survey_id = ? ORDER BY defect_no""", (rs, i) -> new Defect(
                rs.getObject("id", UUID.class), rs.getString("defect_no"), building, rs.getString("floor"),
                rs.getString("member_ref"), rs.getString("defect_type"), rs.getBigDecimal("x_m"), rs.getBigDecimal("y_m"),
                rs.getBigDecimal("crack_width_mm"), rs.getBigDecimal("length_mm")), surveyId);
    }
}
