package com.atomeng.erp.sal.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.domain.SalCodes;
import com.atomeng.erp.sal.domain.qual.QualificationScorer;
import com.atomeng.erp.sal.domain.qual.QualificationScorer.CompanySnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적격심사 시뮬레이션 · 실적 원천 (FR-SAL-006, BR-SAL-014, 01-IMPL Step 12 · 데이터 이관).
 * 수행능력 실적 금액은 sal.performance_record 만 사용한다(core.project · 견적 · 계약은 보지 않음):
 * 같은 용역 종류 · 공고일 기준 최근 N년(규칙 similarYears, 기본 5) 준공분의 Σ amount_krw × joint_share/100.
 * 실적증명서 번호(cert_no)는 유일 — 이관 시 중복 행은 넣지 않고 리포트로 돌려준다(TC-SAL-041).
 */
@Service
public class QualificationService {

    public record Simulate(String criteriaCode, LocalDate noticeDate, BigDecimal estPrice, String serviceType,
                           List<BigDecimal> bidRates, Integer engineersQualified, Integer engineersRequired,
                           String creditGrade, boolean disabledStandardWorkplace) {}

    public record PerformanceInput(String certNo, UUID projectId, UUID facilityId, String serviceType,
                                   String clientName, BigDecimal amountKrw, BigDecimal jointShare, LocalDate startDate,
                                   LocalDate endDate) {}

    private final SalJdbc sal;
    private final QualificationScorer scorer;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;

