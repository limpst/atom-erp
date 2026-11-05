package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V0052 · V0053 청구 · 수금 · 통장 대사 스키마 시험 (docs/CST_BILLING_COLLECTION_DESIGN.md).
 * 내장 PostgreSQL 16 (Docker 없음), 합성 데이터만. 기준일은 고정값(2026-10-31)을 쓴다.
 */
class BillingMigrationTest {

    static String url;
    static UUID project;
    static UUID client;
    static final UUID FIN = UUID.randomUUID();
    static final UUID ADMIN = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("cst_bil");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac", "cst").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac", "classpath:db/migration/cst")
                .load().migrate().success).isTrue();
        project = UUID.randomUUID();
        client = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-BIL', '합성건설', 'PRIVATE', '%s', '%s')").formatted(client, FIN, FIN));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-8101', '합성 청구', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, FIN, FIN));
        }
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    static Object scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }

    static UUID invoice(Statement st, String no, String issueDate, long supply, long vat) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO cst.invoice(id, invoice_no, project_id, client_id, issue_date, supply_krw, vat_krw, created_by) "
                + "VALUES ('%s', '%s', '%s', '%s', DATE '%s', %d, %d, '%s')")
                .formatted(id, no, project, client, issueDate, supply, vat, FIN));
        return id;
    }

    static UUID receipt(Statement st, String key, String valueDate, long amount) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO cst.bank_receipt(id, bank_account, value_date, amount_krw, payer_text, statement_key, created_by) "
                + "VALUES ('%s', 'SYN-운영', DATE '%s', %d, '합성건설', '%s', '%s')").formatted(id, valueDate, amount, key, FIN));
        return id;
    }

    static String alloc(UUID id, UUID inv, UUID rcp, long amount, UUID reversalOf, UUID proposer, UUID confirmer) {
        return ("INSERT INTO cst.receipt_allocation(id, invoice_id, receipt_id, amount_krw, reversal_of, match_score, "
                + "proposed_by, confirmed_by) VALUES ('%s', '%s', '%s', %d, %s, 100, '%s', '%s')")
                .formatted(id, inv, rcp, amount, reversalOf == null ? "NULL" : "'" + reversalOf + "'", proposer, confirmer);
    }

    @Test
    @DisplayName("스키마: 표 3개 · 합계 생성 열 · 부동소수 열 없음 · 권한 19건")
    void schemaCreated() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            for (String t : new String[] {"invoice", "bank_receipt", "receipt_allocation"}) {
                assertThat((Long) scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'cst' "
                        + "AND table_name = '" + t + "'")).as(t).isEqualTo(1L);
            }
            UUID inv = invoice(st, "INV-T-0001", "2026-10-01", 1_000_000, 100_000);
            assertThat((BigDecimal) scalar(st, "SELECT total_krw FROM cst.invoice WHERE id = '" + inv + "'"))
                    .isEqualByComparingTo("1100000");
            assertThat((Long) scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'cst' "
                    + "AND data_type IN ('real', 'double precision')")).as("부동소수 금지(NFR-CST-02)").isZero();
            assertThat((Long) scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission LIKE 'cst:invoice:%' "
                    + "OR permission LIKE 'cst:receipt:%' OR permission LIKE 'cst:allocation:%' "
                    + "OR permission LIKE 'cst:receivable:%'")).isEqualTo(19L);
            assertThat((Long) scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission = 'cst:allocation:confirm' "
                    + "AND role = 'FIN'")).as("재무는 제안만, 확정 권한 없음(직무 분리)").isZero();
        }
    }

    @Test
    @DisplayName("BR-CST-BIL-001: 발행된 청구는 금액 · 거래처 · 발행일을 못 고치고 지울 수도 없다(CST-409-020)")
    void invoiceImmutable() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID inv = invoice(st, "INV-T-0002", "2026-09-01", 2_000_000, 200_000);
            assertThatThrownBy(() -> st.execute("UPDATE cst.invoice SET supply_krw = 1 WHERE id = '" + inv + "'"))
                    .hasMessageContaining("CST-409-020");
            assertThatThrownBy(() -> st.execute("UPDATE cst.invoice SET issue_date = DATE '2026-01-01' WHERE id = '" + inv + "'"))
                    .hasMessageContaining("CST-409-020");
            assertThatThrownBy(() -> st.execute("DELETE FROM cst.invoice WHERE id = '" + inv + "'"))
                    .hasMessageContaining("CST-409-020");
            st.execute("UPDATE cst.invoice SET memo = '메모는 고칠 수 있다' WHERE id = '" + inv + "'");
        }
    }

    @Test
    @DisplayName("BR-CST-BIL-002·003: 입금 · 배분은 INSERT 만. 같은 명세서 줄은 한 번만(재업로드 멱등)")
    void appendOnlyAndIdempotentStatementLine() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID inv = invoice(st, "INV-T-0003", "2026-09-01", 1_000_000, 100_000);
            UUID rcp = receipt(st, "file-a:1", "2026-09-10", 1_100_000);
            assertThatThrownBy(() -> receipt(st, "file-a:1", "2026-09-10", 1_100_000)).hasMessageContaining("ux_bank_receipt_line");
            assertThatThrownBy(() -> st.execute("UPDATE cst.bank_receipt SET amount_krw = 1 WHERE id = '" + rcp + "'"))
                    .hasMessageContaining("CST-409-022");
            UUID a = UUID.randomUUID();
            st.execute(alloc(a, inv, rcp, 1_100_000, null, FIN, ADMIN));
            assertThatThrownBy(() -> st.execute("UPDATE cst.receipt_allocation SET amount_krw = 5 WHERE id = '" + a + "'"))
                    .hasMessageContaining("CST-409-023");
            assertThatThrownBy(() -> st.execute("DELETE FROM cst.receipt_allocation WHERE id = '" + a + "'"))
                    .hasMessageContaining("CST-409-023");
        }
    }

    @Test
    @DisplayName("BR-CST-BIL-004·005: 제안자 = 확정자 거부, 청구 합계 · 입금액을 넘는 배분 거부")
    void fourEyesAndOverAllocation() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID inv = invoice(st, "INV-T-0004", "2026-08-01", 1_000_000, 100_000);
            UUID big = receipt(st, "file-b:1", "2026-08-20", 5_000_000);
            UUID small = receipt(st, "file-b:2", "2026-08-21", 300_000);
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, big, 100_000, null, FIN, FIN)))
                    .hasMessageContaining("ck_alloc_4eyes");
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, big, 1_100_001, null, FIN, ADMIN)))
                    .hasMessageContaining("CST-409-025");
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, small, 300_001, null, FIN, ADMIN)))
                    .hasMessageContaining("CST-409-026");
            st.execute(alloc(UUID.randomUUID(), inv, small, 300_000, null, FIN, ADMIN));     // 부분 입금
            st.execute(alloc(UUID.randomUUID(), inv, big, 800_000, null, FIN, ADMIN));       // 잔액 입금
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, big, 1, null, FIN, ADMIN)))
                    .as("이미 다 받은 청구").hasMessageContaining("CST-409-025");
        }
    }

    @Test
    @DisplayName("역분개: 원 배분을 그대로 뒤집은 음수 한 줄만 허용, 두 번은 안 된다. 받은 돈이 남아 있는 청구는 VOID 불가(CST-409-021)")
    void reversalAndVoid() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID inv = invoice(st, "INV-T-0005", "2026-07-01", 1_000_000, 100_000);
            UUID rcp = receipt(st, "file-c:1", "2026-07-15", 1_100_000);
            UUID a = UUID.randomUUID();
            st.execute(alloc(a, inv, rcp, 1_100_000, null, FIN, ADMIN));
            assertThatThrownBy(() -> st.execute("UPDATE cst.invoice SET status = 'VOID' WHERE id = '" + inv + "'"))
                    .hasMessageContaining("CST-409-021");
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, rcp, -1_000_000, a, FIN, ADMIN)))
                    .as("금액이 거울이 아님").hasMessageContaining("CST-422-020");
            st.execute(alloc(UUID.randomUUID(), inv, rcp, -1_100_000, a, FIN, ADMIN));
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, rcp, -1_100_000, a, FIN, ADMIN)))
                    .as("두 번째 역분개").isInstanceOf(SQLException.class);
            st.execute("UPDATE cst.invoice SET status = 'VOID' WHERE id = '" + inv + "'");
            assertThatThrownBy(() -> st.execute(alloc(UUID.randomUUID(), inv, rcp, 100, null, FIN, ADMIN)))
                    .hasMessageContaining("CST-409-024");
            assertThatThrownBy(() -> st.execute("UPDATE cst.invoice SET status = 'ISSUED' WHERE id = '" + inv + "'"))
                    .hasMessageContaining("CST-409-021");
        }
    }

    @Test
    @DisplayName("BR-CST-BIL-006: 기준일 2026-10-31 aging — 구간별 건수 · 미수 합, 기준일 뒤 입금은 세지 않는다")
    void agingAsOf() throws Exception {
        String asOfDb = EmbeddedPg.newDatabaseUrl("cst_bil_aging");
        Flyway.configure().dataSource(asOfDb, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac", "cst").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac", "classpath:db/migration/cst")
                .load().migrate();
        try (var c = DriverManager.getConnection(asOfDb, EmbeddedPg.user(), EmbeddedPg.password()); var st = c.createStatement()) {
            UUID cl = UUID.randomUUID();
            UUID pj = UUID.randomUUID();
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-AGE', '합성관리단', 'APT', '%s', '%s')").formatted(cl, FIN, FIN));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-8102', '합성 aging', 'PRECISE_DIAG', '%s', '%s', '%s')").formatted(pj, cl, FIN, FIN));
            String ins = "INSERT INTO cst.invoice(id, invoice_no, project_id, client_id, issue_date, supply_krw, vat_krw, created_by) "
                    + "VALUES ('%s', '%s', '" + pj + "', '" + cl + "', DATE '%s', %d, %d, '" + FIN + "')";
            UUID a30 = UUID.randomUUID();   // 30일 → A
            UUID b31 = UUID.randomUUID();   // 31일 → B
            UUID d91 = UUID.randomUUID();   // 91일 → D
            UUID e200 = UUID.randomUUID();  // 200일 → E, 일부 입금
            UUID paid = UUID.randomUUID();  // 전액 입금 → 목록에서 빠짐
            UUID future = UUID.randomUUID(); // 기준일 뒤 발행 → 제외
            st.execute(ins.formatted(a30, "INV-A", "2026-10-01", 1_000_000, 100_000));
            st.execute(ins.formatted(b31, "INV-B", "2026-09-30", 2_000_000, 200_000));
            st.execute(ins.formatted(d91, "INV-D", "2026-08-01", 3_000_000, 300_000));
            st.execute(ins.formatted(e200, "INV-E", "2026-04-14", 4_000_000, 400_000));
            st.execute(ins.formatted(paid, "INV-P", "2026-09-01", 500_000, 50_000));
            st.execute(ins.formatted(future, "INV-F", "2026-11-05", 900_000, 90_000));
            String rc = "INSERT INTO cst.bank_receipt(id, bank_account, value_date, amount_krw, payer_text, statement_key, created_by) "
                    + "VALUES ('%s', 'SYN-운영', DATE '%s', %d, '합성관리단', '%s', '" + FIN + "')";
            UUID r1 = UUID.randomUUID();
            UUID r2 = UUID.randomUUID();
            UUID r3 = UUID.randomUUID();
            st.execute(rc.formatted(r1, "2026-06-01", 1_400_000, "age:1"));
            st.execute(rc.formatted(r2, "2026-09-20", 550_000, "age:2"));
            st.execute(rc.formatted(r3, "2026-11-10", 1_100_000, "age:3"));   // 기준일 뒤 입금
            st.execute(alloc(UUID.randomUUID(), e200, r1, 1_400_000, null, FIN, ADMIN));
            st.execute(alloc(UUID.randomUUID(), paid, r2, 550_000, null, FIN, ADMIN));
            st.execute(alloc(UUID.randomUUID(), a30, r3, 1_100_000, null, FIN, ADMIN));

            try (ResultSet rs = st.executeQuery("SELECT bucket, invoice_count, open_krw FROM cst.receivable_aging(DATE '2026-10-31')")) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('=').append(rs.getLong(2)).append(':')
                            .append(rs.getBigDecimal(3).toPlainString()).append(' ');
                }
                assertThat(sb.toString().trim())
                        .isEqualTo("A_0_30=1:1100000 B_31_60=1:2200000 D_91_180=1:3300000 E_181_PLUS=1:3000000");
            }
            assertThat((Long) scalar(st, "SELECT count(*) FROM cst.invoice_open_items(DATE '2026-10-31')")).isEqualTo(4L);
            assertThat((Integer) scalar(st, "SELECT age_days FROM cst.invoice_open_items(DATE '2026-10-31') WHERE invoice_no = 'INV-E'"))
                    .isEqualTo(200);
            // 기준일을 2026-11-30 으로 옮기면 A30 은 전액 입금되어 빠지고, 11-05 발행분이 새로 들어온다
            assertThat((Long) scalar(st, "SELECT count(*) FROM cst.invoice_open_items(DATE '2026-11-30') WHERE invoice_no = 'INV-A'")).isZero();
            assertThat((Long) scalar(st, "SELECT count(*) FROM cst.invoice_open_items(DATE '2026-11-30') WHERE invoice_no = 'INV-F'")).isEqualTo(1L);
        }
    }
}
