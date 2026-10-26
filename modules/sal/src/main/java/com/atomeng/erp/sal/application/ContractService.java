package com.atomeng.erp.sal.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.api.ProjectCommandApi;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.application.port.AssignmentFeasibilityPort;
import com.atomeng.erp.sal.domain.ContractStatus;
import com.atomeng.erp.sal.domain.OpportunityStage;
import com.atomeng.erp.sal.domain.SalCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 (FR-SAL-018–020, BR-SAL-008·011·012, 설계 B5.2 + v0.3 ready-to-sign, 01-IMPL Step 9).
 * <ol>
 *   <li>등록(DRAFT): 기회 WON, 견적은 ACCEPTED, 수의계약 사유(DB CHECK ck_negotiated → 422), 최종 견적과 1% 이상 차이면 사유(A8-8),
 *       외화는 환율로 KRW 환산(BR-SAL-012)</li>
 *   <li>사내 결재(EXEC, If-Match): DRAFT → PENDING + {@code sal.contract.ready-to-sign}(고객 서명 요청)</li>
 *   <li>고객 서명 기록(If-Match): HRQ 투입 가능성 검사(포트, BR-SAL-011) → 같은 트랜잭션에서 core::api 로 프로젝트 생성
 *       (PRJ-{서명연도}, 지분 금액) → APPROVED · 기회 CONTRACTED → {@code sal.contract.signed}</li>
 * </ol>
 */
@Service
public class ContractService {

    private static final BigDecimal ONE_PCT = new BigDecimal("0.01");

    public record Create(UUID opportunityId, UUID estimateId, UUID clientId, String title, String serviceType,
                         String contractMethod, String negotiatedReason, BigDecimal jointShare, BigDecimal amount,
                         String currency, BigDecimal fxRate, boolean vatIncluded, LocalDate signDate,
                         LocalDate startDate, LocalDate endDate, Integer warrantyMonths, String amountDiffReason,
                         UUID scanAttachmentId) {}

    private final SalJdbc sal;
    private final ProjectCommandApi projects;
    private final AssignmentFeasibilityPort feasibility;
    private final EstimateService estimates;
    private final OpportunityService opportunities;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;
    private final Clock clock;

