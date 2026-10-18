package com.atomeng.erp.rpt.domain.grade;

import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 결함물량표 (FR-RPT-005, 06-IMPL Step 5) — 동 × 층 × 부재 × 결함유형별 개소 · 길이(m) · 면적(m²).
 * 합계는 원 값 합산 후 표시 단계에서 1회 반올림(2자리 HALF_UP) — 부분합 반올림 금지(Step 5 팁).
 */
public final class QuantityAggregator {

    public record Row(String buildingCode, String floorCode, String memberType, String defectType, int count,
                      BigDecimal lengthM, BigDecimal areaM2, List<String> defectIds) {}

    private QuantityAggregator() {}

    public static List<Row> aggregate(SnapshotDocument doc) {
        Map<String, List<Defect>> acc = new TreeMap<>();
        for (Defect d : doc.defects()) {
            String key = String.join("\u0000", nz(d.buildingCode()), nz(d.floorCode()), nz(d.memberType()),
                    nz(d.defectType()));
            acc.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }
        List<Row> rows = new ArrayList<>();
        for (List<Defect> ds : acc.values()) {
            Defect f = ds.get(0);
            BigDecimal len = BigDecimal.ZERO;
            BigDecimal area = BigDecimal.ZERO;
            for (Defect d : ds) {
                if (d.lengthM() != null) {
                    len = len.add(d.lengthM());
                }
                if (d.areaM2() != null) {
                    area = area.add(d.areaM2());
                }
            }
            rows.add(new Row(f.buildingCode(), f.floorCode(), f.memberType(), f.defectType(), ds.size(),
                    len.setScale(2, RoundingMode.HALF_UP), area.setScale(2, RoundingMode.HALF_UP),
                    ds.stream().map(Defect::id).sorted().toList()));
        }
        return rows;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
