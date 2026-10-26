package com.atomeng.erp.sal.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.api.SalSubscriptionContracts.PtlServiceRequestSubmitted;
import com.atomeng.erp.sal.domain.OpportunityStage;
import com.atomeng.erp.sal.domain.SalCodes;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기회 파이프라인 (FR-SAL-014–016, A6.1, BR-SAL-007·010, 01-IMPL Step 8).
 * 단계 이동은 전이표(OpportunityStage)로만, 이력(opportunity_stage_history) + sal.opportunity.stage-changed 를 같은 트랜잭션에.
 * 점검 도래 리드는 (facility, inspectionType, dueDate) 부분 유니크 인덱스 + ON CONFLICT DO NOTHING — 리스너 트랜잭션을 깨지 않는다.
 */
@Service
public class OpportunityService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(OpportunityService.class);
    private static final Set<String> SOURCES = Set.of("G2B", "DUE_SOON", "INQUIRY", "REFERRAL", "RENEWAL", "PORTAL");
    private static final Set<String> LOST_REASONS = Set.of("PRICE", "PERFORMANCE", "STAFF", "SCHEDULE", "COMPETITOR",
            "OTHER");

    public record Create(String source, UUID clientId, UUID facilityId, UUID bidNoticeId, String serviceType,
                         String title, BigDecimal expectedAmount, Integer probability, LocalDate expectedClose,
                         UUID ownerId) {}

    private final SalJdbc sal;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;
    private final com.atomeng.common.identity.authz.PermissionRegistry registry;

    /** SAL-03: 입찰 품의 승인(→BID_APPROVED) 권한 */
    public static final String BID_APPROVE_PERMISSION = "sal:opportunity:approve";

    public OpportunityService(SalJdbc sal, OutboxPublisher outbox, AuditLog audit, CurrentActor actor, Clock clock,
                              com.atomeng.common.identity.authz.PermissionRegistry registry) {
        this.registry = registry;
        this.sal = sal;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    @Transactional
    public Map<String, Object> create(Create c) {
        if (!SOURCES.contains(c.source())) {
            throw new DomainException(SalCodes.INVALID, "알 수 없는 리드 출처: " + c.source());
        }
        UUID by = actor.actorIdOrSystem();
        UUID id = UuidV7.next();
        String no = sal.nextDocNo("OPP", today().getYear());
        sal.jdbc().update("""
                INSERT INTO sal.opportunity(id, opp_no, source, bid_notice_id, client_id, facility_id, service_type, title,
                                            stage, expected_amount, probability, expected_close, owner_id,
                                            created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'LEAD', ?, ?, ?, ?, ?, ?)""",
                id, no, c.source(), c.bidNoticeId(), c.clientId(), c.facilityId(), c.serviceType(), c.title(),
                c.expectedAmount(), c.probability(), SalJdbc.d(c.expectedClose()), c.ownerId(), by, by);
        history(id, null, "LEAD", by, "생성(" + c.source() + ")");
        if (c.bidNoticeId() != null) {
            sal.jdbc().update("UPDATE sal.bid_notice SET status = 'CONVERTED', updated_at = now() WHERE id = ? "
                    + "AND status IN ('NEW','WATCHING')", c.bidNoticeId());
        }
        Map<String, Object> v = view(sal.get("opportunity", id));
        audit.append("SAL", AuditLog.Action.CREATE, "opportunity", id, null, v);
        return v;
    }

    public Map<String, Object> get(UUID id) {
        return view(sal.get("opportunity", id));
    }

    public List<Map<String, Object>> list(String stage, UUID ownerId, String source, int page, int size) {
        return sal.jdbc().queryForList("""
                SELECT * FROM sal.opportunity
                 WHERE deleted_at IS NULL AND (?::text IS NULL OR stage = ?::text)
                   AND (?::uuid IS NULL OR owner_id = ?::uuid) AND (?::text IS NULL OR source = ?::text)
                 ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""",
                stage, stage, ownerId, ownerId, source, source, size, (long) page * size)
                .stream().map(OpportunityService::view).toList();
    }

    public long count(String stage, UUID ownerId, String source) {
        Long n = sal.jdbc().queryForObject("""
                SELECT count(*) FROM sal.opportunity
                 WHERE deleted_at IS NULL AND (?::text IS NULL OR stage = ?::text)
                   AND (?::uuid IS NULL OR owner_id = ?::uuid) AND (?::text IS NULL OR source = ?::text)""",
                Long.class, stage, stage, ownerId, ownerId, source, source);
        return n == null ? 0 : n;
    }

    /**
     * POST /opportunities/{id}:move (If-Match) — LEAD → QUALIFYING 은 담당 배정 필수, LOST 는 사유 코드 필수(BR-SAL-010).
     * SAL-03: →BID_APPROVED 는 {@code sal:opportunity:approve}(EXEC) 보유자만(전용 {@code :approve-bid} 권장),
     * →WON · →CONTRACTED 는 시스템 전이(견적 수락 · 계약 서명)로만 — 수동 이동은 409 SAL-409-012.
     */
    @Transactional
    public Map<String, Object> move(UUID id, long expectedVersion, String toStage, String note, String lostReason,
                                    UUID ownerId) {
        Map<String, Object> before = sal.lock("opportunity", id);
        IfMatch.check(expectedVersion, SalJdbc.lng(before, "version"));
        OpportunityStage to;
        try {
            to = OpportunityStage.valueOf(toStage);
        } catch (RuntimeException e) {
            throw new DomainException(SalCodes.INVALID, "알 수 없는 단계: " + toStage);
        }
        if (to == OpportunityStage.WON || to == OpportunityStage.CONTRACTED) {
            throw new DomainException(SalCodes.WON_BY_ACCEPTANCE_ONLY, to == OpportunityStage.WON
                    ? "수주(WON)는 고객의 견적 수락(:accept)으로만 전이합니다(수동 단계 이동 금지)"
                    : "계약(CONTRACTED)은 계약 서명(:sign)으로만 전이합니다(수동 단계 이동 금지)");
        }
        if (to == OpportunityStage.BID_APPROVED) {
            requireBidApprover();
        }
        if (to == OpportunityStage.LOST && (lostReason == null || !LOST_REASONS.contains(lostReason))) {
            throw new DomainException(SalCodes.LOST_REASON_REQUIRED, "Lost 사유 코드 필수(BR-SAL-010): " + LOST_REASONS);
        }
        UUID owner = ownerId != null ? ownerId : SalJdbc.uuid(before, "owner_id");
        if (owner == null && to != OpportunityStage.DROPPED) {
            throw new DomainException(SalCodes.INVALID, "담당 영업(ownerId)을 배정해야 다음 단계로 갈 수 있습니다");
        }
        if (ownerId != null) {
            sal.jdbc().update("UPDATE sal.opportunity SET owner_id = ? WHERE id = ?", ownerId, id);
        }
        transition(before, to, note, lostReason);
        Map<String, Object> after = view(sal.get("opportunity", id));
        audit.append("SAL", AuditLog.Action.UPDATE, "opportunity", id, view(before), after);
        return after;
    }

    /**
     * POST /opportunities/{id}:approve-bid (If-Match, sal:opportunity:approve = EXEC) — SAL-03 입찰 품의 승인
     * BIDDING → BID_APPROVED. BR-SAL-009 투찰금액 입력은 이 승인 뒤에만.
     */
    @Transactional
    public Map<String, Object> approveBid(UUID id, long expectedVersion, String note) {
        requireBidApprover();
        Map<String, Object> before = sal.lock("opportunity", id);
        IfMatch.check(expectedVersion, SalJdbc.lng(before, "version"));
        transition(before, OpportunityStage.BID_APPROVED, note == null ? "입찰 품의 승인" : note, null);
        Map<String, Object> after = view(sal.get("opportunity", id));
        audit.append("SAL", AuditLog.Action.APPROVE, "opportunity-bid", id, view(before), after);
        return after;
    }

    /** 웹 요청 주체가 sal:opportunity:approve 를 가져야 함(주체 없음 = 시스템 실행은 통과) */
    private void requireBidApprover() {
        var p = com.atomeng.common.identity.AtomAuthentication.currentPrincipal().orElse(null);
        if (p != null && registry.scopeFor(p.roles(), BID_APPROVE_PERMISSION).isEmpty()) {
            throw new DomainException(SalCodes.BID_APPROVAL_REQUIRED,
                    "입찰 품의 승인(→BID_APPROVED)은 " + BID_APPROVE_PERMISSION + " 권한(EXEC)이 필요합니다");
        }
    }

    /**
     * 시스템 전이(견적 발송 → NEGOTIATING, 고객 수락 → WON, 계약 서명 → CONTRACTED, 공고 취소 → DROPPED).
     * 이미 그 단계거나 전이표에 없으면 건너뛰고 false (업무 흐름을 막지 않음, 로그만).
     */
    public boolean moveIfAllowed(UUID id, OpportunityStage to, String note) {
        Map<String, Object> row = sal.lock("opportunity", id);
        OpportunityStage from = OpportunityStage.valueOf(SalJdbc.str(row, "stage"));
        if (from == to || !from.canMoveTo(to)) {
            log.info("opportunity {} 자동 전이 생략: {} → {}", SalJdbc.str(row, "opp_no"), from, to);
            return false;
        }
        if (row.get("owner_id") == null && to != OpportunityStage.DROPPED) {
            return false;
        }
        transition(row, to, note, null);
        return true;
    }

    private void transition(Map<String, Object> row, OpportunityStage to, String note, String lostReason) {
        UUID id = SalJdbc.uuid(row, "id");
        OpportunityStage from = OpportunityStage.valueOf(SalJdbc.str(row, "stage"));
        if (!from.canMoveTo(to)) {
            throw new DomainException(SalCodes.STAGE_TRANSITION, "기회 단계 전이 불가: %s → %s".formatted(from, to));
        }
        UUID by = actor.actorIdOrSystem();
        sal.jdbc().update("""
                UPDATE sal.opportunity SET stage = ?, lost_reason = ?, lost_note = coalesce(?, lost_note),
                       version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?""",
                to.name(), to == OpportunityStage.LOST ? lostReason : null,
                to == OpportunityStage.LOST || to == OpportunityStage.DROPPED ? note : null, by, id);
        history(id, from.name(), to.name(), by, note);
        outbox.publish(new SalEvents.OpportunityStageChanged(id, from.name(), to.name()));
    }

    private void history(UUID oppId, String from, String to, UUID by, String note) {
        sal.jdbc().update("""
                INSERT INTO sal.opportunity_stage_history(opportunity_id, from_stage, to_stage, changed_by, changed_at, note)
                VALUES (?, ?, ?, ?, ?, ?)""", oppId, from, to, by, Timestamp.from(clock.instant()), note);
    }

    public List<Map<String, Object>> stageHistory(UUID id) {
        return sal.jdbc().queryForList("""
                SELECT from_stage AS "from", to_stage AS "to", changed_by AS "changedBy", changed_at AS "changedAt", note
                  FROM sal.opportunity_stage_history WHERE opportunity_id = ? ORDER BY id""", id);
    }

    @Transactional
    public Map<String, Object> addActivity(UUID oppId, String type, String summary, String nextAction,
                                           LocalDate nextActionAt) {
        Map<String, Object> opp = sal.get("opportunity", oppId);
        UUID id = UuidV7.next();
        sal.jdbc().update("""
                INSERT INTO sal.activity(id, opportunity_id, client_id, activity_type, occurred_at, summary, next_action,
                                         next_action_at, owner_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, oppId, opp.get("client_id"), type, Timestamp.from(clock.instant()), summary, nextAction,
                SalJdbc.d(nextActionAt), actor.actorIdOrSystem());
        return sal.jdbc().queryForMap("""
                SELECT id, opportunity_id AS "opportunityId", activity_type AS "type", occurred_at AS "occurredAt", summary,
                       next_action AS "nextAction", next_action_at AS "nextActionAt" FROM sal.activity WHERE id = ?""", id);
    }

    public List<Map<String, Object>> activities(UUID oppId) {
        return sal.jdbc().queryForList("""
                SELECT id, activity_type AS "type", occurred_at AS "occurredAt", summary, next_action AS "nextAction",
                       next_action_at AS "nextActionAt" FROM sal.activity WHERE opportunity_id = ? ORDER BY occurred_at, id""",
                oppId);
    }

    // ------------------------------------------------------------------ 구독: 점검 도래 · 포털 요청 → 리드

    /**
     * FR-SAL-015 / BR-SAL-007 / TC-SAL-020. 같은 시설물에 진행 중(종결 아닌) 기회가 있으면 리드 대신 활동만 추가.
     * D-180 · 90 · 30 세 번 와도 유니크 키에 dDay 가 없어 첫 이벤트만 리드, 이후는 활동(01-IMPL Step 8 팁).
     * 담당은 비워 둔다(LEAD — 담당 배정은 QUALIFYING 전이 때). 고객은 fac::api 에 조회 포트가 없어 비워 둔다.
     * @return 생성된 기회 id (활동만 추가했거나 중복이면 null)
     */
    @Transactional
    public UUID onDueApproaching(FacEvents.InspectionDueApproaching e) {
        List<Map<String, Object>> open = sal.jdbc().queryForList("""
                SELECT id, opp_no FROM sal.opportunity
                 WHERE facility_id = ? AND deleted_at IS NULL
                   AND stage NOT IN ('WON','LOST','DROPPED','CONTRACTED')
                 ORDER BY created_at LIMIT 1""", e.buildingId());
        String note = "점검 도래 D-%d (%s, %s)".formatted(e.threshold(), e.inspectionType(), e.dueDate());
        if (!open.isEmpty()) {
            UUID oppId = SalJdbc.uuid(open.get(0), "id");
            sal.jdbc().update("""
                    INSERT INTO sal.activity(id, opportunity_id, activity_type, occurred_at, summary, owner_id)
                    VALUES (?, ?, 'NOTE', ?, ?, ?)""", UuidV7.next(), oppId, Timestamp.from(clock.instant()), note,
                    CurrentActor.SYSTEM);
            return null;
        }
        UUID id = UuidV7.next();
        String no = sal.nextDocNo("OPP", today().getYear());
        int n = sal.jdbc().update("""
                INSERT INTO sal.opportunity(id, opp_no, source, facility_id, due_inspection_type, due_date, service_type,
                                            title, stage, created_by, updated_by)
                VALUES (?, ?, 'DUE_SOON', ?, ?, ?, ?, ?, 'LEAD', ?, ?)
                ON CONFLICT (facility_id, due_inspection_type, due_date) WHERE source = 'DUE_SOON' AND deleted_at IS NULL
                DO NOTHING""",
                id, no, e.buildingId(), e.inspectionType(), SalJdbc.d(e.dueDate()), serviceTypeOf(e.inspectionType()),
                "점검 도래 재영업: %s (%s)".formatted(e.inspectionType(), e.dueDate()), CurrentActor.SYSTEM,
                CurrentActor.SYSTEM);
        if (n == 0) {
            return null;
        }
        history(id, null, "LEAD", CurrentActor.SYSTEM, note);
        return id;
    }

    /** ptl.service-request.submitted (스텁 계약) → PORTAL 리드, serviceRequestId 로 멱등 */
    @Transactional
    public UUID onServiceRequest(PtlServiceRequestSubmitted r) {
        UUID id = UuidV7.next();
        String no = sal.nextDocNo("OPP", today().getYear());
        Integer clientExists = r.clientId() == null ? 0 : sal.jdbc().queryForObject(
                "SELECT count(*) FROM core.client WHERE id = ?", Integer.class, r.clientId());
        int n = sal.jdbc().update("""
                INSERT INTO sal.opportunity(id, opp_no, source, source_ref, client_id, facility_id, service_type, title,
                                            stage, created_by, updated_by)
                VALUES (?, ?, 'PORTAL', ?, ?, ?, ?, ?, 'LEAD', ?, ?)
                ON CONFLICT (source_ref) WHERE source = 'PORTAL' AND deleted_at IS NULL DO NOTHING""",
                id, no, r.serviceRequestId().toString(), clientExists != null && clientExists > 0 ? r.clientId() : null,
                r.facilityId(), r.requestType() == null ? "INQUIRY" : r.requestType(),
                "포털 요청: " + (r.message() == null ? "(내용 없음)" : abbreviate(r.message(), 250)),
                CurrentActor.SYSTEM, CurrentActor.SYSTEM);
        if (n == 0) {
            return null;
        }
        history(id, null, "LEAD", CurrentActor.SYSTEM, "포털 요청 " + r.serviceRequestId());
        return id;
    }

    /** FAC 점검 종류 → 용역 종류 코드(⚠️ 코드표 확정 전 단순 매핑) */
    static String serviceTypeOf(String inspectionType) {
        if (inspectionType == null) {
            return "INSPECTION";
        }
        return switch (inspectionType) {
            case "PRECISE_DIAG", "PRECISE_DIAGNOSIS" -> "PRECISE_DIAG";
            case "PRECISE_INSP", "PRECISE_INSPECTION" -> "PRECISE_INSP";
            default -> abbreviate(inspectionType, 30);
        };
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("oppNo", r.get("opp_no"));
        m.put("source", r.get("source"));
        m.put("bidNoticeId", r.get("bid_notice_id"));
        m.put("clientId", r.get("client_id"));
        m.put("facilityId", r.get("facility_id"));
        m.put("dueInspectionType", r.get("due_inspection_type"));
        m.put("dueDate", SalJdbc.localDate(r.get("due_date")));
        m.put("serviceType", r.get("service_type"));
        m.put("title", r.get("title"));
        m.put("stage", r.get("stage"));
        m.put("expectedAmount", r.get("expected_amount"));
        m.put("probability", r.get("probability"));
        m.put("expectedClose", SalJdbc.localDate(r.get("expected_close")));
        m.put("ownerId", r.get("owner_id"));
        m.put("lostReason", r.get("lost_reason"));
        m.put("criteriaChanged", r.get("criteria_changed"));
        m.put("version", r.get("version"));
        return m;
    }
}
