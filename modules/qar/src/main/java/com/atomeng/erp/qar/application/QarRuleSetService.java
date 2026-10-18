package com.atomeng.erp.qar.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.Severity;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine.RuleDef;
import com.atomeng.erp.qar.domain.autocheck.RuleParams;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 유효기간 규칙 해석 (BR-QAR-007): 기준일에 DEPLOYED 인 AUTO-CHECK 규칙 세트 · 체크리스트 템플릿(유형별, 없으면 RT_COMMON).
 * 라운드는 제출 시점 버전을 rule_versions 에 고정하고, 이후 해석은 고정 버전으로 한다.
 */
@Service
public class QarRuleSetService {

    public record RuleSet(UUID id, String version, List<RuleDef> defs) {}

    public record Template(UUID id, String reportType, String version) {
        public String label() {
            return reportType + "@" + version;
        }
    }

    private final QarJdbc qar;

    public QarRuleSetService(QarJdbc qar) {
        this.qar = qar;
    }

    public RuleSet ruleSetAsOf(LocalDate asOf) {
        List<Map<String, Object>> rows = qar.jdbc().queryForList("""
                SELECT id, version FROM qar.autocheck_rule_set
                 WHERE status = 'DEPLOYED' AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)""",
                QarJdbc.d(asOf), QarJdbc.d(asOf));
        if (rows.isEmpty()) {
            throw new DomainException(QarCodes.RULE_NOT_FOUND, "기준일(" + asOf + ")에 배포된 AUTO-CHECK 규칙 세트가 없습니다");
        }
        return load(QarJdbc.uuid(rows.get(0), "id"), QarJdbc.str(rows.get(0), "version"));
    }

    public RuleSet ruleSetByVersion(String version) {
        Map<String, Object> r = qar.jdbc().queryForList("SELECT id, version FROM qar.autocheck_rule_set WHERE version = ?",
                version).stream().findFirst().orElseThrow(() -> new DomainException(QarCodes.RULE_NOT_FOUND,
                "AUTO-CHECK 규칙 세트 없음: " + version));
        return load(QarJdbc.uuid(r, "id"), version);
    }

    private RuleSet load(UUID id, String version) {
        List<RuleDef> defs = new ArrayList<>();
        for (Map<String, Object> d : qar.jdbc().queryForList("""
                SELECT rule_id, default_severity, params::text AS params, enabled, applies_to
                  FROM qar.autocheck_rule_def WHERE rule_set_id = ? ORDER BY rule_id""", id)) {
            defs.add(new RuleDef(QarJdbc.str(d, "rule_id"), Severity.valueOf(QarJdbc.str(d, "default_severity")),
                    new RuleParams(QarJdbc.tree(d.get("params"))), Boolean.TRUE.equals(d.get("enabled")),
                    strings(d.get("applies_to"))));
        }
        return new RuleSet(id, version, List.copyOf(defs));
    }

    public Template templateAsOf(String reportType, LocalDate asOf) {
        for (String type : List.of(reportType, "RT_COMMON")) {
            List<Map<String, Object>> rows = qar.jdbc().queryForList("""
                    SELECT id, report_type, version FROM qar.checklist_template
                     WHERE report_type = ? AND status = 'DEPLOYED' AND effective_from <= ?
                       AND (effective_to IS NULL OR effective_to > ?)""", type, QarJdbc.d(asOf), QarJdbc.d(asOf));
            if (!rows.isEmpty()) {
                return template(rows.get(0));
            }
        }
        throw new DomainException(QarCodes.RULE_NOT_FOUND, "기준일(" + asOf + ")에 배포된 체크리스트가 없습니다: " + reportType);
    }

    public Template templateByLabel(String label) {
        String[] p = label.split("@", 2);
        return qar.jdbc().queryForList("SELECT id, report_type, version FROM qar.checklist_template "
                + "WHERE report_type = ? AND version = ?", p[0], p[1]).stream().findFirst().map(this::template)
                .orElseThrow(() -> new DomainException(QarCodes.RULE_NOT_FOUND, "체크리스트 없음: " + label));
    }

    private Template template(Map<String, Object> r) {
        return new Template(QarJdbc.uuid(r, "id"), QarJdbc.str(r, "report_type"), QarJdbc.str(r, "version"));
    }

    /** 라운드의 고정 버전(제출 후) 또는 기준일 버전(자체검토 중) */
    public Map<String, String> versionsFor(Map<String, Object> round, String reportType, LocalDate asOf) {
        Map<String, Object> fixed = QarJdbc.map(round.get("rule_versions"));
        if (fixed != null) {
            Map<String, String> m = new LinkedHashMap<>();
            fixed.forEach((k, v) -> m.put(k, String.valueOf(v)));
            return m;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AUTOCHECK", ruleSetAsOf(asOf).version());
        m.put("CHECKLIST", templateAsOf(reportType, asOf).label());
        return m;
    }

    private static List<String> strings(Object pgArray) {
        List<String> out = new ArrayList<>();
        if (pgArray instanceof Array a) {
            try {
                for (Object o : (Object[]) a.getArray()) {
                    out.add(String.valueOf(o));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }
}
