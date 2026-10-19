package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.RateRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 요율 (BR-CST-005, 08-IMPL Step 3 · 설계 08 A2 "노임단가 · 제경비율 · 환율 기준 관리: ADMIN 관리 · EXEC 승인").
 * 구간 [valid_from, valid_to). ⚠️ 법령 최신본 확인 필요 - 값은 ADMIN 입력만(마이그레이션 시드 없음).
 *
 * <p>절차(V0038): ADMIN 이 DRAFT 로 등록 · 수정 → EXEC 가 승인(등록자 · 마지막 수정자 ≠ 승인자, 4-eyes) → APPROVED.
 * 승인 때 직전 무기한 APPROVED 행을 같은 트랜잭션에서 신규 적용일로 마감하고, 남은 겹침이 있으면 409.
 * 원장 표준단가는 <b>발생일(근무일)</b> 기준, 예산은 계약일 기준(Step 4) — 모두 APPROVED 행만 쓴다.
 * 종료(retire)는 오늘 이후 날짜로만(소급 종료 금지 — 이미 전기된 원장과 어긋나지 않게).
 */
@Service
public class RateService {

    public static final Set<String> KINDS = Set.of("STD_LABOR", "OVERHEAD_PCT", "TECHFEE_PCT", "EQUIP_DAILY");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record Register(String rateKind, String keyCode, BigDecimal value, LocalDate validFrom, LocalDate validTo,
                           String sourceRef) {
        public Register(String rateKind, String keyCode, BigDecimal value, LocalDate validFrom, String sourceRef) {
            this(rateKind, keyCode, value, validFrom, null, sourceRef);
        }
    }

    public record Edit(BigDecimal value, LocalDate validFrom, LocalDate validTo, String sourceRef) {}

    private final CstJdbc jdbc;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;

    public RateService(CstJdbc jdbc, AuditLog audit, CurrentActor actor, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    public BigDecimal resolve(String kind, String key, LocalDate asOf) {
        return jdbc.effectiveRate(kind, key, asOf).orElseThrow(() -> new DomainException(CstErrors.RATE_NOT_FOUND,
                "유효한 요율 없음: " + kind + "/" + key + " @ " + asOf));
    }

    /** ADMIN 초안 등록 — 승인 전에는 원장 · 예산에 쓰이지 않는다 */
    @Transactional
    public RateRow register(Register r) {
        if (!KINDS.contains(r.rateKind())) {
            throw new DomainException(CstErrors.INVALID, "rateKind: " + KINDS);
        }
        validate(r.value(), r.validFrom(), r.validTo(), r.sourceRef());
        if (jdbc.draftExists(r.rateKind(), r.keyCode(), null)) {
            throw new DomainException(CstErrors.RATE_DRAFT_EXISTS,
                    "같은 요율(" + r.rateKind() + "/" + r.keyCode() + ")의 승인 대기 초안이 이미 있습니다 — 그 초안을 수정하세요");
        }
        precheckPredecessor(r.rateKind(), r.keyCode(), r.validFrom());
        var by = actor.actorIdOrSystem();
        RateRow row = new RateRow(UuidV7.next(), r.rateKind(), r.keyCode(), r.value(), r.validFrom(), r.validTo(),
                r.sourceRef(), 0, "DRAFT", by, null, null, null, null, null);
        jdbc.insertRate(row, by);
        audit.append("CST", AuditLog.Action.CREATE, "Rate", row.id(), null, snapshot(row));
        return get(row.id());
    }

    /** 초안 수정(If-Match) — 승인 · 반려된 행은 고칠 수 없다(새 적용일로 다시 등록) */
    @Transactional
    public RateRow edit(UUID id, long expectedVersion, Edit e) {
        RateRow cur = lock(id);
        IfMatch.check(expectedVersion, cur.version());
        if (!"DRAFT".equals(cur.status())) {
            throw new DomainException(CstErrors.RATE_STATE, "승인 대기(DRAFT) 초안만 수정할 수 있습니다 — 현재 " + cur.status());
        }
        BigDecimal value = e.value() != null ? e.value() : cur.value();
        LocalDate from = e.validFrom() != null ? e.validFrom() : cur.validFrom();
        LocalDate to = e.validTo();
        String src = e.sourceRef() != null ? e.sourceRef() : cur.sourceRef();
        validate(value, from, to, src);
        precheckPredecessor(cur.rateKind(), cur.keyCode(), from);
        jdbc.updateDraft(id, value, from, to, src, actor.actorIdOrSystem());
        RateRow after = get(id);
        audit.append("CST", AuditLog.Action.UPDATE, "Rate", id, snapshot(cur), snapshot(after));
        return after;
    }

    /** EXEC 승인(If-Match) — 등록자 · 마지막 수정자와 다른 사람만(4-eyes) */
    @Transactional
    public RateRow approve(UUID id, long expectedVersion) {
        RateRow cur = lock(id);
        IfMatch.check(expectedVersion, cur.version());
        if (!"DRAFT".equals(cur.status())) {
            throw new DomainException(CstErrors.RATE_STATE, "승인 대기(DRAFT) 초안만 승인할 수 있습니다 — 현재 " + cur.status());
        }
        UUID by = actor.actorIdOrSystem();
        UUID lastEditor = jdbc.rateUpdatedBy(id);
        if (by.equals(cur.createdBy()) || by.equals(lastEditor)) {
            throw new DomainException(CstErrors.RATE_SAME_APPROVER,
                    "요율 등록 · 수정자는 같은 요율을 승인할 수 없습니다(4-eyes)").with("reason", "CST_RATE_SOD");
        }
        // 무기한 초안만 직전 무기한 행을 이어받아 마감한다(한정 기간 초안은 중간 끼워 넣기 금지 — 겹치면 409)
        jdbc.lockOpenEndedRate(cur.rateKind(), cur.keyCode()).ifPresent(prev -> {
            if (cur.validTo() == null && cur.validFrom().isAfter(prev.validFrom())) {
                jdbc.closeRate(prev.id(), cur.validFrom(), by);
                audit.append("CST", AuditLog.Action.UPDATE, "Rate", prev.id(), Map.of("validTo", "null"),
                        Map.of("validTo", cur.validFrom().toString(), "closedBy", cur.id()));
            }
        });
        List<RateRow> overlaps = jdbc.overlappingApproved(cur.rateKind(), cur.keyCode(), cur.validFrom(), cur.validTo(),
                cur.id());
        if (!overlaps.isEmpty()) {
            throw overlap(overlaps);
        }
        jdbc.approveRate(id, by);
        RateRow after = get(id);
        audit.append("CST", AuditLog.Action.APPROVE, "Rate", id, snapshot(cur), snapshot(after));
        return after;
    }

    /** EXEC 반려(If-Match, 사유 필수) */
    @Transactional
    public RateRow reject(UUID id, long expectedVersion, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(CstErrors.INVALID, "반려 사유 필수");
        }
        RateRow cur = lock(id);
        IfMatch.check(expectedVersion, cur.version());
        if (!"DRAFT".equals(cur.status())) {
            throw new DomainException(CstErrors.RATE_STATE, "승인 대기(DRAFT) 초안만 반려할 수 있습니다 — 현재 " + cur.status());
        }
        jdbc.rejectRate(id, reason, actor.actorIdOrSystem());
        RateRow after = get(id);
        audit.append("CST", AuditLog.Action.UPDATE, "Rate", id, snapshot(cur), snapshot(after));
        return after;
    }

