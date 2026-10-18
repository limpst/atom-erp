package com.atomeng.erp.cst.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LineRow;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.SnapshotPoint;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.WbsRow;
import com.atomeng.erp.cst.domain.evm.Distribution;
import com.atomeng.erp.cst.domain.evm.EvmCalculator;
import com.atomeng.erp.cst.domain.evm.EvmModels.AlertPolicy;
import com.atomeng.erp.cst.domain.evm.EvmModels.Assessment;
import com.atomeng.erp.cst.domain.evm.EvmModels.EvmInput;
import com.atomeng.erp.cst.domain.evm.EvmModels.EvmResult;
import com.atomeng.erp.cst.domain.evm.EvmModels.PlannedLine;
import com.atomeng.erp.cst.domain.evm.EvmModels.WbsProgress;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EVM 조회·스냅샷 (FR-CST-009·010·011, 08-IMPL Step 11 일부). 계산은 순수 도메인 {@link EvmCalculator}.
 * 스냅샷은 실단가 AC 기준으로 저장하고, 실단가 열람 권한이 없는 사용자(PM 등)에는 표준단가 AC 로 다시 계산해 보여준다
 * (설계 A2 🔒, 08-IMPL Step 11 팁 "ac / ac_std 두 벌").
 */
@Service
public class EvmService {

    public static final BigDecimal MANUAL_LIMIT_PCT_POINT = new BigDecimal("15");

    public record EvmView(UUID projectId, LocalDate asOf, String acBasis, EvmResult result, Assessment assessment,
                          int budgetRevNo) {}

    private final CstJdbc jdbc;
    private final ObjectMapper om;
    private final CurrentActor actor;
    private final EvmCalculator calc = new EvmCalculator();

    public EvmService(CstJdbc jdbc, ObjectMapper om, CurrentActor actor) {
        this.jdbc = jdbc;
        this.om = om;
        this.actor = actor;
    }

    private record Inputs(EvmInput real, EvmInput std, int revNo) {}

    private Inputs inputs(UUID projectId, LocalDate asOf) {
        var head = jdbc.approvedBudget(projectId).orElseThrow(() -> new DomainException(CstErrors.NOT_FOUND,
                "승인된 실행예산(Baseline)이 없습니다: " + projectId));
        List<LineRow> lines = jdbc.budgetLines(head.revisionId());
        Map<UUID, BigDecimal> budgetByWbs = new HashMap<>();
        List<PlannedLine> planned = new ArrayList<>();
        for (LineRow l : lines) {
            budgetByWbs.merge(l.wbsId(), l.amount(), BigDecimal::add);
            planned.add(new PlannedLine(l.amount(), l.plannedStart(), l.plannedEnd(), Distribution.valueOf(l.curve())));
        }
        Map<UUID, BigDecimal[]> acByWbs = jdbc.actualCostByWbs(projectId, asOf);
        List<WbsProgress> wbsReal = new ArrayList<>();
        List<WbsProgress> wbsStd = new ArrayList<>();
        for (WbsRow w : jdbc.wbsNodes(projectId)) {
            BigDecimal budget = budgetByWbs.get(w.id());
            if (budget == null) {
                continue;   // 예산 없는 WBS(상위 노드 등)는 EV 에 기여하지 않음
            }
            BigDecimal pct = jdbc.latestProgress(w.id(), asOf).orElse(BigDecimal.ZERO);
            BigDecimal[] ac = acByWbs.getOrDefault(w.id(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            wbsReal.add(new WbsProgress(w.code(), budget, pct, ac[0]));
            wbsStd.add(new WbsProgress(w.code(), budget, pct, ac[1]));
        }
        BigDecimal[] ac = jdbc.actualCost(projectId, asOf);
        List<SnapshotPoint> hist = jdbc.snapshotsBetween(projectId, asOf.minusDays(21), asOf);
        BigDecimal slope = EvmCalculator.slopePerWeek(
                hist.stream().map(h -> ChronoUnit.DAYS.between(asOf.minusDays(21), h.asOf())).toList(),
                hist.stream().map(SnapshotPoint::cpi).toList());
        EvmInput real = new EvmInput(asOf, head.bacKrw(), head.contractAmountKrw(), planned, wbsReal, ac[0], slope);
        EvmInput std = new EvmInput(asOf, head.bacKrw(), head.contractAmountKrw(), planned, wbsStd, ac[1], slope);
        return new Inputs(real, std, head.revNo());
    }

    /** 계산 + 스냅샷 저장(실단가 기준, 같은 asOf 는 덮어씀) → 권한에 맞는 기준으로 응답 */
    @Transactional
    public EvmView evaluate(UUID projectId, LocalDate asOf, boolean realCostVisible) {
        Inputs in = inputs(projectId, asOf);
        EvmResult real = calc.compute(in.real());
        Assessment realA = calc.assess(real, in.real(), AlertPolicy.defaults());
        BigDecimal acStd = in.std().actualCost();
        Map<String, BigDecimal> v = new HashMap<>();
        v.put("bac", real.bac());
        v.put("pv", real.pv());
        v.put("ev", real.ev());
        v.put("ac", real.ac());
        v.put("cv", real.cv());
        v.put("sv", real.sv());
        v.put("cpi", real.cpi());
        v.put("spi", real.spi());
        v.put("eac", real.eac());
        v.put("etc", real.etc());
        v.put("vac", real.vac());
        v.put("tcpi", real.tcpi());
        jdbc.upsertSnapshot(projectId, asOf, v, acStd.setScale(0, java.math.RoundingMode.HALF_UP),
                realA.level().name(), json(realA.alerts()));
        if (realCostVisible) {
            return new EvmView(projectId, asOf, "REAL", real, realA, in.revNo());
        }
        EvmResult std = calc.compute(in.std());
        return new EvmView(projectId, asOf, "STANDARD", std, calc.assess(std, in.std(), AlertPolicy.defaults()),
                in.revNo());
    }

    /** 진척률 수동 보정 (FR-CST-009, BR-CST-013: 직전 대비 ±15%p 초과는 EXEC 만). 사유 필수 */
    @Transactional
    public UUID recordManualProgress(UUID projectId, UUID wbsId, LocalDate asOf, BigDecimal pct, String reason,
                                     boolean exec) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(CstErrors.INVALID, "진척률 수동 보정 사유 필수");
        }
        if (pct == null || pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new DomainException(CstErrors.INVALID, "진척률 0~100");
        }
        WbsRow w = jdbc.wbs(wbsId).filter(x -> x.projectId().equals(projectId))
                .orElseThrow(() -> new DomainException(CstErrors.NOT_FOUND, "프로젝트의 WBS 가 아닙니다: " + wbsId));
        BigDecimal prev = jdbc.latestProgress(w.id(), asOf).orElse(BigDecimal.ZERO);
        if (pct.subtract(prev).abs().compareTo(MANUAL_LIMIT_PCT_POINT) > 0 && !exec) {
            throw new DomainException(CstErrors.PROGRESS_LIMIT,
                    "직전 진척률(" + prev + "%) 대비 ±15%p 초과 보정은 EXEC 승인 필요(BR-CST-013)");
        }
        UUID id = UuidV7.next();
        jdbc.insertProgress(id, projectId, wbsId, asOf, pct, "MANUAL", null, reason, actor.actorIdOrSystem());
        return id;
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
