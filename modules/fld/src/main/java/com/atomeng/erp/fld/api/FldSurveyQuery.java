package com.atomeng.erp.fld.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FLD 조사 결과 공개 조회 (설계 05 B8 "RPT: defect_current 읽기 모델", 06-IMPL Step 3 스냅샷 원천, 07 QAR AC-PHO 원천).
 * 다른 모듈은 fld 테이블을 읽지 않고 이 인터페이스만 호출한다. 권한 검사는 호출 모듈의 웹 경계 책임.
 */
public interface FldSurveyQuery {

    record RoundSummary(UUID roundId, UUID projectId, UUID buildingId, int roundNo, String surveyType, String status,
                        Instant completedAt, long lastSeq) {}

    /**
     * 보고서 반영 대상 결함(REMOVED 제외). 측정값은 확정(장비 검증 통과 또는 장비 비대상 방법)만 — 잠정 값 · AI 미수락 제안은 없다
     * (BR-FLD-004 · 008). floorCode · memberType · memberCode = FAC 위치 노드(fac::api) 기준, memberType 은 FAC 코드(COL · GIR …).
     * widthEquipmentId · widthMeasuredOn = 균열폭 측정 장비 · 측정일(KST) — RPT 검교정 재판정(BR-RPT-003) 입력.
     */
    record DefectExport(UUID defectId, String code, UUID buildingId, UUID locationNodeId, String floorCode,
                        String memberType, String memberCode, String typeCode, String subtypeCode, String status,
                        BigDecimal widthMm, String widthMethod, UUID widthEquipmentId, LocalDate widthMeasuredOn,
                        BigDecimal lengthM, BigDecimal areaM2, String memo) {}

    /** asOfSeq = 내보낸 시점 이벤트 커서(스냅샷 워터마크) */
    record SurveyExport(RoundSummary round, List<DefectExport> defects, long asOfSeq) {}

    /** 검증(VERIFIED) · 유효(무효 표시 아님) 사진 — defectCode = 결함 번호(보고서 사진대지 번호 대응) */
    record PhotoExport(UUID photoId, UUID defectId, String defectCode, LocalDate takenOn, String sha256) {}

    Optional<RoundSummary> round(UUID roundId);

    /** 프로젝트 · 건물의 가장 최근 회차(완료 우선, 없으면 진행 중) */
    Optional<RoundSummary> latestRound(UUID projectId, UUID buildingId);

    SurveyExport export(UUID roundId);

    List<PhotoExport> verifiedPhotos(UUID roundId);
}
