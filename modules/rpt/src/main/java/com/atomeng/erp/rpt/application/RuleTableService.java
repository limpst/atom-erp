package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.grade.RuleTables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * rule table 해석 (06-IMPL Step 2 RuleResolver · PinnedRules, BR-RPT-002).
 * 기준일(점검 수행일)에 유효한 DEPLOYED 버전을 종류별로 찾아 고정하고, 고정 버전(BLD_RC@2026.1)으로 다시 불러온다.
 * DB 행(rule_set + rule_item)은 골든 픽스처와 같은 JSON 으로 조립한 뒤 {@link RuleTables#parse} 로 검증한다.
 */
@Service
public class RuleTableService {

    /** 고정된 rule 세트 정보(보고서 "적용 기준" 표) */
    public record RuleSetInfo(String kind, String facilityClass, String version, LocalDate effectiveFrom,
                              LocalDate effectiveTo, String legalBasis) {}

    public record Resolved(RuleTables tables, List<RuleSetInfo> sets, JsonNode json) {}

    private final RptJdbc rpt;

    public RuleTableService(RptJdbc rpt) {
        this.rpt = rpt;
    }

    public Resolved resolve(String facilityClass, LocalDate asOf) {
        Map<String, Map<String, Object>> sets = new LinkedHashMap<>();
        for (String kind : RuleTables.KINDS) {
            List<Map<String, Object>> rows = rpt.jdbc().queryForList("""
                    SELECT * FROM rpt.rule_set
                     WHERE rule_kind = ? AND facility_class = ? AND status = 'DEPLOYED'
                       AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)""",
                    kind, facilityClass, RptJdbc.d(asOf), RptJdbc.d(asOf));
            if (rows.isEmpty()) {
                throw new DomainException(RptCodes.RULE_NOT_FOUND,
                        "기준일 %s 에 유효한 %s/%s rule table 이 없습니다".formatted(asOf, kind, facilityClass));
            }
            sets.put(kind, rows.get(0));
        }
        return assemble(facilityClass, sets);
    }

    /** 고정 버전 {"COND_RATING":"BLD_RC@2026.1", …} 으로 불러오기(발행 후 rule 이 바뀌어도 재계산 안 함) */
    public Resolved load(JsonNode pinned) {
        Map<String, Map<String, Object>> sets = new LinkedHashMap<>();
        String fc = null;
        for (String kind : RuleTables.KINDS) {
            String ref = pinned.path(kind).asText(null);
            if (ref == null || !ref.contains("@")) {
                throw new DomainException(RptCodes.RULE_NOT_FOUND, "고정 rule 버전 없음: " + kind);
            }
            fc = ref.substring(0, ref.indexOf('@'));
            List<Map<String, Object>> rows = rpt.jdbc().queryForList(
                    "SELECT * FROM rpt.rule_set WHERE rule_kind = ? AND facility_class = ? AND version = ?",
                    kind, fc, ref.substring(ref.indexOf('@') + 1));
            if (rows.isEmpty()) {
                throw new DomainException(RptCodes.RULE_NOT_FOUND, "rule 버전 없음: " + kind + " " + ref);
            }
            sets.put(kind, rows.get(0));
        }
        return assemble(fc, sets);
    }

    private Resolved assemble(String facilityClass, Map<String, Map<String, Object>> sets) {
        ObjectNode root = RptJson.MAPPER.createObjectNode();
        root.put("facilityClass", facilityClass);
        ObjectNode versions = root.putObject("versions");
        ObjectNode cond = root.putObject("condRating");
        ObjectNode items = cond.putObject("items");
        ObjectNode gw = root.putObject("gradeWeight");
        ObjectNode gb = root.putObject("gradeBand");
        List<RuleSetInfo> infos = new ArrayList<>();
        for (var e : sets.entrySet()) {
            Map<String, Object> s = e.getValue();
            versions.put(e.getKey(), RptJdbc.str(s, "version"));
            infos.add(new RuleSetInfo(e.getKey(), facilityClass, RptJdbc.str(s, "version"),
                    RptJdbc.localDate(s.get("effective_from")), RptJdbc.localDate(s.get("effective_to")),
                    RptJdbc.str(s, "legal_basis")));
            UUID id = RptJdbc.uuid(s, "id");
            for (Map<String, Object> item : rpt.jdbc().queryForList(
                    "SELECT item_key, expr::text AS expr FROM rpt.rule_item WHERE rule_set_id = ? ORDER BY item_key", id)) {
                String key = RptJdbc.str(item, "item_key");
                JsonNode expr = RptJdbc.tree(item.get("expr"));
                switch (e.getKey()) {
                    case RuleTables.COND_RATING -> {
                        if (key.equals("_extent")) {
                            cond.set("extent", expr);
                        } else {
                            items.set(key, expr);
                        }
                    }
                    case RuleTables.GRADE_WEIGHT -> gw.set(key, expr);
                    default -> gb.set(key, expr);
                }
            }
        }
        return new Resolved(RuleTables.parse(root), List.copyOf(infos), root);
    }
}
