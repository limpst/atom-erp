package com.atomeng.erp.fac.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 구독 계약: RPT 보고서 발행 이벤트 payload (설계 02 v0.3 B6 · 06 v0.2 B6 정본).
 * 이벤트명: {@code rpt.report.published}. RPT 가 fac::api 에 의존하므로 계약은 FAC 쪽에 두고, RPT 발행 레코드
 * (rpt::api RptEvents.ReportPublished)와 필드가 정확히 같아야 한다(apps RptFacContractTest — 알 수 없는 필드 = 실패).
 * FAC 가 쓰는 값: buildingIds · grade · inspectionType · performedOn · approvedBy · reportId.
 * grade 또는 inspectionType 이 null(등급 없는 보고서 유형)이면 등급 이력을 남기지 않는다.
 */
public record ReportPublishedContract(UUID reportId, UUID revisionId, int revNo, UUID projectId, String reportType,
                                      List<UUID> buildingIds, String grade, String inspectionType,
                                      LocalDate performedOn, UUID approvedBy, String pdfSha256) {

    public static final String TYPE = "rpt.report.published";
}
