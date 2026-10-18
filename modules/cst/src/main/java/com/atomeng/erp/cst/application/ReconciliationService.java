package com.atomeng.erp.cst.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.CstRow;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.HrqRow;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.Key;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code hrq.manday.closed} 처리 — <b>대사·잠금 확인 전용</b> (BR-HRQ-013, 설계 08 B6 콜아웃).
 * 원장에는 절대 쓰지 않는다(이중 집계 방지). HRQ 인·일 vs CST 원장 인·일을 같은 단위로 비교해 기록하고,
 * 회계기간에 HRQ 마감 수신 시각을 남긴다(CST 월 마감 체크리스트 전제). 불일치는 경보 로그 + MISMATCH 기록.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final CstJdbc jdbc;
    private final ObjectMapper om;

    public ReconciliationService(CstJdbc jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MandayReconciler.Result onMandayClosed(UUID eventId, HrqEvents.MandayClosed e) {
        PeriodYm ym = CstErrors.period(e.yearMonth());
        jdbc.ensurePartition(ym.value());
        List<HrqRow> hrq = e.rows().stream().map(r -> new HrqRow(
                new Key(r.projectId(), r.employeeId(), r.grade(), r.workType()), r.mandays())).toList();
        List<CstRow> cst = jdbc.laborGroupsForReconciliation(ym.value()).stream().map(m -> new CstRow(
                new Key((UUID) m.get("project_id"), (UUID) m.get("employee_id"), (String) m.get("grade"),
                        (String) m.get("work_type")), (BigDecimal) m.get("hours"))).toList();
        MandayReconciler.Result r = MandayReconciler.reconcile(hrq, cst);
        if (e.totalMandays() != null && e.totalMandays().compareTo(r.hrqTotal()) != 0) {
            log.warn("hrq.manday.closed totalMandays({}) 와 행 합계({}) 불일치 ym={}", e.totalMandays(), r.hrqTotal(), ym);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groups", r.groups());
        detail.put("eventTotalMandays", e.totalMandays());
        detail.put("diffs", r.diffs().stream().map(d -> Map.of("projectId", d.key().projectId().toString(),
                "employeeId", d.key().employeeId().toString(), "grade", d.key().grade(), "workType", d.key().workType(),
                "hrqMandays", d.hrqMandays(), "cstMandays", d.cstMandays(), "diff", d.diff())).toList());
        String status = r.matched() ? "MATCHED" : "MISMATCH";
        String hash = sha256(om.valueToTree(e).toString());
        if (!jdbc.insertReconciliation(UuidV7.next(), ym.value(), eventId, hash, r.hrqTotal(), r.cstTotal(),
                r.diffTotal(), status, json(detail))) {
            log.info("hrq.manday.closed {} 같은 마감 내용 재수신 — 대사 기록 생략", ym);
            return r;
        }
        jdbc.markHrqClosed(ym.value(), eventId);
        if (!r.matched()) {
            log.warn("HRQ↔CST 인·일 대사 불일치 ym={} hrq={} cst={} diffs={}", ym, r.hrqTotal(), r.cstTotal(),
                    r.diffs().size());
        }
        return r;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
