package com.atomeng.erp.rpt.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 리비전 공개 조회 (QAR 연동 전제). QAR 은 RPT 테이블을 읽지 않고 이 인터페이스만 호출한다.
 * <ul>
 *   <li>{@link #anchors(UUID)} — 마지막 렌더의 anchors.json: 블록 id · 섹션 키 · 페이지 · rect(mm) · 텍스트 해시
 *       (코멘트 위치 고정, 본문 변경 감지)</li>
 *   <li>{@link #sectionEditors(UUID)} — 섹션별 편집자(자동 생성 실행자 포함) — QAR 자기 검토 금지 판정</li>
 * </ul>
 */
public interface RptRevisionQuery {

    Optional<AnchorSet> anchors(UUID revisionId);

    List<SectionEditors> sectionEditors(UUID revisionId);

    record Rect(int page, BigDecimal x, BigDecimal y, BigDecimal w, BigDecimal h) {}

    record Anchor(UUID blockId, String sectionKey, String kind, int page, List<Rect> rects, String textHash) {}

    /** source = ESTIMATED(흐름 배치 추정) · PDF_TEXT(PDF 글자 좌표, 렌더 워커 단계) */
    record AnchorSet(UUID revisionId, String source, String unit, int pageCount, String sha256, List<Anchor> anchors) {}

    /** sources = AUTO(자동 생성 실행) · AI · HUMAN(직접 편집) */
    record Editor(UUID editorId, List<String> sources, int editCount, Instant firstEditedAt, Instant lastEditedAt) {}

    record SectionEditors(String sectionKey, List<Editor> editors) {}
}
