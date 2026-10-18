package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.RateRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 요율 (BR-CST-005, 08-IMPL Step 3). 구간 [valid_from, valid_to). 신규 등록 시 직전 무기한 행을 같은 트랜잭션에서 마감.
 * 원장 표준단가는 <b>발생일(근무일)</b> 기준, 예산은 계약일 기준(Step 4). ⚠️ 법령 최신본 확인 필요 - 값은 ADMIN 입력만.
 * Redis 캐시는 Step 2 로컬 스택 이후(현재 매 조회 DB).
 */
@Service
public class RateService {

    public static final Set<String> KINDS = Set.of("STD_LABOR", "OVERHEAD_PCT", "TECHFEE_PCT", "EQUIP_DAILY");

    public record Register(String rateKind, String keyCode, BigDecimal value, LocalDate validFrom, String sourceRef) {}

    private final CstJdbc jdbc;
    private final AuditLog audit;
    private final CurrentActor actor;

    public RateService(CstJdbc jdbc, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    public BigDecimal resolve(String kind, String key, LocalDate asOf) {
        return jdbc.effectiveRate(kind, key, asOf).orElseThrow(() -> new DomainException(CstErrors.RATE_NOT_FOUND,
                "유효한 요율 없음: " + kind + "/" + key + " @ " + asOf));
    }

    @Transactional
    public RateRow register(Register r) {
        if (!KINDS.contains(r.rateKind())) {
            throw new DomainException(CstErrors.INVALID, "rateKind: " + KINDS);
        }
        if (r.value() == null || r.value().signum() < 0) {
            throw new DomainException(CstErrors.INVALID, "value >= 0");
        }
        if (r.sourceRef() == null || r.sourceRef().isBlank()) {
            throw new DomainException(CstErrors.INVALID, "근거(sourceRef) 필수 - ADMIN 확인 값만 등록");
        }
        var by = actor.actorIdOrSystem();
        jdbc.lockOpenEndedRate(r.rateKind(), r.keyCode()).ifPresent(prev -> {
            if (!r.validFrom().isAfter(prev.validFrom())) {
                throw new DomainException(CstErrors.INVALID,
                        "신규 적용일은 직전 요율 시작일(" + prev.validFrom() + ") 이후여야 합니다");
            }
            jdbc.closeRate(prev.id(), r.validFrom(), by);
        });
        RateRow row = new RateRow(UuidV7.next(), r.rateKind(), r.keyCode(), r.value(), r.validFrom(), null,
                r.sourceRef(), 0);
        jdbc.insertRate(row, by);
        audit.append("CST", AuditLog.Action.CREATE, "Rate", row.id(), null,
                Map.of("rateKind", r.rateKind(), "keyCode", r.keyCode(), "value", r.value(),
                        "validFrom", r.validFrom().toString(), "sourceRef", r.sourceRef()));
        return row;
    }
}
