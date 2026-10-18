package com.atomeng.erp.rpt.domain.grade;

import com.atomeng.erp.rpt.domain.RptJson;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 상태평가 · 안전등급 산정 결과(초안) — 부재 등급(근거 포함) → 층 → 동 → 시설물 점수 → 등급 (설계 06 B7.1).
 * {@link #sha256()} = 정규 JSON 해시: 같은 스냅샷 + 같은 rule 버전 → 항상 같은 값(TC-RPT-002, NFR-RPT-03).
 */
public record GradeResult(Map<String, String> ruleVersions, List<MemberRating> memberRatings,
                          List<FloorScore> floorScores, List<BuildingScore> buildingScores, BigDecimal facilityScore,
                          String computedGrade, List<String> appliedFloorRules) {

    /** 지표 근거: 지표 · 값 · 단위 · 판정 등급 · 적용 구간 상한(else 면 null) · 결함 표시번호 */
    public record Evidence(String indicator, BigDecimal value, String unit, String rating, BigDecimal threshold,
                           List<String> defectIds) {}

    public record MemberRating(String buildingCode, String floorCode, String memberType, int memberCount,
                               String rating, BigDecimal score, BigDecimal weight, BigDecimal affectedRatio,
                               List<Evidence> evidence) {}

    public record FloorScore(String buildingCode, String floorCode, BigDecimal score) {}

    public record BuildingScore(String buildingCode, BigDecimal score) {}

    public String sha256() {
        return RptJson.sha256(this);
    }
}
