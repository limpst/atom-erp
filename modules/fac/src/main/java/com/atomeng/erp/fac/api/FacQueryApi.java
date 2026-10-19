package com.atomeng.erp.fac.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FAC 공개 조회 (09 PTL 고객 포털 · 09-IMPL 0.5 "FacQueryApi.facilitiesOfClient()"). 다른 모듈은 fac 테이블을 읽지 않고
 * 이 인터페이스만 호출한다. 소유 고객사 = {@code fac.site.owner_client_id}. 권한 검사는 호출 모듈의 웹 경계 책임.
 * 차기 점검일은 FAC 점검주기 엔진이 계산한 값만 돌려준다(PTL 은 재계산하지 않음 — ⚠️ 법령 최신본 확인 필요).
 */
public interface FacQueryApi {

    /**
     * 시설물 요약. currentGrade = 최신 등급 이력(수행일 · 기록 시각 역순 첫 행), 없으면 null.
     * status = FAC A6 상태(MERGED 이면 mergedIntoId 가 정본).
     */
    record BuildingSummary(UUID buildingId, UUID ownerClientId, String name, String dongName, String roadAddress,
                           String structureNm, BigDecimal grossFloorArea, Integer floorsAbove, Integer floorsBelow,
                           LocalDate approvalDate, String status, UUID mergedIntoId, String currentGrade,
                           LocalDate gradePerformedOn) {}

    record GradeEntry(String grade, String inspectionType, LocalDate performedOn, UUID reportId, boolean correction) {}

    /** 진행 중 점검 도래(DONE · LOST · CANCELLED 제외) */
    record InspectionDue(UUID buildingId, String inspectionType, LocalDate dueDate, String status) {}

    /**
     * 위치 계층 노드(05 FLD 결함 위치 참조 · 06 RPT 부재 그룹). floorCode = 가장 가까운 FLOOR 조상(자기 자신 포함) 코드,
     * memberType = FAC 부재 유형(COL · GIR · BEAM · SLAB · WALL · FDN · STAIR · ETC, 노드가 MEMBER 일 때), deleted = 논리 삭제됨,
     * replacedById = 삭제 시 지정된 대체 노드.
     */
    record LocationNode(UUID nodeId, UUID buildingId, UUID parentId, String nodeType, String memberType, String code,
                        String name, Integer floorLevel, String floorCode, boolean deleted, UUID replacedById) {}

    /** 위치 노드(삭제된 노드도 deleted=true 로 돌려준다 — 호출자가 거부 판단) */
    Optional<LocationNode> locationNode(UUID nodeId);

    /** 고객사가 소유한 시설물(병합 · 삭제 제외), 이름순 */
    List<BuildingSummary> buildingsOwnedBy(UUID clientId);

    Optional<BuildingSummary> building(UUID buildingId);

    /** 등급 이력 — 수행일 역순 */
    List<GradeEntry> gradeHistory(UUID buildingId);

    /** 진행 중 점검 도래 — 도래일 순 */
    List<InspectionDue> openDues(UUID buildingId);
}
