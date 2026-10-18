package com.atomeng.erp.qar.domain.autocheck.rules;

import com.atomeng.erp.qar.domain.autocheck.AutoCheckRule;
import com.atomeng.erp.qar.domain.autocheck.Finding;
import com.atomeng.erp.qar.domain.autocheck.NumberExtractor;
import com.atomeng.erp.qar.domain.autocheck.NumberExtractor.Mention;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.Photo;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.QuantityRow;
import com.atomeng.erp.qar.domain.autocheck.RuleParams;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Defect;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Grade;
import com.atomeng.erp.rpt.api.RptRevisionQuery.MemberGroup;
import com.atomeng.erp.rpt.api.RptRevisionQuery.MemberRating;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AUTO-CHECK 규칙 15종 (설계 A4.1 · 07-IMPL Step 4 · 5). AC-HRQ-002(참여자 = 현장 출입 로그)는 FLD 출입 모듈이 없어 제외.
 * 원천이 다른 모듈(HRQ 자격 · FLD 사진 · FAC 이력 · 재료시험)인 규칙은 {@link ReviewContext.ExternalEvidence} 가 null 이면
 * INFO(원천 미연동 — 수동 확인)로 남기고, 값이 오면 설계 판정을 그대로 수행한다.
 */
public final class AutoCheckRules {

    private AutoCheckRules() {}

    public static List<AutoCheckRule> all() {
        return List.of(new AcNum001(), new AcNum002(), new AcGrd001(), new AcGrd002(), new AcGrd003(), new AcPho001(),
                new AcPho002(), new AcEqp001(), new AcHrq001(), new AcSec001(), new AcSec002(), new AcTxt001(),
                new AcTxt002(), new AcDrw001(), new AcDate001());
    }

