package com.atomeng.erp.cst.domain.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * HRQ 월 마감 인·일 ↔ CST 원장 대사 (BR-HRQ-013, FR-HRQ-014, TC-HRQ-010) - 순수 도메인.
 * HRQ 와 같은 단위로 비교한다: (프로젝트, 기술자, 등급, 업무유형) 그룹마다 round(sum(hours) / 8, 2).
 * 결과는 기록·경보에만 쓰며 원장을 고치지 않는다(이중 집계 방지).
 */
public final class MandayReconciler {

    private static final BigDecimal EIGHT = new BigDecimal("8");

    private MandayReconciler() {}

    public record Key(UUID projectId, UUID employeeId, String grade, String workType) implements Comparable<Key> {
        public Key {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(employeeId, "employeeId");
            grade = grade == null ? "UNGRADED" : grade;
            workType = workType == null ? "" : workType;
        }

        @Override
        public int compareTo(Key o) {
            int c = projectId.compareTo(o.projectId);
            if (c == 0) {
                c = employeeId.compareTo(o.employeeId);
            }
            if (c == 0) {
                c = grade.compareTo(o.grade);
            }
            return c == 0 ? workType.compareTo(o.workType) : c;
        }
    }

    public record HrqRow(Key key, BigDecimal mandays) {}

    /** CST 그룹 합계 시간(부호 포함: TIMESHEET +, 같은 월 원본의 REVERSAL -) */
    public record CstRow(Key key, BigDecimal hours) {}

    public record Diff(Key key, BigDecimal hrqMandays, BigDecimal cstMandays, BigDecimal diff) {}

    public record Result(boolean matched, BigDecimal hrqTotal, BigDecimal cstTotal, BigDecimal diffTotal,
                         List<Diff> diffs, int groups) {}

    public static Result reconcile(List<HrqRow> hrq, List<CstRow> cst) {
        Map<Key, BigDecimal> h = new TreeMap<>();
        for (HrqRow r : hrq) {
            h.merge(r.key(), r.mandays().setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
        }
        Map<Key, BigDecimal> hours = new TreeMap<>();
        for (CstRow r : cst) {
            hours.merge(r.key(), r.hours(), BigDecimal::add);
        }
        Map<Key, BigDecimal> c = new TreeMap<>();
        hours.forEach((k, v) -> c.put(k, v.divide(EIGHT, 2, RoundingMode.HALF_UP)));

        TreeMap<Key, Boolean> keys = new TreeMap<>();
        h.keySet().forEach(k -> keys.put(k, true));
        c.keySet().forEach(k -> keys.put(k, true));
        List<Diff> diffs = new ArrayList<>();
        BigDecimal ht = BigDecimal.ZERO.setScale(2);
        BigDecimal ct = BigDecimal.ZERO.setScale(2);
        for (Key k : keys.keySet()) {
            BigDecimal hv = h.getOrDefault(k, BigDecimal.ZERO.setScale(2));
            BigDecimal cv = c.getOrDefault(k, BigDecimal.ZERO.setScale(2));
            ht = ht.add(hv);
            ct = ct.add(cv);
            if (hv.compareTo(cv) != 0) {
                diffs.add(new Diff(k, hv, cv, cv.subtract(hv)));
            }
        }
        return new Result(diffs.isEmpty(), ht, ct, ct.subtract(ht), List.copyOf(diffs), keys.size());
    }
}
