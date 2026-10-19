package com.atomeng.erp.fac.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 구독 계약: DEM 해체 완료 이벤트 payload (설계 10 v0.3 B6 {@code dem.demolition.completed}).
 * DEM 이 fac::api 에 의존하므로 계약은 FAC 쪽에 두고, DEM 발행 레코드(dem::api DemEvents.DemolitionCompleted)와
 * 필드가 정확히 같아야 한다(apps DemFacContractTest — 알 수 없는 필드 = 실패).
 * FAC 가 쓰는 값: buildingId(멸실 처리 대상) · completedOn · projectId(감사 근거).
 */
public record DemolitionCompletedContract(UUID projectId, UUID workPackageId, UUID buildingId, LocalDate completedOn,
                                          UUID submissionId) {

    public static final String TYPE = "dem.demolition.completed";
}