    /** 사람이 쓰는 서술 블록(잠금 아님 또는 AI) — 스냅샷 파생 잠금 블록은 바인딩 검사만 */
    static boolean narrative(Block b) {
        return ("PARAGRAPH".equals(b.kind()) || "HEADING".equals(b.kind())) && (!b.locked() || "AI".equals(b.source()));
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ AC-NUM-001

    /** 본문 수치 vs 결함물량표 · 스냅샷 (TC-QAR-003, B7.3) */
    static final class AcNum001 implements AutoCheckRule {
        private static final Pattern BOUND_COUNT = Pattern.compile("결함\\s*(\\d+)\\s*건");

        @Override
        public String id() {
            return "AC-NUM-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            List<QuantityRow> rows = ctx.quantityRows();
            int totalDefects = ctx.defects().size();
            for (Block b : ctx.blocks()) {
                // 1) 바인딩 문장: 스냅샷 경로 직접 대조(정확 일치 — 수동 변조 의심)
                if (b.bound().contains("defects.length")) {
                    Matcher m = BOUND_COUNT.matcher(b.text());
                    if (m.find() && Integer.parseInt(m.group(1)) != totalDefects) {
                        out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), Map.of("kind", "snapshot",
                                "path", "defects.length"), String.valueOf(totalDefects), m.group(1),
                                "바인딩 값이 스냅샷과 다릅니다(수동 변조 의심)"));
                    }
                }
                if (!narrative(b)) {
                    continue;
                }
                // 2) 자유 텍스트 수치: 문맥(층 · 부재 · 결함유형) → 물량표 셀 → 비교
                for (Mention m : NumberExtractor.extract(b.text())) {
                    switch (m.unit()) {
                        case COUNT -> checkCount(ctx, p, b, m, rows, totalDefects, out);
                        case M -> checkLength(ctx, p, b, m, rows, out);
                        case MM -> checkWidth(ctx, p, b, m, out);
                        default -> { }
                    }
                }
            }
            return out;
        }

        private void checkCount(ReviewContext ctx, RuleParams p, Block b, Mention m, List<QuantityRow> rows, int total,
                                List<Finding> out) {
            BigDecimal tol = p.decimal("countTolerance", BigDecimal.ZERO);
            if (m.total() && (m.defectType() == null || m.raw().endsWith("건"))) {
                if (!m.matches(BigDecimal.valueOf(total), tol)) {
                    out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), Map.of("kind", "snapshot",
                            "path", "defects.length"), total + "건", m.raw(), "본문 결함 총수가 스냅샷과 다릅니다"));
                }
            } else if (m.hasLocation()) {
                List<QuantityRow> cells = rows.stream().filter(r -> r.floorCode().equals(m.floor())
                        && r.memberType().equals(m.member()) && r.defectType().equals(m.defectType())).toList();
                if (cells.isEmpty()) {
                    out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), Map.of("kind", "quantityTable",
                                    "key", "*/" + m.floor() + "/" + m.member() + "/" + m.defectType()), "0개소", m.raw(),
                            "본문 수치에 해당하는 결함물량표 행이 없습니다(%s %s %s)".formatted(m.floor(), m.member(),
                                    m.defectType())));
                    return;
                }
                BigDecimal expected = cells.stream().map(r -> ReviewContext.num(r.count())).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!m.matches(expected, tol)) {
                    out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), ctx.quantityCellRef(cells.get(0), "count"),
                            plain(expected) + "개소", m.raw(),
                            "본문 수치와 결함물량표가 다릅니다 (%s)".formatted(cells.get(0).key())));
                }
            } else {
                out.add(Finding.info(ctx.textAnchor(b, m.start(), m.end()), "근거 미확인 수치: " + m.raw()));
            }
        }

        private void checkLength(ReviewContext ctx, RuleParams p, Block b, Mention m, List<QuantityRow> rows,
                                 List<Finding> out) {
            if (!m.hasLocation()) {
                out.add(Finding.info(ctx.textAnchor(b, m.start(), m.end()), "근거 미확인 수치: " + m.raw()));
                return;
            }
            List<QuantityRow> cells = rows.stream().filter(r -> r.floorCode().equals(m.floor())
                    && r.memberType().equals(m.member()) && r.defectType().equals(m.defectType())).toList();
            BigDecimal expected = cells.stream().map(r -> ReviewContext.num(r.lengthM())).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (cells.isEmpty() || !m.matches(expected, p.decimal("lengthToleranceM", new BigDecimal("0.01")))) {
                out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), cells.isEmpty() ? Map.of("kind", "quantityTable")
                                : ctx.quantityCellRef(cells.get(0), "lengthM"), plain(expected) + "m", m.raw(),
                        "본문 길이와 결함물량표가 다릅니다"));
            }
        }

        private void checkWidth(ReviewContext ctx, RuleParams p, Block b, Mention m, List<Finding> out) {
            if (m.floor() == null || m.member() == null) {
                out.add(Finding.info(ctx.textAnchor(b, m.start(), m.end()), "근거 미확인 수치: " + m.raw()));
                return;
            }
            BigDecimal tol = p.decimal("widthToleranceMm", BigDecimal.ZERO);
            List<Defect> cands = ctx.defects().stream().filter(d -> m.floor().equals(d.floorCode())
                    && m.member().equals(d.memberType()) && d.widthMm() != null).toList();
            boolean any = cands.stream().anyMatch(d -> m.matches(d.widthMm(), tol));
            if (!any) {
                out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), Map.of("kind", "snapshot",
                                "path", "defects[floor=%s,member=%s].widthMm".formatted(m.floor(), m.member())),
                        cands.stream().map(d -> plain(d.widthMm()) + "mm").distinct().toList().toString(), m.raw(),
                        "본문 균열폭이 스냅샷 측정값에 없습니다"));
            }
        }
    }

    // ------------------------------------------------------------------ AC-NUM-002

    /** 결함물량표 = 스냅샷 결함 재집계(원 값 합산 후 반올림), 단위 열 일관성 */
    static final class AcNum002 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-NUM-002";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            var table = ctx.quantityTable();
            if (table.isEmpty()) {
                out.add(Finding.of(ctx.sectionAnchor(ReviewContext.QUANTITY_SECTION), null, "결함물량표", "없음",
                        "결함물량표가 없습니다"));
                return out;
            }
            Block t = table.get();
            List<String> cols = t.columns();
            if (cols.size() < 7 || !cols.get(5).contains("(m)") || !(cols.get(6).contains("(m²)") || cols.get(6).contains("(㎡)"))) {
                out.add(Finding.of(ctx.blockAnchor(t), null, "길이(m) · 면적(m²)", String.join(",", cols),
                        "물량표 단위 열이 일관되지 않습니다"));
            }
            int scale = p.integer("scale", 2);
            Map<String, BigDecimal[]> expected = new TreeMap<>();
            for (Defect d : ctx.defects()) {
                String key = d.buildingCode() + "/" + d.floorCode() + "/" + d.memberType() + "/" + d.defectType();
                BigDecimal[] acc = expected.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO});
                acc[0] = acc[0].add(BigDecimal.ONE);
                acc[1] = acc[1].add(d.lengthM() == null ? BigDecimal.ZERO : d.lengthM());
                acc[2] = acc[2].add(d.areaM2() == null ? BigDecimal.ZERO : d.areaM2());
            }
            Set<String> seen = new HashSet<>();
            for (QuantityRow r : ctx.quantityRows()) {
                seen.add(r.key());
                BigDecimal[] e = expected.get(r.key());
                if (e == null) {
                    out.add(Finding.of(ctx.blockAnchor(t), ctx.quantityCellRef(r, "row"), "행 없음", r.key(),
                            "스냅샷에 없는 물량표 행입니다"));
                    continue;
                }
                compare(ctx, out, t, r, "count", e[0].setScale(0, RoundingMode.HALF_UP), r.count());
                compare(ctx, out, t, r, "lengthM", e[1].setScale(scale, RoundingMode.HALF_UP), r.lengthM());
                compare(ctx, out, t, r, "areaM2", e[2].setScale(scale, RoundingMode.HALF_UP), r.areaM2());
            }
            for (String k : expected.keySet()) {
                if (!seen.contains(k)) {
                    out.add(Finding.of(ctx.blockAnchor(t), Map.of("kind", "quantityTable", "key", k), k, "누락",
                            "스냅샷 결함이 물량표에 빠졌습니다"));
                }
            }
            return out;
        }

        private static void compare(ReviewContext ctx, List<Finding> out, Block t, QuantityRow r, String field,
                                    BigDecimal expected, String actual) {
            BigDecimal a = ReviewContext.num(actual);
            if (a == null || a.compareTo(expected) != 0) {
                out.add(Finding.of(ctx.blockAnchor(t), ctx.quantityCellRef(r, field), plain(expected), actual,
                        "물량표 %s 재계산 값이 다릅니다 (%s)".formatted(field, r.key())));
            }
        }
    }

    // ------------------------------------------------------------------ AC-GRD-001 · 002 · 003

    static final class AcGrd001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-GRD-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            Grade g = ctx.content().grade();
            Map<String, Object> anchor = ctx.sectionAnchor("ch6.condition");
            if (g == null) {
                out.add(Finding.of(anchor, null, "상태평가 결과", "없음", "등급 산정(compose) 전입니다"));
                return out;
            }
            Map<String, MemberRating> byKey = new HashMap<>();
            g.members().forEach(m -> byKey.put(m.buildingCode() + "/" + m.floorCode() + "/" + m.memberType(), m));
            for (MemberGroup grp : ctx.content().memberGroups()) {
                String key = grp.buildingCode() + "/" + grp.floorCode() + "/" + grp.memberType();
                if (!byKey.containsKey(key)) {
                    out.add(Finding.of(anchor, Map.of("kind", "memberGroup", "key", key), "상태평가(조사 완료)", "없음",
                            "부재 그룹 상태평가(결함 0건이면 조사 완료 표시)가 없습니다: " + key));
                }
            }
            for (MemberRating m : g.members()) {
                if (!"a".equals(m.rating()) && (m.evidenceCount() == 0 || m.evidenceDefectIds().isEmpty())) {
                    out.add(Finding.of(anchor, Map.of("kind", "memberRating", "key", m.buildingCode() + "/" + m.floorCode()
                            + "/" + m.memberType()), "판정 근거(결함)", "없음", "부재 등급 " + m.rating() + " 의 판정 근거가 없습니다"));
                }
            }
            return out;
        }
    }

    static final class AcGrd002 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-GRD-002";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            Grade g = ctx.content().grade();
            Map<String, Object> anchor = ctx.sectionAnchor("ch6.condition");
            if (g == null || g.finalGrade() == null) {
                out.add(Finding.of(anchor, Map.of("kind", "grade", "field", "finalGrade"), "책임기술자 확정 등급", "미확정",
                        "최종 등급이 확정되지 않았습니다"));
                return out;
            }
            if (!g.finalGrade().equals(g.computedGrade())) {
                int min = p.integer("minReasonChars", 100);
                String reason = g.overrideReason() == null ? "" : g.overrideReason().strip();
                if (reason.codePointCount(0, reason.length()) < min || g.overrideEvidence().isEmpty()) {
                    out.add(Finding.of(anchor, Map.of("kind", "grade", "field", "overrideReason"),
                            "사유 " + min + "자 이상 + 근거 결함 1건 이상", reason.length() + "자 · 근거 " + g.overrideEvidence().size(),
                            "산정(%s)과 다른 최종 등급(%s)의 override 근거가 부족합니다".formatted(g.computedGrade(), g.finalGrade())));
                }
                Set<String> known = new HashSet<>();
                ctx.defects().forEach(d -> known.add(d.id()));
                for (String e : g.overrideEvidence()) {
                    if (!known.contains(e)) {
                        out.add(Finding.of(anchor, ReviewContext.defectRef(e), "스냅샷 결함", e,
                                "override 근거 결함이 스냅샷에 없습니다: " + e));
                    }
                }
            }
            return out;
        }
    }

    static final class AcGrd003 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-GRD-003";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            ReviewContext.GradeHistory h = ctx.evidence().gradeHistory();
            if (h == null) {
                return List.of(Finding.unconnected("FAC 등급 이력"));
            }
            Grade g = ctx.content().grade();
            if (g == null || g.finalGrade() == null || h.previousGrade() == null) {
                return List.of();
            }
            if (g.finalGrade().compareTo(h.previousGrade()) < 0 && h.repairsSince() == 0) {
                return List.of(Finding.of(ctx.sectionAnchor("ch6.condition"), Map.of("kind", "fac", "field", "gradeHistory"),
                        "보수보강 이력 1건 이상", "0건",
                        "직전 회차(%s, %s) 대비 등급이 %s 로 개선되었으나 보수보강 이력이 없습니다".formatted(h.previousGrade(),
                                h.previousOn(), g.finalGrade())));
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------ AC-PHO-001 · 002

    static final class AcPho001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-PHO-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Photo> photos = ctx.evidence().photos();
            if (photos == null) {
                return List.of(Finding.unconnected("FLD 사진대지"));
            }
            List<Finding> out = new ArrayList<>();
            Set<String> required = new HashSet<>(p.strings("requiredDefectTypes"));
            Set<String> withPhoto = new HashSet<>();
            photos.forEach(ph -> withPhoto.add(ph.defectId()));
            Set<String> defectIds = new HashSet<>();
            for (Defect d : ctx.defects()) {
                defectIds.add(d.id());
                if ((required.isEmpty() || required.contains(d.defectType())) && !withPhoto.contains(d.id())) {
                    out.add(Finding.of(null, ReviewContext.defectRef(d.id()), "사진 1장 이상", "0장", "사진 없는 결함: " + d.id()));
                }
            }
            for (Photo ph : photos) {
                if (!defectIds.contains(ph.defectId())) {
                    out.add(Finding.of(null, Map.of("kind", "photo", "photoNo", String.valueOf(ph.photoNo())), "결함 번호",
                            String.valueOf(ph.defectId()), "사진대지 번호가 결함 번호와 맞지 않습니다: " + ph.photoNo()));
                }
            }
            return out;
        }
    }

    static final class AcPho002 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-PHO-002";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Photo> photos = ctx.evidence().photos();
            if (photos == null) {
                return List.of(Finding.unconnected("FLD 사진 EXIF"));
            }
            List<Finding> out = new ArrayList<>();
            LocalDate insp = ctx.content().revision().inspectionDate();
            LocalDate from = insp.minusDays(p.integer("maxSurveyDays", 90));
            Map<String, Set<String>> bySha = new LinkedHashMap<>();
            for (Photo ph : photos) {
                if (ph.takenOn() != null && (ph.takenOn().isAfter(insp) || ph.takenOn().isBefore(from))) {
                    out.add(Finding.of(null, Map.of("kind", "photo", "photoNo", String.valueOf(ph.photoNo())),
                            from + " ~ " + insp, String.valueOf(ph.takenOn()), "촬영일이 현장조사 기간 밖입니다(재사용 의심)"));
                }
                if (ph.sha256() != null) {
                    bySha.computeIfAbsent(ph.sha256(), k -> new java.util.TreeSet<>()).add(ph.defectId());
                }
            }
            bySha.forEach((sha, ids) -> {
                if (ids.size() > 1) {
                    out.add(Finding.of(null, Map.of("kind", "photo", "sha256", sha), "결함별 고유 사진", ids.toString(),
                            "같은 사진(해시)이 서로 다른 결함에 쓰였습니다"));
                }
            });
            return out;
        }
    }

    // ------------------------------------------------------------------ AC-EQP-001 · AC-HRQ-001

    /** 측정 장비 검교정 — RPT 가 eqp::api 로 측정일 기준 재판정한 값(조회 시점 이력 반영, TC-QAR-004) */
    static final class AcEqp001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-EQP-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            for (Defect d : ctx.defects()) {
                if (Boolean.FALSE.equals(d.calibrationValid())) {
                    Map<String, Object> src = new LinkedHashMap<>();
                    src.put("kind", "measurement");
                    src.put("defectId", d.id());
                    src.put("module", "eqp");
                    src.put("equipmentId", d.equipmentId());
                    out.add(Finding.of(ctx.sectionAnchor(ReviewContext.QUANTITY_SECTION), src, "측정일(" + d.measuredOn()
                                    + ") 유효 검교정", "무효(" + d.calibrationReason() + ")",
                            "검교정 무효 장비로 측정된 값입니다: " + d.id()));
                }
            }
            return out;
        }
    }

    static final class AcHrq001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-HRQ-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            var q = ctx.evidence().qualifications();
            if (q == null) {
                return List.of(Finding.unconnected("HRQ 자격"));
            }
            return q.stream().filter(x -> !x.valid()).map(x -> Finding.of(ctx.sectionAnchor("ch1.overview"),
                    Map.of("kind", "employee", "module", "hrq", "id", String.valueOf(x.employeeId())),
                    x.role() + " 자격 유효", String.valueOf(x.reason()),
                    "참여 · 책임기술자 자격 요건 미충족(" + ctx.asOf() + " 기준)")).toList();
        }
    }

    // ------------------------------------------------------------------ AC-SEC-001 · 002

    static final class AcSec001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-SEC-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            for (String key : p.strings("requiredSections")) {
                var s = ctx.section(key);
                if (s.isEmpty() || s.get().blocks().stream().allMatch(b -> b.text() == null || b.text().isBlank())) {
                    out.add(Finding.of(ctx.sectionAnchor(key), Map.of("kind", "manifest", "section", key), "필수 섹션",
                            s.isEmpty() ? "없음" : "비어 있음", "필수 목차가 없거나 비어 있습니다: " + key));
                }
            }
            for (String key : p.strings("requiredTables")) {
                boolean has = ctx.section(key).map(Section::blocks).orElse(List.of()).stream()
                        .anyMatch(b -> "TABLE".equals(b.kind()));
                if (!has) {
                    out.add(Finding.of(ctx.sectionAnchor(key), Map.of("kind", "manifest", "table", key), "필수 표", "없음",
                            "필수 표가 없습니다: " + key));
                }
            }
            return out;
        }
    }

    static final class AcSec002 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-SEC-002";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            Map<String, Integer> tests = ctx.evidence().materialTests();
            if (tests == null) {
                return List.of(Finding.unconnected("FLD 재료시험"));
            }
            List<Finding> out = new ArrayList<>();
            JsonNode min = p.get("minTests");
            min.fieldNames().forEachRemaining(kind -> {
                int need = min.path(kind).asInt();
                int have = tests.getOrDefault(kind, 0);
                if (have < need) {
                    out.add(Finding.of(null, Map.of("kind", "materialTest", "test", kind), need + "회 이상", have + "회",
                            "재료시험 최소 수량 미달(⚠️ 예시 기준): " + kind));
                }
            });
            return out;
        }
    }

    // ------------------------------------------------------------------ AC-TXT-001 · 002

    static final class AcTxt001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-TXT-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            return ctx.blocks().stream().filter(b -> "AI".equals(b.source())).map(b -> Finding.of(ctx.blockAnchor(b),
                    null, "작성자 수락(HUMAN)", "AI 초안", "AI 초안 문장이 수락되지 않았습니다")).toList();
        }
    }

    static final class AcTxt002 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-TXT-002";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            List<Pattern> patterns = new ArrayList<>();
            List<String> suggests = new ArrayList<>();
            p.get("phrases").forEach(n -> {
                patterns.add(Pattern.compile(n.path("pattern").asText()));
                suggests.add(n.path("suggest").asText());
            });
            for (Block b : ctx.blocks()) {
                if ("TABLE".equals(b.kind()) || "IMAGE".equals(b.kind())) {
                    continue;
                }
                for (int i = 0; i < patterns.size(); i++) {
                    Matcher m = patterns.get(i).matcher(b.text());
                    while (m.find()) {
                        out.add(Finding.of(ctx.textAnchor(b, m.start(), m.end()), Map.of("kind", "phraseDictionary"),
                                suggests.get(i), m.group(), "단정적 안전 보장 표현입니다 — 대체 표현 권고"));
                    }
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ AC-DRW-001 · AC-DATE-001

    static final class AcDrw001 implements AutoCheckRule {
        private static final Pattern CAPTION = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+외관조사망도.*?결함\\s*(\\d+)\\s*건");

        @Override
        public String id() {
            return "AC-DRW-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            Map<String, Integer> perFloor = new TreeMap<>();
            ctx.defects().forEach(d -> perFloor.merge(d.buildingCode() + " " + d.floorCode(), 1, Integer::sum));
            Set<String> sheets = new HashSet<>();
            ctx.content().drawings().forEach(s -> sheets.add(s.buildingCode() + " " + s.floorCode()));
            perFloor.forEach((floor, n) -> {
                if (!sheets.contains(floor)) {
                    out.add(Finding.of(ctx.sectionAnchor("ch5.drawings"), Map.of("kind", "drawing", "floor", floor),
                            "망도 1장", "없음", "결함이 있는 층의 외관조사망도가 없습니다: " + floor));
                }
            });
            ctx.section("ch5.drawings").ifPresent(s -> s.blocks().stream().filter(b -> "IMAGE".equals(b.kind()))
                    .forEach(b -> {
                        Matcher m = CAPTION.matcher(b.text());
                        if (m.find()) {
                            String floor = m.group(1) + " " + m.group(2);
                            int n = perFloor.getOrDefault(floor, 0);
                            if (Integer.parseInt(m.group(3)) != n) {
                                out.add(Finding.of(ctx.blockAnchor(b), Map.of("kind", "drawing", "floor", floor),
                                        n + "건", m.group(3) + "건", "망도 핀(결함) 수가 결함 수와 다릅니다: " + floor));
                            }
                        }
                    }));
            return out;
        }
    }

    static final class AcDate001 implements AutoCheckRule {
        @Override
        public String id() {
            return "AC-DATE-001";
        }

        @Override
        public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
            List<Finding> out = new ArrayList<>();
            LocalDate insp = ctx.content().revision().inspectionDate();
            if (insp.isAfter(ctx.asOf())) {
                out.add(Finding.of(ctx.sectionAnchor("ch1.overview"), Map.of("kind", "report", "field", "inspectionDate"),
                        "점검일 ≤ 제출일(" + ctx.asOf() + ")", insp.toString(), "점검일이 보고서 제출일보다 늦습니다"));
            }
            LocalDate from = insp.minusDays(p.integer("maxSurveyDays", 90));
            for (Defect d : ctx.defects()) {
                if (d.measuredOn() == null) {
                    continue;
                }
                if (d.measuredOn().isAfter(insp) || d.measuredOn().isBefore(from)) {
                    out.add(Finding.of(null, ReviewContext.defectRef(d.id()), from + " ~ " + insp, d.measuredOn().toString(),
                            "측정일이 현장조사 기간 밖입니다: " + d.id()));
                }
            }
            return out;
        }
    }
}
