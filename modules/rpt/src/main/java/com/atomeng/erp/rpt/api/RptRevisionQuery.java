package com.atomeng.erp.rpt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 리비전 공개 조회 (QAR 연동 전제). QAR 은 RPT 테이블을 읽지 않고 이 인터페이스만 호출한다.
 * <ul>
 *   <li>{@link #anchors(UUID)} — 마지막 렌더의 anchors.json: 블록 id · 섹션 키 · 페이지 · rect(mm) · 텍스트 해시
 *       (코멘트 위치 고정, 본문 변경 감지)</li>
 *   <li>{@link #sectionEditors(UUID)} — 섹션별 편집자(자동 생성 실행자 포함) — QAR 자기 검토 금지 판정</li>
 *   <li>{@link #revision(UUID)} · {@link #currentRevision(UUID)} — 리비전 상태 · 본문 해시 · 최신 렌더 PDF 해시
 *       (QAR 라운드 고정 · 편집 잠금 확인 · 정정본 존재 판정, 07 v0.3 B8 계약 추가)</li>
 *   <li>{@link #content(UUID)} — 읽기 전용 검토 컨텍스트: 섹션 · 블록 · 등급 · 부재평가 · 스냅샷 결함(측정일 기준 검교정 재판정 포함)
 *       · 망도 — QAR AUTO-CHECK · AI 검토 · CitationGuard 입력(07 B5 getRevisionContext)</li>
 * </ul>
 */
public interface RptRevisionQuery {

    Optional<AnchorSet> anchors(UUID revisionId);

    List<SectionEditors> sectionEditors(UUID revisionId);

    Optional<RevisionInfo> revision(UUID revisionId);

    /** 보고서의 현재(최신) 리비전 — 정정본(Rev.N) 생성 여부 판정 */
    Optional<RevisionInfo> currentRevision(UUID reportId);

    Optional<RevisionContent> content(UUID revisionId);

    /** 작성 · 등급 결정 이력자 — QAR 최종 승인자 3자 분리(보안 검토 2026-10-19 QAR-01) */
    Optional<Authorship> authorship(UUID revisionId);

    /** reportCreatedBy = 보고서 생성자, revisionCreatedBy = 리비전 생성자(정정본 작성자), gradeDecidedBy = 등급 확정자(없으면 null) */
    record Authorship(UUID reportCreatedBy, UUID revisionCreatedBy, UUID gradeDecidedBy) {}

    record Rect(int page, BigDecimal x, BigDecimal y, BigDecimal w, BigDecimal h) {}

    record Anchor(UUID blockId, String sectionKey, String kind, int page, List<Rect> rects, String textHash) {}

    /** source = ESTIMATED(흐름 배치 추정) · PDF_TEXT(PDF 글자 좌표, 렌더 워커 단계) */
    record AnchorSet(UUID revisionId, String source, String unit, int pageCount, String sha256, List<Anchor> anchors) {}

    /** sources = AUTO(자동 생성 실행) · AI · HUMAN(직접 편집) */
    record Editor(UUID editorId, List<String> sources, int editCount, Instant firstEditedAt, Instant lastEditedAt) {}

    record SectionEditors(String sectionKey, List<Editor> editors) {}

    /**
     * 리비전 요약. status = RPT A6 상태(IN_REVIEW = 검토 중 편집 잠금). renderedPdfSha256 = 최신 렌더(DONE) PDF 해시,
     * renderFresh = 그 렌더가 현재 본문 해시와 같은지.
     */
    record RevisionInfo(UUID revisionId, UUID reportId, UUID projectId, UUID facilityId, String reportType, int revNo,
                        String status, Integer snapshotVer, String contentHash, String renderedPdfSha256,
                        boolean renderFresh, LocalDate inspectionDate) {}

    /** 블록: 표는 columns/rows, 바인딩 문장은 bound(스냅샷 경로) */
    record Block(UUID blockId, String sectionKey, int orderNo, String kind, String text, boolean locked, String source,
                 List<String> columns, List<List<String>> rows, List<String> bound) {}

    record Section(String sectionKey, String title, int orderNo, String source, List<Block> blocks) {}

    record MemberRating(String buildingCode, String floorCode, String memberType, int memberCount, String rating,
                        int evidenceCount, List<String> evidenceDefectIds) {}

    record Grade(String computedGrade, String finalGrade, String overrideReason, List<String> overrideEvidence,
                 BigDecimal weightedScore, List<MemberRating> members) {}

    record MemberGroup(String buildingCode, String floorCode, String memberType, int memberCount) {}

    /** 스냅샷 결함 + 측정일 기준 검교정 재판정(eqp::api, 조회 시점 이력 반영) — 장비 없는 결함은 calibrationValid null */
    record Defect(String id, String buildingCode, String floorCode, String memberType, String memberId,
                  String defectType, BigDecimal widthMm, BigDecimal lengthM, BigDecimal areaM2, UUID equipmentId,
                  LocalDate measuredOn, Boolean calibrationValid, String calibrationReason) {}

    record DrawingSheet(String buildingCode, String floorCode, int entityCount) {}

    record RevisionContent(RevisionInfo revision, String facilityName, List<Section> sections, Grade grade,
                           List<MemberGroup> memberGroups, List<Defect> defects, List<DrawingSheet> drawings) {}
}