    public ContractService(SalJdbc sal, ProjectCommandApi projects, AssignmentFeasibilityPort feasibility,
                           EstimateService estimates, OpportunityService opportunities, OutboxPublisher outbox,
                           AuditLog audit, CurrentActor actor, ObjectMapper om, Clock clock) {
        this.sal = sal;
        this.projects = projects;
        this.feasibility = feasibility;
        this.estimates = estimates;
        this.opportunities = opportunities;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> create(Create c) {
        UUID clientId = c.clientId();
        String serviceType = c.serviceType();
        if (c.opportunityId() != null) {
            if (c.estimateId() == null) {   // SAL-03 견적 없이 계약 → BR-SAL-006 할인 통제 우회 차단
                throw new DomainException(SalCodes.CONTRACT_ESTIMATE_REQUIRED,
                        "영업 기회에 연결된 계약은 고객이 수락한 견적(estimateId)이 필요합니다");
            }
            Map<String, Object> opp = sal.lock("opportunity", c.opportunityId());
            if (!OpportunityStage.WON.name().equals(SalJdbc.str(opp, "stage"))) {
                throw new DomainException(SalCodes.STAGE_NOT_ALLOWED, "계약은 WON 기회에서만 등록합니다: " + opp.get("stage"));
            }
            clientId = clientId != null ? clientId : SalJdbc.uuid(opp, "client_id");
            serviceType = serviceType != null ? serviceType : SalJdbc.str(opp, "service_type");
        }
        if (clientId == null || serviceType == null) {
            throw new DomainException(SalCodes.INVALID, "고객(clientId) · 용역 종류(serviceType)가 필요합니다");
        }
        String currency = c.currency() == null ? "KRW" : c.currency();
        BigDecimal amountKrw;
        if ("KRW".equals(currency)) {
            amountKrw = c.amount().setScale(0, RoundingMode.FLOOR);
        } else {
            if (c.fxRate() == null || c.fxRate().signum() <= 0) {
                throw new DomainException(SalCodes.INVALID, "외화 계약은 계약일 환율(fxRate)이 필요합니다(BR-SAL-012)");
            }
            amountKrw = c.amount().multiply(c.fxRate()).setScale(0, RoundingMode.FLOOR);
        }
        if (c.estimateId() != null) {
            Map<String, Object> est = sal.get("estimate", c.estimateId());
            if (!"ACCEPTED".equals(SalJdbc.str(est, "status"))) {
                throw new DomainException(SalCodes.ESTIMATE_STATE, "고객이 수락한(ACCEPTED) 견적만 계약에 연결합니다");
            }
            if (c.opportunityId() != null && !c.opportunityId().equals(SalJdbc.uuid(est, "opportunity_id"))) {
                // 다른 기회의 수락 견적으로 금액 대조(1%)를 통과시키는 것을 막는다(보안 검토 2026-10-19)
                throw new DomainException(SalCodes.INVALID, "연결한 견적이 이 계약의 영업 기회 견적이 아닙니다");
            }
            BigDecimal offer = SalJdbc.dec(est, "offer_amount");
            if (offer.signum() > 0 && amountKrw.subtract(offer).abs().divide(offer, 6, RoundingMode.HALF_UP)
                    .compareTo(ONE_PCT) >= 0 && (c.amountDiffReason() == null || c.amountDiffReason().isBlank())) {
                throw new DomainException(SalCodes.AMOUNT_DIFF_REASON_REQUIRED,
                        "계약 금액이 최종 견적(%s)과 1%% 이상 다릅니다 — 사유 필수(A8-8)".formatted(offer.toPlainString()));
            }
        }
        UUID by = actor.actorIdOrSystem();
        UUID id = UuidV7.next();
        String no = sal.nextDocNo("CTR", c.signDate().getYear());
        try {
            sal.jdbc().update("""
                    INSERT INTO sal.contract(id, contract_no, opportunity_id, estimate_id, client_id, title, service_type,
                                             contract_method, negotiated_reason, joint_share, amount, currency, fx_rate,
                                             amount_krw, vat_included, amount_diff_reason, sign_date, start_date, end_date,
                                             warranty_months, scan_attachment_id, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, no, c.opportunityId(), c.estimateId(), clientId, c.title(), serviceType, c.contractMethod(),
                    c.negotiatedReason(), c.jointShare() == null ? new BigDecimal("100.00") : c.jointShare(),
                    c.amount(), currency, c.fxRate(), amountKrw, c.vatIncluded(), c.amountDiffReason(),
                    SalJdbc.d(c.signDate()), SalJdbc.d(c.startDate()), SalJdbc.d(c.endDate()), c.warrantyMonths(),
                    c.scanAttachmentId(), by, by);
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (msg.contains("ck_negotiated")) {   // TC-SAL-032: DB CHECK 위반을 업무 오류로
                throw new DomainException(SalCodes.NEGOTIATED_REASON_REQUIRED, "수의계약은 사유 코드가 필요합니다(FR-SAL-020)");
            }
            if (msg.contains("ck_contract_period")) {
                throw new DomainException(SalCodes.INVALID, "계약 종료일은 시작일 이후여야 합니다");
            }
            throw e;
        }
        Map<String, Object> v = get(id);
        audit.append("SAL", AuditLog.Action.CREATE, "contract", id, null, v);
        return v;
    }

    /** 사내 결재(EXEC) — DRAFT → PENDING, sal.contract.ready-to-sign 1회 */
    @Transactional
    public Map<String, Object> approve(UUID id, long expectedVersion, LocalDate signDeadline, List<String> signers) {
        Map<String, Object> c = sal.lock("contract", id);
        IfMatch.check(expectedVersion, SalJdbc.lng(c, "version"));
        requireStatus(c, ContractStatus.DRAFT);
        UUID by = actor.actorIdOrSystem();
        // SAL-06 결재자 ≠ 계약 작성자 · 연결 견적 작성자(다중 역할 SALES+EXEC 본인 결재 금지)
        UUID estimateId = SalJdbc.uuid(c, "estimate_id");
        UUID estimateAuthor = estimateId == null ? null : SalJdbc.uuid(sal.get("estimate", estimateId), "created_by");
        if (by.equals(SalJdbc.uuid(c, "created_by")) || by.equals(estimateAuthor)) {
            throw new DomainException(SalCodes.SELF_APPROVAL, "계약 · 견적 작성자는 본인 계약을 결재할 수 없습니다");
        }
        LocalDate deadline = signDeadline != null ? signDeadline : SalJdbc.localDate(c.get("sign_date")).plusDays(14);
        List<String> who = signers == null ? List.of() : List.copyOf(signers);
        sal.jdbc().update("""
                UPDATE sal.contract SET status = 'PENDING', approved_by = ?, approved_at = ?, sign_deadline = ?,
                       signers = ?::jsonb, version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?""",
                by, Timestamp.from(clock.instant()), SalJdbc.d(deadline), json(who), by, id);
        outbox.publish(new SalEvents.ContractReadyToSign(id, SalJdbc.uuid(c, "client_id"),
                SalJdbc.uuid(c, "scan_attachment_id"), SalJdbc.dec(c, "amount_krw"), deadline, who));
        Map<String, Object> v = get(id);
        audit.append("SAL", AuditLog.Action.APPROVE, "contract", id, Map.of("status", "DRAFT"),
                Map.of("status", "PENDING", "signDeadline", deadline.toString()));
        return v;
    }

    /**
     * 고객 서명 기록 → 프로젝트 생성 · sal.contract.signed (TC-SAL-030·031). 투입 충돌이면 409 SAL-409-003,
     * 프로젝트 · outbox 모두 생성 안 됨(예외로 롤백).
     */
    @Transactional
    public Map<String, Object> sign(UUID id, long expectedVersion, LocalDate signedOn) {
        Map<String, Object> c = sal.lock("contract", id);
        IfMatch.check(expectedVersion, SalJdbc.lng(c, "version"));
        requireStatus(c, ContractStatus.PENDING);
        LocalDate start = SalJdbc.localDate(c.get("start_date"));
        LocalDate end = SalJdbc.localDate(c.get("end_date"));
        UUID estimateId = SalJdbc.uuid(c, "estimate_id");
        AssignmentFeasibilityPort.Result f = feasibility.check(id, estimateId, start, end);
        if (!f.ok()) {
            throw new DomainException(SalCodes.ASSIGNMENT_CONFLICT, "기술자 투입 중복(BR-SAL-011): " + f.conflicts())
                    .with("conflicts", f.conflicts());
        }
        LocalDate signDate = signedOn != null ? signedOn : opportunities.today();
        BigDecimal share = SalJdbc.dec(c, "joint_share");
        BigDecimal selfAmount = SalJdbc.dec(c, "amount_krw").multiply(share)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        var created = projects.createFromContract(new ProjectCommandApi.CreateFromContract(id,
                SalJdbc.str(c, "title"), SalJdbc.str(c, "service_type"), SalJdbc.uuid(c, "client_id"), start, end,
                selfAmount, SalJdbc.str(c, "currency"), signDate.getYear()));
        UUID by = actor.actorIdOrSystem();
        sal.jdbc().update("""
                UPDATE sal.contract SET status = 'APPROVED', project_id = ?, signed_recorded_by = ?, signed_at = ?,
                       version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?""",
                created.projectId(), by, Timestamp.from(clock.instant()), by, id);
        UUID oppId = SalJdbc.uuid(c, "opportunity_id");
        List<UUID> facilities = new ArrayList<>();
        if (oppId != null) {
            opportunities.moveIfAllowed(oppId, OpportunityStage.CONTRACTED, "계약 서명 " + c.get("contract_no"));
            UUID fac = SalJdbc.uuid(sal.get("opportunity", oppId), "facility_id");
            if (fac != null) {
                facilities.add(fac);
            }
        }
        outbox.publish(new SalEvents.ContractSigned(id, created.projectId(), created.projectNo(),
                SalJdbc.uuid(c, "client_id"), List.copyOf(facilities), estimateId, selfAmount, "KRW", share, signDate,
                start, end, estimates.linesForEvent(estimateId)));
        audit.append("SAL", AuditLog.Action.APPROVE, "contract", id, Map.of("status", "PENDING"),
                Map.of("status", "APPROVED", "projectNo", created.projectNo()));
        Map<String, Object> v = get(id);
        v.put("projectNo", created.projectNo());
        return v;
    }

    public Map<String, Object> get(UUID id) {
        Map<String, Object> c = sal.get("contract", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("contractNo", c.get("contract_no"));
        m.put("opportunityId", c.get("opportunity_id"));
        m.put("estimateId", c.get("estimate_id"));
        m.put("clientId", c.get("client_id"));
        m.put("title", c.get("title"));
        m.put("serviceType", c.get("service_type"));
        m.put("contractMethod", c.get("contract_method"));
        m.put("negotiatedReason", c.get("negotiated_reason"));
        m.put("jointShare", c.get("joint_share"));
        m.put("amount", c.get("amount"));
        m.put("currency", c.get("currency"));
        m.put("fxRate", c.get("fx_rate"));
        m.put("amountKrw", c.get("amount_krw"));
        m.put("signDate", SalJdbc.localDate(c.get("sign_date")));
        m.put("startDate", SalJdbc.localDate(c.get("start_date")));
        m.put("endDate", SalJdbc.localDate(c.get("end_date")));
        m.put("signDeadline", SalJdbc.localDate(c.get("sign_deadline")));
        m.put("projectId", c.get("project_id"));
        m.put("status", c.get("status"));
        m.put("approvedBy", c.get("approved_by"));
        m.put("version", c.get("version"));
        return m;
    }

    private static void requireStatus(Map<String, Object> c, ContractStatus expected) {
        if (!expected.name().equals(SalJdbc.str(c, "status"))) {
            throw new DomainException(SalCodes.CONTRACT_STATE, "계약 상태 %s 에서는 할 수 없습니다(필요: %s)"
                    .formatted(c.get("status"), expected));
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
