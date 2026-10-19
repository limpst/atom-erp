package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.audit.AuditPartitionMaintenance;
import com.atomeng.erp.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 00-IMPL Step 6 감사 로그 — @Audited, PII 마스킹, VIEW_PII (TC-CORE-010), 월 파티션 배치 */
@ExtendWith(OutputCaptureExtension.class)
class AuditPiiIntegrationTest extends IntegrationTestBase {

    @Autowired
    AuditLog auditLog;
    @Autowired
    AuditPartitionMaintenance partitions;

    private UUID employee(String name, boolean disabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.employee(id, emp_no, name, hire_date, is_disabled_worker, created_by, updated_by)
                VALUES (?, ?, ?, DATE '2023-05-02', ?, ?, ?)""",
                id, "P-" + id.toString().substring(0, 8), name, disabled, id, id);
        return id;
    }

    @Test
    @DisplayName("TC-CORE-010: PII 조회 API → audit_log VIEW_PII 1건, 감사·애플리케이션 로그에 원문 없음")
    void tcCore010_viewPiiIsAuditedAndMasked(CapturedOutput output) throws Exception {
        String rawName = "합성직원가나다";
        UUID emp = employee(rawName, true);
        UUID admin = actor();

        mvc.perform(as(get("/api/v1/core/employees/" + emp + "/sensitive"), admin, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(rawName))          // 권한자 응답은 원문
                .andExpect(jsonPath("$.disabledWorker").value(true));

        var rows = jdbc.queryForList("""
                SELECT action, actor_id, target_type, after_json::text AS after FROM core.audit_log
                 WHERE target_id = ? ORDER BY id""", emp);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("action")).isEqualTo("VIEW_PII");
        assertThat(row.get("target_type")).isEqualTo("Employee");
        assertThat(row.get("actor_id")).isEqualTo(admin);
        String after = (String) row.get("after");
        assertThat(after).contains("합*****다").doesNotContain(rawName);
        assertThat(after).contains("\"disabledWorker\": \"***\"");
        assertThat(output.getAll()).contains("VIEW_PII").doesNotContain(rawName);
        assertThat(auditLog.verifyChain()).isEmpty();
    }

    @Test
    @DisplayName("SEC-CORE-001: 개인(INDIVIDUAL) 고객 생성 감사 로그 → 이름 · 주소 원문 없음(가림), 법인 고객은 원문 유지")
    void individualClientAuditIsMasked() throws Exception {
        String rawName = "합성개인고객가";
        String rawAddress = "서울시 합성구 비밀로 77";
        var r = mvc.perform(as(postJson("/api/v1/core/clients",
                "{\"name\":\"" + rawName + "\",\"clientType\":\"INDIVIDUAL\",\"address\":\"" + rawAddress + "\"}"),
                actor(), "SALES")).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json(r).get("id").asText());
        String after = jdbc.queryForObject("SELECT after_json::text FROM core.audit_log WHERE target_id = ? "
                + "AND action = 'CREATE'", String.class, id);
        assertThat(after).doesNotContain(rawName).doesNotContain(rawAddress).contains("합*****가");

        String corpName = "합성법인고객-" + UUID.randomUUID();
        UUID corp = createClient(actor(), corpName);
        assertThat(jdbc.queryForObject("SELECT after_json::text FROM core.audit_log WHERE target_id = ? "
                + "AND action = 'CREATE'", String.class, corp)).contains(corpName);
    }

    @Test
    @DisplayName("TC-CORE-010 보조: 권한 없는 역할(PM)·AUDITOR 비교 — 거부 시 VIEW_PII 미기록, AUDITOR 조회는 기록")
    void deniedViewIsNotAudited() throws Exception {
        UUID emp = employee("합성직원라마", false);
        mvc.perform(as(get("/api/v1/core/employees/" + emp + "/sensitive"), actor(), "PM"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-001"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE target_id = ?", Integer.class, emp))
                .isZero();
        mvc.perform(as(get("/api/v1/core/employees/" + emp + "/sensitive"), actor(), "AUDITOR"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE target_id = ? AND action = 'VIEW_PII'",
                Integer.class, emp)).isEqualTo(1);
    }

    @Test
    @DisplayName("Step6: 월 파티션 배치 — 다음·다다음 달 audit_log 파티션 생성(멱등)")
    void partitionMaintenance() {
        partitions.ensureNextPartitions();
        partitions.ensureNextPartitions();
        LocalDate in2 = LocalDate.now().plusMonths(2);
        String name = "audit_log_%d%02d".formatted(in2.getYear(), in2.getMonthValue());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'core.audit_log'::regclass AND c.relname = ?""", Integer.class, name))
                .isEqualTo(1);
    }
}
