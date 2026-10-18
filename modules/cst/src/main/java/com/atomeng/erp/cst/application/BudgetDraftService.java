package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.SalContractSigned;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.SalContractSigned.EstimateLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 체결 → 실행예산 Rev.0 초안 (FR-CST-001, 설계 08 A3.2-1 · B5.1, TC-CST-01, 08-IMPL Step 4 일부).
 * <ul>
 *   <li>멱등: 이벤트 단위는 core.processed_event(cst.budget-drafter), 업무 단위는 "프로젝트에 예산 Rev 가 이미 있으면 생성 안 함"
 *       (같은 계약의 이벤트가 다른 eventId 로 다시 와도 1건)</li>
 *   <li>WBS: 견적 공종(workItem)마다 1노드(가중치 = 공종 인건비 비율, 합 100), 경비는 90-EXPENSE 노드(가중치 0)</li>
 *   <li>라인: LABOR = 공종 × 등급 인·일 × 견적 노임단가(등급은 HRQ 코드로 변환 ⚠️ 매핑 확인 필요), 경비 = 항목 → 원가요소</li>
 *   <li>BAC = 라인 합(직접인건비 + 직접경비). 제경비 · 기술료는 가격 구성요소라 실행예산 라인에 넣지 않는다.
 *       낙찰률 조정안 · WBS 템플릿 6종 · cst.budget.drafted 알림은 후속(Step 2·4)</li>
 * </ul>
 */
@Service
public class BudgetDraftService {

    private static final Logger log = LoggerFactory.getLogger(BudgetDraftService.class);
    /** SAL 대가 등급 → HRQ 기술자 등급 (⚠️ 코드표 확정 전 가정) */
    static final Map<String, String> GRADE = Map.of("SPECIAL", "MASTER", "ADVANCED", "SENIOR",
            "INTERMEDIATE", "INTER", "BEGINNER", "JUNIOR");
    static final Map<String, String> ELEMENT = Map.of("TRAVEL", "TRAVEL", "LODGING", "LODGING", "VEHICLE", "VEHICLE",
            "EQUIPMENT_DEPRECIATION", "EQUIP_USE", "EQUIP_USE", "EQUIP_USE", "MATERIAL_TEST", "MATERIAL_TEST",
            "PRINTING", "PRINTING", "DRONE", "DRONE", "SUBCONTRACT", "SUBCONTRACT");
    static final Map<String, String> WORK_NAME = Map.of("DATA", "자료수집·분석", "FIELD", "현장조사", "TEST", "재료시험",
            "ANALYSIS", "구조해석", "REPORT", "보고서 작성");

    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public BudgetDraftService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** @return 만든 budget_revision id (이미 있으면 null) */
    @Transactional
    public UUID draftFromContract(SalContractSigned e) {
        // 프로젝트 행 잠금 → 같은 프로젝트 초안 생성 직렬화
        List<UUID> project = jdbc.queryForList("SELECT id FROM core.project WHERE id = ? FOR UPDATE", UUID.class,
                e.projectId());
        if (project.isEmpty()) {
            throw new IllegalStateException("sal.contract.signed 의 프로젝트가 없습니다: " + e.projectId());
        }
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM cst.budget_revision WHERE project_id = ?",
                Integer.class, e.projectId());
        if (existing != null && existing > 0) {
            log.info("실행예산 초안 생략(이미 있음): project={} contract={}", e.projectNo(), e.contractId());
            return null;
        }
        List<EstimateLine> lines = e.estimateLines() == null ? List.of() : e.estimateLines();
        Map<String, BigDecimal> laborByItem = new LinkedHashMap<>();
        for (EstimateLine l : lines) {
            if ("LABOR".equals(l.lineType())) {
                laborByItem.merge(l.workItem(), nz(l.amount()), BigDecimal::add);
            }
        }
        boolean hasExpense = lines.stream().anyMatch(l -> "EXPENSE".equals(l.lineType()));
        BigDecimal laborTotal = laborByItem.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID by = CurrentActor.SYSTEM;
        Map<String, UUID> wbs = new LinkedHashMap<>();
        List<String> items = new ArrayList<>(laborByItem.keySet());
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            String wi = items.get(i);
            BigDecimal weight = i == items.size() - 1
                    ? BigDecimal.valueOf(100).subtract(assigned)
                    : laborTotal.signum() == 0 ? BigDecimal.ZERO
                    : laborByItem.get(wi).multiply(BigDecimal.valueOf(100)).divide(laborTotal, 2, RoundingMode.HALF_UP);
            assigned = assigned.add(weight);
            UUID id = UuidV7.next();
            jdbc.update("""
                    INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (project_id, code) DO NOTHING""", id, e.projectId(), "%02d-%s".formatted((i + 1) * 10, wi),
                    WORK_NAME.getOrDefault(wi, wi), weight.max(BigDecimal.ZERO), (i + 1) * 10);
            wbs.put(wi, wbsId(e.projectId(), "%02d-%s".formatted((i + 1) * 10, wi)));
        }
        if (hasExpense || items.isEmpty()) {
            jdbc.update("""
                    INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) VALUES (?, ?, '90-EXPENSE',
                           '직접경비', ?, 90) ON CONFLICT (project_id, code) DO NOTHING""",
                    UuidV7.next(), e.projectId(), items.isEmpty() ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
            wbs.put("__EXPENSE", wbsId(e.projectId(), "90-EXPENSE"));
        }
        UUID rev = UuidV7.next();
        jdbc.update("""
                INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, change_reason,
                                                created_by)
                VALUES (?, ?, 0, 'DRAFT', 0, ?, ?, ?)""", rev, e.projectId(), nz(e.amount()),
                "sal.contract.signed 자동 초안(계약 " + e.contractId() + ")", by);
        for (EstimateLine l : lines) {
            boolean labor = "LABOR".equals(l.lineType());
            UUID w = labor ? wbs.get(l.workItem()) : wbs.get("__EXPENSE");
            BigDecimal qty = labor ? nz(l.manDays()) : nz(l.quantity());
            BigDecimal unit = nz(l.unitPrice());
            jdbc.update("""
                    INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, grade_code, qty, unit_cost_krw,
                                                planned_start, planned_end, memo)
                    VALUES (?, ?, ?, ?::cst.cost_element, ?, ?, ?, ?, ?, ?)""",
                    UuidV7.next(), rev, w, labor ? "LABOR" : ELEMENT.getOrDefault(l.workItem(), "OTHER"),
                    labor && l.grade() != null ? GRADE.get(l.grade()) : null, qty, unit.setScale(0, RoundingMode.HALF_UP),
                    e.startDate() == null ? null : java.sql.Date.valueOf(e.startDate()),
                    e.endDate() == null ? null : java.sql.Date.valueOf(e.endDate()),
                    labor ? "SAL " + l.workItem() + "/" + l.grade() : "SAL 경비 " + l.workItem());
        }
        jdbc.update("""
                UPDATE cst.budget_revision SET bac_krw = (SELECT coalesce(sum(amount_krw), 0) FROM cst.budget_line
                                                           WHERE revision_id = ?) WHERE id = ?""", rev, rev);
        audit.append("CST", AuditLog.Action.CREATE, "budget_revision", rev, null,
                Map.of("projectId", e.projectId().toString(), "revNo", 0, "status", "DRAFT", "source",
                        "sal.contract.signed", "lines", lines.size()));
        return rev;
    }

    private UUID wbsId(UUID projectId, String code) {
        return jdbc.queryForObject("SELECT id FROM cst.wbs_node WHERE project_id = ? AND code = ?", UUID.class,
                projectId, code);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
