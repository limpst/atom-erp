package com.atomeng.erp.dem.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 효력일 기반 규칙 조회 (10-IMPL Step 3 RuleParamService) — 기준일에 유효한 가장 최근 행.
 * Redis 캐시는 후속(규칙 변경 빈도가 낮고 조회는 PK 범위 스캔).
 */
@Service
public class RuleParams {

    private final DemJdbc dem;

    public RuleParams(DemJdbc dem) {
        this.dem = dem;
    }

    public JsonNode get(String key, LocalDate asOf) {
        List<String> rows = dem.jdbc().queryForList("""
                SELECT value_json::text FROM dem.rule_param
                 WHERE rule_key = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
                 ORDER BY effective_from DESC LIMIT 1""", String.class, key, DemJdbc.d(asOf), DemJdbc.d(asOf));
        if (rows.isEmpty()) {
            throw new DomainException(DemErrors.RULE_MISSING, "규칙 없음: " + key + " @" + asOf)
                    .with("reason", "DEM_RULE_MISSING").with("ruleKey", key);
        }
        return dem.tree(rows.get(0));
    }
}
