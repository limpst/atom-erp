package com.atomeng.erp.cst.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LedgerRow;
import com.atomeng.erp.cst.domain.ledger.LaborCost;
import com.atomeng.erp.cst.domain.shared.Money;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import com.atomeng.erp.hrq.api.HrqQueryApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원가 원장 쓰기의 <b>유일한</b> 진입점 (08-IMPL Step 6 DoD). append-only: 정정은 음수 REVERSAL 행(reversal_of) + 재기록.
 * <ul>
 *   <li>멱등 1차: 원천(source_type, source_id) 이 이미 전기됐으면 건너뜀(월이 달라도) — 2차: DB UNIQUE NULLS NOT DISTINCT</li>
 *   <li>귀속월: 요청 월이 CLOSED 면 다음 OPEN 월 (BR-CST-012·015 ④)</li>
 *   <li>인건비: 근무일 기준 등급(hrq::api HrqQueryApi) × 근무일 기준 표준단가 + 근무월 실단가(없으면 표준단가, 플래그)</li>
 * </ul>
 * 실단가 금액은 급여 역산이 가능하므로 로그에 남기지 않는다.
 */
@Service
public class CostLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CostLedgerService.class);
    public static final Set<String> ELEMENTS = Set.of("LABOR", "TRAVEL", "LODGING", "VEHICLE", "EQUIP_USE",
            "MATERIAL_TEST", "DRONE", "SUBCONTRACT", "PRINTING", "OVERHEAD", "TECH_FEE", "OTHER");

    /** 타임시트 1행 전기 요청 (confirmed 의 line, corrected 의 새 리비전) */
    public record LaborPosting(UUID entryId, UUID projectId, String wbsCode, UUID employeeId, LocalDate workDate,
                               String workType, BigDecimal hours, PeriodYm requestedPeriod, String sourceRef) {}

    /** 일반 원가(경비·장비 등) 전기 요청 — KRW 금액 */
    public record CostPosting(String sourceType, UUID sourceId, UUID projectId, String wbsCode, String costElement,
                              LocalDate postingDate, PeriodYm requestedPeriod, BigDecimal amountKrw, BigDecimal qty,
                              UUID employeeId, String sourceRef, List<String> flags) {}

    private final CstJdbc jdbc;
    private final PeriodService periods;
    private final RateService rates;
    private final HrqQueryApi hrq;
    private final CurrentActor actor;

    public CostLedgerService(CstJdbc jdbc, PeriodService periods, RateService rates, HrqQueryApi hrq,
                             CurrentActor actor) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.rates = rates;
        this.hrq = hrq;
        this.actor = actor;
    }

    /** @return 새로 기록한 원장 행(이미 전기된 원천이면 empty) */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> postLabor(LaborPosting p) {
        if (jdbc.findPosted("TIMESHEET", p.entryId()).isPresent()) {
            log.debug("TIMESHEET {} 이미 전기됨 — 건너뜀", p.entryId());
            return Optional.empty();
        }
        PeriodYm period = periods.attribution(p.requestedPeriod());
        String grade = hrq.gradeOf(p.employeeId(), p.workDate()).orElse("UNGRADED");
        BigDecimal std = rates.resolve("STD_LABOR", grade, p.workDate());          // 발생일 기준(08-IMPL Step 3 팁)
        BigDecimal real = jdbc.realDailyCost(p.employeeId(), PeriodYm.of(p.workDate()).value()).orElse(null);
        LaborCost.Result c = LaborCost.compute(p.hours(), std, real);
        List<String> flags = new ArrayList<>();
        if (c.realRateFallback()) {
            flags.add(LaborCost.FLAG_REAL_RATE_FALLBACK);
        }
        if (!period.equals(p.requestedPeriod())) {
            flags.add("PERIOD_SHIFTED");
        }
        LedgerRow row = new LedgerRow(UuidV7.next(), p.projectId(), jdbc.wbsIdOf(p.projectId(), p.wbsCode()).orElse(null),
                p.workDate(), period.value(), "TIMESHEET", p.entryId(), "LABOR", p.employeeId(), grade, p.workType(),
                c.hours(), c.mandays(), c.amountReal().amount(), c.amountStd().amount(), "KRW", c.origAmount(),
                BigDecimal.ONE, null, p.sourceRef(), flags, actor.actorIdOrSystem());
        return jdbc.insertLedger(row).map(id -> row);
    }

    /**
     * 역분개: 원 행과 같은 금액·수량의 음수 REVERSAL 행(reversal_of = 원 행). 귀속월은 requested(정정·취소 월) 기준.
     * @return 새 역분개 행(이미 역분개된 원 행이면 empty)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> reverse(LedgerRow original, PeriodYm requested, String sourceRef) {
        if ("REVERSAL".equals(original.sourceType())) {
            throw new DomainException(CstErrors.INVALID, "역분개 행은 다시 역분개할 수 없습니다");
        }
        if (jdbc.isReversed(original.id())) {
            log.debug("원장 {} 이미 역분개됨 — 건너뜀", original.id());
            return Optional.empty();
        }
        PeriodYm period = periods.attribution(requested);
        LedgerRow rev = new LedgerRow(UuidV7.next(), original.projectId(), original.wbsId(), original.postingDate(),
                period.value(), "REVERSAL", original.sourceId(), original.costElement(), original.employeeId(),
                original.gradeCode(), original.workType(), neg(original.hours()), neg(original.qty()),
                original.amountKrw().negate(), original.amountStdKrw().negate(), original.origCurrency(),
                original.origAmount().negate(), original.fxRate(), original.id(), sourceRef, List.of(),
                actor.actorIdOrSystem());
        return jdbc.insertLedger(rev).map(id -> rev);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> postCost(CostPosting p) {
        if (!ELEMENTS.contains(p.costElement())) {
            throw new DomainException(CstErrors.INVALID, "원가요소: " + p.costElement());
        }
        if (jdbc.findPosted(p.sourceType(), p.sourceId()).isPresent()) {
            return Optional.empty();
        }
        PeriodYm period = periods.attribution(p.requestedPeriod());
        BigDecimal krw = Money.krw(p.amountKrw()).amount();
        List<String> flags = new ArrayList<>(p.flags() == null ? List.of() : p.flags());
        if (!period.equals(p.requestedPeriod())) {
            flags.add("PERIOD_SHIFTED");
        }
        LedgerRow row = new LedgerRow(UuidV7.next(), p.projectId(), jdbc.wbsIdOf(p.projectId(), p.wbsCode()).orElse(null),
                p.postingDate(), period.value(), p.sourceType(), p.sourceId(), p.costElement(), p.employeeId(), null,
                null, null, p.qty(), krw, krw, "KRW", p.amountKrw().setScale(2, Money.RM), BigDecimal.ONE, null,
                p.sourceRef(), flags, actor.actorIdOrSystem());
        return jdbc.insertLedger(row).map(id -> row);
    }

    private static BigDecimal neg(BigDecimal v) {
        return v == null ? null : v.negate();
    }
}
