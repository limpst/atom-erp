package com.atomeng.erp.hrq.application;

import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.FieldResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록요건 평가·예측 (03-IMPL Step 7, FR-HRQ-007). 규칙은 hrq.registration_rule(시행일 기반, ⚠️ 예시 시드).
 * forecast 는 실제 데이터(퇴사 예정·자격 만료·규칙 시행일) 변화점만 평가해 최초 미달일을 찾는다
 * (가상 이벤트 what-if 시나리오 저장은 Step 8).
 */
@Service
public class ComplianceService {

    public record Evaluation(LocalDate date, boolean allSatisfied, List<FieldResult> fields) {}

    public record Forecast(LocalDate from, LocalDate to, LocalDate firstShortfall, Map<String, Integer> shortage,
                           List<Evaluation> series) {}

    private final HrqJdbc jdbc;
    private final Clock clock;

    public ComplianceService(HrqJdbc jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(LocalDate date) {
        LocalDate d = date != null ? date : HrqErrors.today(clock);
        List<FieldResult> fields = ComplianceEngine.evaluateAll(jdbc.staffAt(d), jdbc.registrationRules(d));
        return new Evaluation(d, fields.stream().allMatch(FieldResult::satisfied), fields);
    }

    @Transactional(readOnly = true)
    public Forecast forecast(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : HrqErrors.today(clock);
        LocalDate t = to != null ? to : f.plusDays(365);
        TreeSet<LocalDate> points = new TreeSet<>();
        points.add(f);
        points.addAll(jdbc.changePoints(f, t));
        List<Evaluation> series = new ArrayList<>();
        Evaluation firstFail = null;
        for (LocalDate d : points) {
            Evaluation e = evaluate(d);
            series.add(e);
            if (firstFail == null && !e.allSatisfied()) {
                firstFail = e;
            }
        }
        Map<String, Integer> shortage = new java.util.LinkedHashMap<>();
        if (firstFail != null) {
            firstFail.fields().stream().filter(x -> !x.satisfied())
                    .forEach(x -> x.shortageByGrade().forEach((g, n) -> shortage.merge(x.field() + ":" + g, n, Integer::sum)));
        }
        return new Forecast(f, t, firstFail == null ? null : firstFail.date(), shortage, series);
    }
}
