package com.atomeng.erp.rpt.domain.hwpx;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 바인딩 컨텍스트 (06-IMPL Step 8 ManifestBinder) — 단순 placeholder 값 · 반복 표 행 · 섹션 블록 · 그림.
 * 모든 수치는 스냅샷 · 계산 결과에서 서버가 만든 표시 문자열이다(BR-RPT-001).
 */
public record RenderModel(Map<String, String> values, Map<String, List<Map<String, String>>> repeats,
                          List<Section> sections, Map<String, Image> images) {

    public record Section(String key, String title, List<Block> blocks) {}

    /**
     * 블록. kind: HEADING · PARAGRAPH · TABLE(columns · rows) · IMAGE(imageKey · text = 캡션).
     */
    public record Block(UUID blockId, String kind, String text, List<String> columns, List<List<String>> rows,
                        String imageKey) {}

    /** PNG 바이트 + 크기(px) — HWPX 에는 mm 기준 HWPUNIT 로 배치 */
    public record Image(String key, byte[] png, int widthPx, int heightPx) {}
}