    public QualificationService(SalJdbc sal, QualificationScorer scorer, AuditLog audit, CurrentActor actor,
                                ObjectMapper om) {
        this.sal = sal;
        this.scorer = scorer;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> simulate(UUID opportunityId, Simulate s) {
        Map<String, Object> opp = sal.get("opportunity", opportunityId);
        BigDecimal estPrice = s.estPrice();
        LocalDate noticeDate = s.noticeDate();
        UUID noticeId = SalJdbc.uuid(opp, "bid_notice_id");
        if (noticeId != null) {
            Map<String, Object> n = sal.get("bid_notice", noticeId);
            if (estPrice == null) {
                estPrice = SalJdbc.dec(n, "est_price");
            }
            if (noticeDate == null && n.get("notice_at") != null) {
                noticeDate = ((java.sql.Timestamp) n.get("notice_at")).toInstant()
                        .atZone(OpportunityService.SEOUL).toLocalDate();
            }
        }
        if (noticeDate == null) {
            throw new DomainException(SalCodes.INVALID, "공고일(noticeDate)이 필요합니다");
        }
        if (s.bidRates() == null || s.bidRates().isEmpty()) {
            throw new DomainException(SalCodes.INVALID, "투찰률 시나리오(bidRates)가 1개 이상 필요합니다");
        }
        String code = s.criteriaCode() == null ? "LOCAL_GOV_SERVICE_V2025" : s.criteriaCode();
        var rows = sal.jdbc().queryForList("""
                SELECT id, body::text AS body FROM sal.qual_criteria
                 WHERE criteria_code = ? AND status = 'ACTIVE' AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
                 ORDER BY valid_from DESC LIMIT 1""", code, SalJdbc.d(noticeDate), SalJdbc.d(noticeDate));
        if (rows.isEmpty()) {
            throw new DomainException(SalCodes.RULE_NOT_FOUND, "적격심사 기준 %s 이(가) %s 에 ACTIVE 가 아닙니다"
                    .formatted(code, noticeDate));
        }
        UUID criteriaId = SalJdbc.uuid(rows.get(0), "id");
        JsonNode criteria = read(SalJdbc.str(rows.get(0), "body"));
        String serviceType = s.serviceType() != null ? s.serviceType() : SalJdbc.str(opp, "service_type");
        int years = criteria.path("similarYears").asInt(5);
        LocalDate fromDate = noticeDate.minusYears(years);
        List<Map<String, Object>> used = sal.jdbc().queryForList("""
                SELECT cert_no AS "certNo", amount_krw AS "amountKrw", joint_share AS "jointShare", end_date AS "endDate",
                       source, round(amount_krw * joint_share / 100, 0) AS "creditedKrw"
                  FROM sal.performance_record
                 WHERE service_type = ? AND end_date > ? AND end_date <= ? ORDER BY end_date, cert_no""",
                serviceType, SalJdbc.d(fromDate), SalJdbc.d(noticeDate));
        BigDecimal similar = BigDecimal.ZERO;
        for (Map<String, Object> r : used) {
            similar = similar.add(SalJdbc.dec(r, "amountKrw").multiply(SalJdbc.dec(r, "jointShare"))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP));
        }
        CompanySnapshot snap = new CompanySnapshot(similar, used.size(), s.engineersQualified(), s.engineersRequired(),
                s.creditGrade(), s.disabledStandardWorkplace());
        var result = scorer.simulate(criteria, snap, estPrice, s.bidRates());

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("noticeDate", noticeDate.toString());
        inputs.put("estPrice", estPrice);
        inputs.put("serviceType", serviceType);
        inputs.put("similarYears", years);
        inputs.put("performanceSource", "sal.performance_record");
        inputs.put("performanceRecords", used);
        inputs.put("similarAmount", similar);
        inputs.put("engineersQualified", s.engineersQualified());
        inputs.put("engineersRequired", s.engineersRequired());
        inputs.put("creditGrade", s.creditGrade());
        inputs.put("disabledStandardWorkplace", s.disabledStandardWorkplace());
        List<Map<String, Object>> items = result.capability().items().stream().map(QualificationScorer::view).toList();
        List<Map<String, Object>> scenarios = new ArrayList<>();
        result.scenarios().forEach(sc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bidRate", sc.bidRate());
            m.put("priceScore", sc.priceScore());
            m.put("total", sc.total());
            m.put("pass", sc.pass());
            m.put("belowLowerLimit", sc.belowLowerLimit());
            scenarios.add(m);
        });
        UUID id = UuidV7.next();
        sal.jdbc().update("""
                INSERT INTO sal.qual_simulation(id, opportunity_id, criteria_id, inputs, capability_scores, scenarios,
                                                selected_bid_rate, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)""",
                id, opportunityId, criteriaId, json(inputs), json(Map.of("items", items,
                        "total", result.capability().total())), json(scenarios), result.recommendedBidRate(),
                actor.actorIdOrSystem());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("opportunityId", opportunityId);
        m.put("criteriaCode", result.criteriaCode());
        m.put("passScore", result.passScore());
        m.put("lowerLimitRate", result.lowerLimitRate());
        m.put("inputs", inputs);
        m.put("capability", Map.of("items", items, "total", result.capability().total()));
        m.put("scenarios", scenarios);
        m.put("recommendedBidRate", result.recommendedBidRate());
        m.put("notice", result.notice());
        return m;
    }

    /**
     * 실적 이관(MIGRATED) — cert_no 유일: DB 기존 · 같은 배치 안 중복은 거부하고 리포트(TC-SAL-041). 나머지는 넣는다.
     */
    @Transactional
    public Map<String, Object> importRecords(List<PerformanceInput> rows, String source) {
        String src = source == null ? "MIGRATED" : source;
        UUID by = actor.actorIdOrSystem();
        List<String> inserted = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PerformanceInput r : rows) {
            String reason = validate(r);
            if (reason == null && r.certNo() != null && !seen.add(r.certNo())) {
                reason = "DUPLICATE_IN_BATCH";
            }
            if (reason != null) {
                rejected.add(Map.of("certNo", String.valueOf(r.certNo()), "reason", reason));
                continue;
            }
            int n = sal.jdbc().update("""
                    INSERT INTO sal.performance_record(id, cert_no, project_id, facility_id, service_type, client_name,
                                                       amount_krw, joint_share, start_date, end_date, source, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (cert_no) DO NOTHING""",
                    UuidV7.next(), r.certNo(), r.projectId(), r.facilityId(), r.serviceType(), r.clientName(),
                    r.amountKrw(), r.jointShare() == null ? BigDecimal.valueOf(100) : r.jointShare(),
                    SalJdbc.d(r.startDate()), SalJdbc.d(r.endDate()), src, by);
            if (n == 1) {
                inserted.add(r.certNo());
            } else {
                rejected.add(Map.of("certNo", r.certNo(), "reason", "DUPLICATE_CERT_NO"));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", src);
        report.put("requested", rows.size());
        report.put("inserted", inserted.size());
        report.put("rejected", rejected);
        audit.append("SAL", AuditLog.Action.CREATE, "performance-import", UuidV7.next(), null,
                Map.of("requested", rows.size(), "inserted", inserted.size(), "rejected", rejected.size()));
        return report;
    }

    private static String validate(PerformanceInput r) {
        if (r.serviceType() == null || r.clientName() == null || r.amountKrw() == null || r.startDate() == null
                || r.endDate() == null) {
            return "MISSING_FIELD";
        }
        if (r.endDate().isBefore(r.startDate())) {
            return "INVALID_PERIOD";
        }
        if (r.jointShare() != null && (r.jointShare().signum() <= 0 || r.jointShare().compareTo(BigDecimal.valueOf(100)) > 0)) {
            return "INVALID_JOINT_SHARE";
        }
        return null;
    }

    public List<Map<String, Object>> performanceRecords(String serviceType) {
        return sal.jdbc().queryForList("""
                SELECT id, cert_no AS "certNo", service_type AS "serviceType", client_name AS "clientName",
                       amount_krw AS "amountKrw", joint_share AS "jointShare", start_date AS "startDate",
                       end_date AS "endDate", source FROM sal.performance_record
                 WHERE (?::text IS NULL OR service_type = ?::text) ORDER BY end_date DESC, cert_no LIMIT 500""",
                serviceType, serviceType);
    }

    private JsonNode read(String s) {
        try {
            return om.readTree(s);
        } catch (JsonProcessingException e) {
            throw new DomainException(SalCodes.RULE_INVALID, "적격심사 기준 JSON 오류");
        }
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new DomainException(SalCodes.INVALID, "JSON 직렬화 오류");
        }
    }
}
