package com.atomeng.erp.sal.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.SalCodes;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * sal 스키마 공통 JDBC — 행 조회 · 잠금 · 채번. 견적 · 이력은 불변 트리거가 지키는 append 성격이라 JPA 대신 JDBC(HRQ · EQP 와 같은 방식).
 * 행은 Map(컬럼명 snake_case) 으로 다루고 API 뷰는 서비스가 만든다.
 */
@Repository
public class SalJdbc {

    private final JdbcTemplate jdbc;

    public SalJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static Date d(LocalDate x) {
        return x == null ? null : Date.valueOf(x);
    }

    public static Timestamp ts(Instant x) {
        return x == null ? null : Timestamp.from(x);
    }

    public static Timestamp ts(OffsetDateTime x) {
        return x == null ? null : Timestamp.from(x.toInstant());
    }

    public static LocalDate localDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Date sd) {
            return sd.toLocalDate();
        }
        if (o instanceof LocalDate ld) {
            return ld;
        }
        return LocalDate.parse(o.toString());
    }

    public static UUID uuid(Map<String, Object> row, String col) {
        return (UUID) row.get(col);
    }

    public static String str(Map<String, Object> row, String col) {
        Object o = row.get(col);
        return o == null ? null : o.toString();
    }

    public static BigDecimal dec(Map<String, Object> row, String col) {
        Object o = row.get(col);
        return o == null ? null : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    public static long lng(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    /** core.next_doc_no — OPP-2026-0001 · EST-2026-0001 · CTR-2026-0001 (호출자 트랜잭션 참여, 동시성 안전) */
    public String nextDocNo(String prefix, int year) {
        return jdbc.queryForObject("SELECT core.next_doc_no(?::text, ?::int, 4)", String.class, prefix, year);
    }

    public Optional<Map<String, Object>> find(String table, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sal." + table + " WHERE id = ?", id);
        return rows.stream().findFirst();
    }

    public Map<String, Object> get(String table, UUID id) {
        return find(table, id).orElseThrow(() -> new DomainException(SalCodes.NOT_FOUND, "%s 없음: %s".formatted(table, id)));
    }

    /** SELECT … FOR UPDATE — 같은 행의 상태 전이 직렬화 */
    public Map<String, Object> lock(String table, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sal." + table + " WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new DomainException(SalCodes.NOT_FOUND, "%s 없음: %s".formatted(table, id));
        }
        return rows.get(0);
    }

    /** 영업 그룹(SALES_LEAD 등) — BR-SAL-006 한도 해석용 */
    public List<String> groupsOf(UUID employeeId) {
        return jdbc.queryForList("SELECT group_code FROM sal.sales_group_member WHERE employee_id = ? AND active", String.class,
                employeeId);
    }
}
