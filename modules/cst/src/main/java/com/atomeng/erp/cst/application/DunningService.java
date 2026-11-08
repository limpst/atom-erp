package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.OpenItem;
import com.atomeng.erp.cst.domain.dunning.DunningLevel;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 독촉(dunning) 1단계 (docs/CST_BILLING_COLLECTION_DESIGN.md §9-1).
 * run(asOf): 기준일의 미수 청구마다 있어야 하는 단계(L0 · L1 · L2)의 초안을 한 번씩만 만든다(멱등 — (invoice, level) 유일).
 * 보류 중 청구는 건너뛴다. 전액 입금된 청구의 남은 초안은 CANCELLED. 자동 발송은 없다 — 사람이 approve · markSent 로 올린다.
 */
@Service
public class DunningService {

    public static final String SAME_PERSON = "CST-403-011";
    public static final String NOT_FOUND = "CST-404-030";
    public static final String STATE = "CST-409-032";
    public static final String ON_HOLD = "CST-409-033";

    public record RunResult(LocalDate asOf, int created, int cancelled, int skippedOnHold) {}

    private final JdbcTemplate jdbc;
    private final BillingJdbc billing;
    private final AuditLog audit;
    private final CurrentActor actor;

    public DunningService(JdbcTemplate jdbc, BillingJdbc billing, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional
    public RunResult run(LocalDate asOf) {
        UUID by = actor.actorIdOrSystem();
        int created = 0;
        int skipped = 0;
        List<OpenItem> open = billing.openItems(asOf, null);
        for (OpenItem o : open) {
            if (onHold(o.invoiceId())) {
                skipped++;
                continue;
            }
            for (DunningLevel l : DunningLevel.dueLevels(o.ageDays())) {
                Integer exists = jdbc.queryForObject("SELECT count(*) FROM cst.dunning_notice WHERE invoice_id = ? AND level = ?",
                        Integer.class, o.invoiceId(), l.name());
                if (exists != null && exists > 0) {
                    continue;
                }
                jdbc.update("INSERT INTO cst.dunning_notice(id, invoice_id, level, as_of, age_days, open_krw, body_text, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", UuidV7.next(), o.invoiceId(), l.name(), Date.valueOf(asOf), o.ageDays(),
                        o.openKrw(), body(l, o, asOf), by);
                created++;
            }
        }
        // 전액 입금(미수 목록에서 사라진) 청구의 남은 초안 · 승인은 취소
        int cancelled = jdbc.update("""
                UPDATE cst.dunning_notice n SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = '전액 입금'
                 WHERE n.status IN ('DRAFT','APPROVED')
                   AND NOT EXISTS (SELECT 1 FROM cst.invoice_open_items(?) o WHERE o.invoice_id = n.invoice_id)""", Date.valueOf(asOf));
        audit.append("CST", AuditLog.Action.CREATE, "dunning_run", UUID.nameUUIDFromBytes(("dunning:" + asOf).getBytes()), null,
                Map.of("asOf", asOf.toString(), "created", created, "cancelled", cancelled, "skippedOnHold", skipped));
        return new RunResult(asOf, created, cancelled, skipped);
    }

    /** 합성 서식 — 개인 연락처 · 계좌번호 원문은 넣지 않는다. 실제 발송 문안은 사람이 고친다 */
    static String body(DunningLevel l, OpenItem o, LocalDate asOf) {
        String head = switch (l) {
            case L0 -> "[알림] ";
            case L1 -> "[안내] ";
            case L2 -> "[공문] ";
        };
        return head + o.clientName() + " 귀중 — 청구 " + o.invoiceNo() + " (발행 " + o.issueDate() + ") 미입금 "
                + o.openKrw().toPlainString() + "원이 기준일 " + asOf + " 현재 " + o.ageDays() + "일 경과했습니다. "
                + "확인 뒤 입금을 요청드립니다. 이미 입금하셨으면 입금일과 금액을 알려 주십시오. (초안 — 발송 전 사람이 확인)";
    }

    public boolean onHold(UUID invoiceId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM cst.dunning_hold WHERE invoice_id = ? AND released_at IS NULL",
                Integer.class, invoiceId);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> notices(String statusOrNull) {
        return jdbc.queryForList("""
                SELECT n.id, n.invoice_id AS "invoiceId", i.invoice_no AS "invoiceNo", i.project_id AS "projectId", c.name AS "clientName",
                       n.level, n.as_of AS "asOf", n.age_days AS "ageDays", n.open_krw AS "openKrw", n.status, n.body_text AS "bodyText",
                       n.created_by AS "createdBy", n.created_at AS "createdAt", n.approved_by AS "approvedBy", n.sent_at AS "sentAt"
                  FROM cst.dunning_notice n JOIN cst.invoice i ON i.id = n.invoice_id JOIN core.client c ON c.id = i.client_id
                 WHERE (?::text IS NULL OR n.status = ?::text)
                 ORDER BY n.as_of DESC, n.level DESC, n.open_krw DESC""", statusOrNull, statusOrNull);
    }

    /** L2 결재 — 초안 작성자와 다른 사람(DB ck_dunning_4eyes 도 같은 기준) */
    @Transactional
    public void approve(UUID id) {
        Map<String, Object> n = one(id);
        UUID by = actor.actorIdOrSystem();
        if (by.equals(n.get("created_by"))) {
            throw new DomainException(SAME_PERSON, "초안을 만든 사람은 결재할 수 없습니다(두 사람 규칙)");
        }
        if (!"DRAFT".equals(n.get("status"))) {
            throw new DomainException(STATE, "DRAFT 만 결재할 수 있습니다: " + n.get("status"));
        }
        jdbc.update("UPDATE cst.dunning_notice SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?", by, id);
        audit.append("CST", AuditLog.Action.APPROVE, "dunning_notice", id, n, Map.of("status", "APPROVED"));
    }

    /** 사람이 실제로 보낸 뒤 기록 — 모든 단계가 다른 사람의 확인(APPROVED)을 거친 뒤에만. 자동 발송은 없다 */
    @Transactional
    public void markSent(UUID id, String channel) {
        Map<String, Object> n = one(id);
        if (!"APPROVED".equals(n.get("status"))) {
            throw new DomainException(STATE, "다른 사람이 확인(APPROVED)한 초안만 발송 기록할 수 있습니다: " + n.get("status"));
        }
        UUID by = actor.actorIdOrSystem();
        jdbc.update("UPDATE cst.dunning_notice SET status = 'SENT', sent_by = ?, sent_at = now(), sent_channel = ? WHERE id = ?", by, channel, id);
        audit.append("CST", AuditLog.Action.UPDATE, "dunning_notice", id, n, Map.of("status", "SENT", "channel", channel));
    }

    @Transactional
    public void hold(UUID invoiceId, String reason) {
        if (onHold(invoiceId)) {
            throw new DomainException(ON_HOLD, "이미 보류 중입니다");
        }
        billing.invoice(invoiceId).orElseThrow(() -> new DomainException(NOT_FOUND, "청구 없음: " + invoiceId));
        jdbc.update("INSERT INTO cst.dunning_hold(id, invoice_id, reason, held_by) VALUES (?, ?, ?, ?)", UuidV7.next(), invoiceId, reason,
                actor.actorIdOrSystem());
        audit.append("CST", AuditLog.Action.UPDATE, "dunning_hold", invoiceId, null, Map.of("reason", reason));
    }

    @Transactional
    public void release(UUID invoiceId, String reason) {
        int n = jdbc.update("UPDATE cst.dunning_hold SET released_by = ?, released_at = now(), release_reason = ? WHERE invoice_id = ? AND released_at IS NULL",
                actor.actorIdOrSystem(), reason, invoiceId);
        if (n == 0) {
            throw new DomainException(NOT_FOUND, "보류 중인 청구가 아닙니다");
        }
        audit.append("CST", AuditLog.Action.UPDATE, "dunning_hold", invoiceId, null, Map.of("released", reason));
    }

    private Map<String, Object> one(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM cst.dunning_notice WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new DomainException(NOT_FOUND, "독촉 초안 없음: " + id);
        }
        return rows.get(0);
    }

}
