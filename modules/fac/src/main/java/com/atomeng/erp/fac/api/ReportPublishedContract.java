package com.atomeng.erp.fac.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 구독 계약(스텁): RPT 보고서 발행 이벤트 payload. RPT 모듈이 아직 없어 FAC 쪽에 계약을 고정한다(02-IMPL Step 9 팁).
 * 설계 02 v0.3 B6 정본명은 {@code rpt.report.published}. 조정 요청의 {@code rpt.revision.published} 도 같은 계약으로 수신한다.
 */
public record ReportPublishedContract(UUID reportId, List<UUID> buildingIds, String grade, String inspectionType,
                                      LocalDate performedOn, UUID approvedBy) {

    public static final String TYPE = "rpt.report.published";
    public static final String TYPE_REVISION = "rpt.revision.published";
}