    /** ADMIN 적용 종료(If-Match) — APPROVED 행의 valid_to 를 오늘 이후 날짜로 당긴다(소급 종료 금지) */
    @Transactional
    public RateRow retire(UUID id, long expectedVersion, LocalDate validTo) {
        RateRow cur = lock(id);
        IfMatch.check(expectedVersion, cur.version());
        if (!"APPROVED".equals(cur.status())) {
            throw new DomainException(CstErrors.RATE_STATE, "승인된 요율만 적용 종료할 수 있습니다 — 현재 " + cur.status());
        }
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (validTo == null || !validTo.isAfter(cur.validFrom()) || validTo.isBefore(today)) {
            throw new DomainException(CstErrors.INVALID,
                    "종료일은 적용 시작일(" + cur.validFrom() + ") 이후이면서 오늘(" + today + ") 이후여야 합니다(소급 종료 금지)");
        }
        if (cur.validTo() != null && !validTo.isBefore(cur.validTo())) {
            throw new DomainException(CstErrors.INVALID, "종료일은 현재 종료일(" + cur.validTo() + ")보다 앞이어야 합니다");
        }
        jdbc.retireRate(id, validTo, actor.actorIdOrSystem());
        RateRow after = get(id);
        audit.append("CST", AuditLog.Action.UPDATE, "Rate", id, snapshot(cur), snapshot(after));
        return after;
    }

    public RateRow get(UUID id) {
        return jdbc.findRate(id).orElseGet(() -> notFound(id));
    }

    // ------------------------------------------------------------------ 내부

    private RateRow lock(UUID id) {
        return jdbc.lockRate(id).orElseGet(() -> notFound(id));
    }

    private static RateRow notFound(UUID id) {
        throw new DomainException(CstErrors.NOT_FOUND, "요율 없음: " + id);
    }

    private static void validate(BigDecimal value, LocalDate from, LocalDate to, String sourceRef) {
        if (value == null || value.signum() < 0) {
            throw new DomainException(CstErrors.INVALID, "value >= 0");
        }
        if (from == null) {
            throw new DomainException(CstErrors.INVALID, "validFrom 필수");
        }
        if (to != null && !to.isAfter(from)) {
            throw new DomainException(CstErrors.INVALID, "validTo 는 validFrom 이후여야 합니다");
        }
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new DomainException(CstErrors.INVALID, "근거(sourceRef) 필수 - ADMIN 확인 값만 등록");
        }
    }

    /** 직전 무기한 APPROVED 행보다 늦게 시작해야 자동 마감할 수 있다 — 등록 · 수정 때 미리 알려 준다 */
    private void precheckPredecessor(String kind, String key, LocalDate from) {
        jdbc.lockOpenEndedRate(kind, key).ifPresent(prev -> {
            if (!from.isAfter(prev.validFrom())) {
                throw new DomainException(CstErrors.INVALID,
                        "신규 적용일은 직전 요율 시작일(" + prev.validFrom() + ") 이후여야 합니다");
            }
        });
    }

    private static DomainException overlap(List<RateRow> overlaps) {
        RateRow o = overlaps.get(0);
        return new DomainException(CstErrors.RATE_OVERLAP, "적용 기간이 승인된 요율과 겹칩니다: " + o.validFrom() + " ~ "
                + (o.validTo() == null ? "무기한" : o.validTo()) + " (" + o.value() + ")")
                .with("overlaps", overlaps.stream().map(RateRow::id).map(UUID::toString).toList());
    }

    private static Map<String, Object> snapshot(RateRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rateKind", r.rateKind());
        m.put("keyCode", r.keyCode());
        m.put("value", r.value());
        m.put("validFrom", Objects.toString(r.validFrom()));
        m.put("validTo", Objects.toString(r.validTo(), null));
        m.put("sourceRef", r.sourceRef());
        m.put("status", r.status());
        m.put("version", r.version());
        return m;
    }
}
