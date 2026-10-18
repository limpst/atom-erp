package com.atomeng.erp.qar;

import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Defect;
import com.atomeng.erp.rpt.api.RptRevisionQuery.DrawingSheet;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Grade;
import com.atomeng.erp.rpt.api.RptRevisionQuery.MemberGroup;
import com.atomeng.erp.rpt.api.RptRevisionQuery.MemberRating;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 합성 RPT 리비전 컨텍스트(실데이터 없음) — RptFlow 의 compose 결과와 같은 모양:
 * B01 · 1F/2F 기둥 그룹, 층마다 균열 2건(폭 0.25mm · 길이 1.20m), 섹션 5개.
 */
public final class Fixtures {

    public static final LocalDate INSPECTION = LocalDate.of(2026, 9, 30);
    public static final UUID OPINION_BLOCK = UUID.fromString("0192f070-0000-7000-8000-00000000b007");

    private Fixtures() {}

    public static RevisionInfo info() {
        return new RevisionInfo(UUID.fromString("0192f070-0000-7000-8000-0000000000a1"),
                UUID.fromString("0192f070-0000-7000-8000-0000000000a2"), UUID.randomUUID(), UUID.randomUUID(), "RT_PRC", 1,
                "IN_REVIEW", 1, "c".repeat(64), "p".repeat(64), true, INSPECTION);
    }

    public static List<Defect> defects() {
        List<Defect> out = new ArrayList<>();
        int n = 0;
        for (String f : List.of("1F", "2F")) {
            for (int k = 1; k <= 2; k++) {
                n++;
                out.add(new Defect("D-%04d".formatted(n), "B01", f, "COLUMN", f + "-COL-0" + k, "CRACK",
                        new BigDecimal("0.25"), new BigDecimal("1.20"), null, null, null, null, null));
            }
        }
        return out;
    }

    public static Block block(UUID id, String section, int order, String kind, String text, boolean locked,
                              String source, List<String> bound) {
        return new Block(id, section, order, kind, text, locked, source, List.of(), List.of(), bound);
    }

    public static Block quantityTable(List<List<String>> rows) {
        return new Block(UUID.fromString("0192f070-0000-7000-8000-00000000b004"), "ch4.quantities", 1, "TABLE",
                "표", true, "AUTO", List.of("동", "층", "부재", "결함", "개소", "길이(m)", "면적(m²)"), rows, List.of());
    }

    public static List<List<String>> quantityRows() {
        return List.of(List.of("B01", "1F", "COLUMN", "CRACK", "2", "2.40", "0.00"),
                List.of("B01", "2F", "COLUMN", "CRACK", "2", "2.40", "0.00"));
    }

    public static RevisionContent content(String opinion) {
        return content(opinion, defects(), quantityRows(), grade("C", "C", null, List.of()));
    }

    public static Grade grade(String computed, String fin, String reason, List<String> evidence) {
        return new Grade(computed, fin, reason, evidence, new BigDecimal("3.000"), List.of(
                new MemberRating("B01", "1F", "COLUMN", 10, "c", 1, List.of("D-0001", "D-0002")),
                new MemberRating("B01", "2F", "COLUMN", 10, "c", 1, List.of("D-0003", "D-0004"))));
    }

    public static RevisionContent content(String opinion, List<Defect> defects, List<List<String>> qrows, Grade grade) {
        List<Section> sections = List.of(
                new Section("ch1.overview", "1. 개요", 1, "AUTO", List.of(block(
                        UUID.fromString("0192f070-0000-7000-8000-00000000b001"), "ch1.overview", 1, "PARAGRAPH",
                        "합성 사옥 정밀점검 — 점검일 2026-09-30, 조사 동 1개 · 층 2개 · 부재 그룹 2개 · 결함 %d건(스냅샷 v1, SHA-256 abc…)."
                                .formatted(defects.size()), true, "AUTO",
                        List.of("meta.facilityName", "meta.inspectionDate", "defects.length")))),
                new Section("ch4.quantities", "4. 결함물량표", 2, "AUTO", List.of(quantityTable(qrows))),
                new Section("ch5.drawings", "5. 외관조사망도", 3, "AUTO", List.of(
                        block(UUID.randomUUID(), "ch5.drawings", 1, "IMAGE", "B01 1F 외관조사망도 — 결함 2건", true, "AUTO",
                                List.of()),
                        block(UUID.randomUUID(), "ch5.drawings", 2, "IMAGE", "B01 2F 외관조사망도 — 결함 2건", true, "AUTO",
                                List.of()))),
                new Section("ch6.condition", "6. 상태평가 및 안전등급", 4, "AUTO", List.of(
                        new Block(UUID.randomUUID(), "ch6.condition", 1, "TABLE", "표", true, "AUTO", List.of("동"),
                                List.of(), List.of()))),
                new Section("ch7.opinion", "7. 종합 의견", 5, "HUMAN", List.of(block(OPINION_BLOCK, "ch7.opinion", 1,
                        "PARAGRAPH", opinion, false, "HUMAN", List.of()))));
        return new RevisionContent(info(), "합성 사옥", sections, grade,
                List.of(new MemberGroup("B01", "1F", "COLUMN", 10), new MemberGroup("B01", "2F", "COLUMN", 10)), defects,
                List.of(new DrawingSheet("B01", "1F", 9), new DrawingSheet("B01", "2F", 9)));
    }
}
