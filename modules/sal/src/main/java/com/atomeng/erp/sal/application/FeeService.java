package com.atomeng.erp.sal.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.domain.SalCodes;
import com.atomeng.erp.sal.domain.fee.FeeEngine;
import com.atomeng.erp.sal.domain.fee.FeeModels.FacilityProfile;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcCommand;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcResult;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeLine;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 대가 산정 유스케이스 (FR-SAL-009·010, BR-SAL-002, 01-IMPL Step 4·5).
 * 기준일(asOf)에 ACTIVE 인 sal.fee_rule_set 1행만 사용(기간 겹침은 DB EXCLUDE), 없으면 SAL-422-004(A8-5).
 */
@Service
public class FeeService {

    /** 규칙 해석 결과 — 견적 스냅샷(rule_snapshot)에 body 원문을 그대로 저장한다 */
    public record ResolvedRule(UUID id, String ruleVersion, JsonNode body, FeeRuleSet rule, UUID verifiedBy,
                               Object verifiedAt) {}

    private final SalJdbc sal;
    private final FeeEngine engine;
    private final ObjectMapper om;

    public FeeService(SalJdbc sal, FeeEngine engine, ObjectMapper om) {
        this.sal = sal;
        this.engine = engine;
        this.om = om;
    }

    public ResolvedRule resolve(LocalDate asOf) {
        if (asOf == null) {
            throw new DomainException(SalCodes.INVALID, "기준일(asOfDate)은 필수입니다(BR-SAL-002)");
        }
        var rows = sal.jdbc().queryForList("""
                SELECT id, rule_version, body::text AS body, verified_by, verified_at FROM sal.fee_rule_set
                 WHERE status = 'ACTIVE' AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)""",
                SalJdbc.d(asOf), SalJdbc.d(asOf));
        if (rows.isEmpty()) {
            throw new DomainException(SalCodes.RULE_NOT_FOUND, "기준일 %s 에 ACTIVE 대가 규칙이 없습니다 — 산정 차단(A8-5)"
                    .formatted(asOf));
        }
        var r = rows.get(0);
        UUID id = SalJdbc.uuid(r, "id");
        JsonNode body;
        try {
            body = om.readTree(SalJdbc.str(r, "body"));
        } catch (JsonProcessingException e) {
            throw new DomainException(SalCodes.RULE_INVALID, "규칙 본문 JSON 오류: " + id);
        }
        return new ResolvedRule(id, SalJdbc.str(r, "rule_version"), body, FeeRuleSet.parse(id, body),
                SalJdbc.uuid(r, "verified_by"), r.get("verified_at"));
    }

    public FeeCalcResult calculate(FeeCalcCommand cmd, FacilityProfile fac) {
        return engine.calculate(cmd, resolve(cmd.asOfDate()).rule(), fac);
    }

    /** POST /fee-calculations 응답 (설계 B4.1 + 01-IMPL Step 7 ruleRanges · verifiedBy 추가) */
    public Map<String, Object> calculateView(FeeCalcCommand cmd, FacilityProfile fac) {
        ResolvedRule rr = resolve(cmd.asOfDate());
        FeeCalcResult r = engine.calculate(cmd, rr.rule(), fac);
        Map<String, Object> m = view(r);
        m.put("verifiedBy", rr.verifiedBy());
        m.put("verifiedAt", rr.verifiedAt());
        return m;
    }

    public static Map<String, Object> view(FeeCalcResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleSetId", r.ruleSetId());
        m.put("ruleVersion", r.ruleVersion());
        m.put("legalBasis", r.legalBasis());
        m.put("lines", r.lines().stream().map(FeeService::lineView).toList());
        m.put("directLabor", r.directLabor());
        m.put("directExpense", r.directExpense());
        m.put("overheadRate", r.overheadRate());
        m.put("overhead", r.overhead());
        m.put("techFeeRate", r.techFeeRate());
        m.put("techFee", r.techFee());
        m.put("subtotal", r.subtotal());
        m.put("vat", r.vat());
        m.put("explanations", r.explanations());
        m.put("warnings", r.warnings());
        m.put("ruleRanges", Map.of(
                "overhead", Map.of("min", r.overheadRange().min(), "max", r.overheadRange().max()),
                "techFee", Map.of("min", r.techFeeRange().min(), "max", r.techFeeRange().max())));
        return m;
    }

    static Map<String, Object> lineView(FeeLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineType", l.lineType());
        m.put("workItem", l.workItem());
        m.put("grade", l.grade());
        m.put("baseManDays", l.baseManDays());
        m.put("adjFactor", l.adjFactor());
        m.put("manDays", l.manDays());
        m.put("unitPrice", l.unitPrice());
        m.put("quantity", l.quantity());
        m.put("amount", l.amount());
        return m;
    }

    public List<Map<String, Object>> rules() {
        return sal.jdbc().queryForList("""
                SELECT id, rule_version AS "ruleVersion", valid_from AS "validFrom", valid_to AS "validTo", status,
                       legal_basis AS "legalBasis", verified_by AS "verifiedBy", verified_at AS "verifiedAt"
                  FROM sal.fee_rule_set ORDER BY valid_from""");
    }
}
