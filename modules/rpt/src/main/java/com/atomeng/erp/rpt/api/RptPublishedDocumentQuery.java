package com.atomeng.erp.rpt.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 발행본(PUBLISHED · DELIVERED · SUPERSEDED 리비전) 공개 조회 — 09 PTL 고객 포털이 납품 보고서 목록 · 다운로드 원본을 얻는 경로
 * (09-IMPL 0.5 "RptQueryApi.finalPdf()"). 초안 · 검토 중 리비전은 절대 돌려주지 않는다(BR-PTL-002).
 * 권한 검사(고객사 범위)는 호출 모듈 책임.
 */
public interface RptPublishedDocumentQuery {

    /**
     * @param status      PUBLISHED · DELIVERED · SUPERSEDED
     * @param finalGrade  책임기술자 확정 등급(등급 없는 유형이면 null)
     * @param supersededBy 정정본 리비전 id(SUPERSEDED 일 때)
     */
    record PublishedDocument(UUID revisionId, UUID reportId, int revNo, UUID projectId, UUID facilityId,
                             String reportType, String title, LocalDate inspectionDate, String status, String finalGrade,
                             String pdfSha256, Integer pageCount, Instant publishedAt, Instant deliveredAt,
                             UUID supersededBy, String changeNote) {}

    Optional<PublishedDocument> publishedDocument(UUID revisionId);

    /** WORM 발행본 PDF 원문(서명 · 워터마크 전). 발행본이 아니면 IllegalArgumentException */
    byte[] publishedPdf(UUID revisionId);
}
