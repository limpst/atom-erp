package com.atomeng.erp.hrq;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Grade;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Rule;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Staff;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 등록요건 엔진 골든 테스트 (03-IMPL Step 6·7). 규칙 값은 테스트 전용 합성값 — 실제 법정 기준과 무관. */
class ComplianceEngineTest {

    static List<Rule> rules(boolean upward, int each) {
        List<Rule> r = new ArrayList<>();
        for (Grade g : Grade.values()) {
            r.add(new Rule(UUID.randomUUID(), "ARCH", g, each, null, false, upward, "합성"));
        }
        return r;
    }

    /** "MASTER:2,SENIOR:2,INTER:1,JUNIOR:3" → 인원 */
    static List<Staff> pool(String spec) {
        List<Staff> out = new ArrayList<>();
        int n = 0;
        for (String part : spec.split(",")) {
            String[] kv = part.split(":");
            for (int i = 0; i < Integer.parseInt(kv[1]); i++) {
                out.add(new Staff(UUID.randomUUID(), "E%03d".formatted(++n), "ARCH", Grade.valueOf(kv[0]), false, Set.of()));
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}: {1} upward={2} → satisfied={3} shortage={4}")
    @CsvSource(delimiter = ';', value = {
            "R01; MASTER:2,SENIOR:2,INTER:1,JUNIOR:3; false; false; {INTER=1}",   // 03-IMPL golden 예시
            "R02; MASTER:2,SENIOR:2,INTER:2,JUNIOR:2; false; true; {}",
            "R03; MASTER:3,SENIOR:2,INTER:1,JUNIOR:2; true;  true; {}",           // 상위 등급 겸용 → 충족
            "R04; MASTER:3,SENIOR:2,INTER:1,JUNIOR:2; false; false; {INTER=1}",   // 겸용 불가 → 중급 부족
            "R05; MASTER:1,SENIOR:5,INTER:0,JUNIOR:0; true;  false; {MASTER=1, JUNIOR=1}",  // 6명 < 필요 8명
            "R06; MASTER:8,SENIOR:0,INTER:0,JUNIOR:0; true;  true; {}",
    })
    @DisplayName("Step7 golden: 높은 요건부터, 가장 낮은 적격 등급부터 소모(상위 인력 보존)")
    void golden(String id, String spec, boolean upward, boolean satisfied, String shortage) {
        var r = ComplianceEngine.evaluateField("ARCH", pool(spec), rules(upward, 2));
        assertThat(r.satisfied()).as(id).isEqualTo(satisfied);
        assertThat(r.shortageByGrade().toString()).as(id).isEqualTo(shortage);
    }

    @Test
    @DisplayName("그리디 방향: 고급 1명이 특급 요건을 채울 수 없고, 특급 여유 인원은 하위 요건을 겸용")
    void greedyKeepsSeniorForSeniorRule() {
        var r = ComplianceEngine.evaluateField("ARCH", pool("MASTER:3,SENIOR:1,INTER:2,JUNIOR:2"), rules(true, 2));
        assertThat(r.satisfied()).isTrue();
        var senior = r.outcomes().stream().filter(o -> o.rule().minGrade() == Grade.SENIOR).findFirst().orElseThrow();
        assertThat(senior.assignedEmpNos()).hasSize(2); // 고급 1 + 남은 특급 1
    }

    @Test
    @DisplayName("BR-HRQ-005·006: 휴직자는 count_leave=false 면 제외, 필수 자격 미보유(=PENDING)는 미인정")
    void leaveAndCredential() {
        UUID a = UUID.randomUUID();
        var staff = List.of(
                new Staff(a, "E1", "ARCH", Grade.MASTER, true, Set.of("ARCH_STRUCT_PE")),
                new Staff(UUID.randomUUID(), "E2", "ARCH", Grade.MASTER, false, Set.of()));
        var rule = new Rule(UUID.randomUUID(), "ARCH", Grade.MASTER, 1, "ARCH_STRUCT_PE", false, true, "합성");
        assertThat(ComplianceEngine.evaluateField("ARCH", staff, List.of(rule)).satisfied()).isFalse();
        var withLeave = new Rule(UUID.randomUUID(), "ARCH", Grade.MASTER, 1, "ARCH_STRUCT_PE", true, true, "합성");
        assertThat(ComplianceEngine.evaluateField("ARCH", staff, List.of(withLeave)).satisfied()).isTrue();
    }
}
