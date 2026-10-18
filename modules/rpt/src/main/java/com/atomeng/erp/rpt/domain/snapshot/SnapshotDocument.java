package com.atomeng.erp.rpt.domain.snapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 동결 스냅샷 문서 v1 (설계 06 A3-2, 06-IMPL Step 3) — FLD 결함 · 부재 그룹(동·층·부재유형·부재 수) · 동(연면적) ·
 * 측정 장비. 사진 바이너리는 넣지 않는다. FLD 공개 API 가 없어 이번 단계는 요청 본문(합성 FLD export)으로 받는다.
 */
public record SnapshotDocument(String schemaVersion, Meta meta, List<Building> buildings, List<MemberGroup> memberGroups,
                               List<Defect> defects) {

    public SnapshotDocument {
        buildings = buildings == null ? List.of() : List.copyOf(buildings);
        memberGroups = memberGroups == null ? List.of() : List.copyOf(memberGroups);
        defects = defects == null ? List.of() : List.copyOf(defects);
    }

    /** 보고서 메타(서버가 채움): 시설물 분류는 rule table 선택, 점검일은 rule 고정 기준일 */
    public record Meta(UUID reportId, String reportType, String facilityName, String facilityClass,
                       LocalDate inspectionDate) {}

    /** 동: code(보고서 내 동 코드) · buildingId(fac.building) · 연면적(시설물 점수 가중) */
    public record Building(String code, String name, UUID buildingId, BigDecimal grossAreaM2) {}

    /** 부재 그룹 = (동, 층, 부재유형) + 부재 수 — 상태평가 단위 */
    public record MemberGroup(String buildingCode, String floorCode, String memberType, int memberCount) {}

    /**
     * 결함. defectType: CRACK · SPALL · REBAR_EXPOSURE · EFFLORESCENCE · LEAK. polyline = 도면 좌표(mm) 점 목록.
     * equipmentId · measuredOn = 측정 장비(검교정 유효성 BR-RPT-003).
     */
    public record Defect(String id, String buildingCode, String floorCode, String memberType, String memberId,
                         String defectType, BigDecimal widthMm, BigDecimal lengthM, BigDecimal areaM2,
                         UUID equipmentId, LocalDate measuredOn, List<List<BigDecimal>> polyline, String note) {

        public boolean inGroup(MemberGroup g) {
            return g.buildingCode().equals(buildingCode) && g.floorCode().equals(floorCode)
                    && g.memberType().equals(memberType);
        }
    }

    /** 메타 지정 + 배열 안정 정렬(동 코드 · 그룹 키 · 결함 번호) — 입력 순서가 달라도 같은 해시(06-IMPL Step 3) */
    public SnapshotDocument withMeta(Meta m) {
        return new SnapshotDocument(schemaVersion == null ? "1" : schemaVersion, m,
                buildings.stream().sorted(java.util.Comparator.comparing(Building::code,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).toList(),
                memberGroups.stream().sorted(java.util.Comparator.comparing(MemberGroup::buildingCode)
                        .thenComparing(MemberGroup::floorCode).thenComparing(MemberGroup::memberType)).toList(),
                defects.stream().sorted(java.util.Comparator.comparing(Defect::id,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).toList());
    }
}
