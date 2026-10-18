package com.atomeng.erp.qar.domain.autocheck;

import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Defect;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 라운드 대상 리비전의 읽기 전용 검토 컨텍스트 (설계 B1 ReviewContext). RPT 내용은 rpt::api {@code RptRevisionQuery.content}
 * 로만 받고, HRQ 자격 · FLD 사진 · FAC 이력 · 재료시험 같은 원천은 {@link ExternalEvidence}(null = 미연동)로 받는다.
 *
 * @param asOf 실행 기준일(제출일) — 규칙 세트 선택 · 날짜 규칙 기준
 */
public record ReviewContext(RevisionContent content, LocalDate asOf, ExternalEvidence evidence) {

    /** 원천 데이터(다른 모듈) — 각 필드 null 이면 해당 원천 미연동 */
    public record ExternalEvidence(List<Qualification> qualifications, List<Photo> photos, GradeHistory gradeHistory,
                                   Map<String, Integer> materialTests) {
        public static final ExternalEvidence NONE = new ExternalEvidence(null, null, null, null);
    }

    public record Qualification(UUID employeeId, String role, boolean valid, String reason) {}

    public record Photo(String photoNo, String defectId, LocalDate takenOn, String sha256) {}

    /** previousGrade = 직전 회차 확정 등급(없으면 null), repairsSince = 그 이후 보수보강 이력 수 */
    public record GradeHistory(String previousGrade, LocalDate previousOn, int repairsSince) {}

    /** 결함물량표 행(ch4.quantities): 동 · 층 · 부재 · 결함 · 개소 · 길이(m) · 면적(m²) */
    public record QuantityRow(int index, String buildingCode, String floorCode, String memberType, String defectType,
                              String count, String lengthM, String areaM2) {
        public String key() {
            return buildingCode + "/" + floorCode + "/" + memberType + "/" + defectType;
        }
    }

    public static final String QUANTITY_SECTION = "ch4.quantities";

    public UUID revisionId() {
        return content.revision().revisionId();
    }

    public String reportType() {
        return content.revision().reportType();
    }

    public List<Section> sections() {
        return content.sections();
    }

    public List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        content.sections().forEach(s -> out.addAll(s.blocks()));
        return out;
    }

    public Optional<Block> block(UUID blockId) {
        return blocks().stream().filter(b -> b.blockId().equals(blockId)).findFirst();
    }

    public Optional<Section> section(String key) {
        return content.sections().stream().filter(s -> s.sectionKey().equals(key)).findFirst();
    }

    public List<Defect> defects() {
        return content.defects();
    }

    public Optional<Block> quantityTable() {
        return section(QUANTITY_SECTION).flatMap(s -> s.blocks().stream().filter(b -> "TABLE".equals(b.kind())).findFirst());
    }

    public List<QuantityRow> quantityRows() {
        List<QuantityRow> out = new ArrayList<>();
        quantityTable().ifPresent(t -> {
            int i = 0;
            for (List<String> r : t.rows()) {
                if (r.size() >= 7) {
                    out.add(new QuantityRow(i, r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6)));
                }
                i++;
            }
        });
        return out;
    }

    /** 블록 전체 앵커(SECTION 단위보다 정밀, 텍스트 범위 없음) */
    public Map<String, Object> blockAnchor(Block b) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "BLOCK");
        a.put("revisionId", revisionId());
        a.put("sectionKey", b.sectionKey());
        a.put("blockId", b.blockId());
        return a;
    }

    public Map<String, Object> textAnchor(Block b, int start, int end) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "TEXT_RANGE");
        a.put("revisionId", revisionId());
        a.put("sectionKey", b.sectionKey());
        a.put("blockId", b.blockId());
        a.put("start", start);
        a.put("end", end);
        a.put("quote", b.text().substring(start, end));
        return a;
    }

    public Map<String, Object> sectionAnchor(String sectionKey) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "SECTION");
        a.put("revisionId", revisionId());
        a.put("sectionKey", sectionKey);
        return a;
    }

    public Map<String, Object> quantityCellRef(QuantityRow r, String field) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "quantityTable");
        s.put("row", r.index());
        s.put("key", r.key());
        s.put("field", field);
        return s;
    }

    public static Map<String, Object> defectRef(String defectId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", "defect");
        s.put("id", defectId);
        return s;
    }

    public static BigDecimal num(String s) {
        try {
            return s == null || s.isBlank() ? null : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
