package com.atomeng.erp.qar.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.qar.domain.QarCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * qar 스키마 공통 JDBC — 행 조회 · 잠금 · 변환(RPT · EQP · SAL 과 같은 방식). 행은 Map(snake_case), API 뷰는 서비스가 만든다.
 */
@Repository
public class QarJdbc {

    public static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private static final Set<String> TABLES = Set.of("review", "review_round", "review_comment", "autocheck_result",
            "ai_finding", "ai_review_job", "waiver", "checklist_item");

    private final JdbcTemplate jdbc;

    public QarJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Map<String, Object> lock(String table, UUID id) {
        return row(table, id, true).orElseThrow(() -> notFound(table, id));
    }

    public Map<String, Object> get(String table, UUID id) {
        return row(table, id, false).orElseThrow(() -> notFound(table, id));
    }

    public Optional<Map<String, Object>> row(String table, UUID id, boolean forUpdate) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException(table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM qar." + table + " WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : ""), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public static DomainException notFound(String what, Object id) {
        return new DomainException(QarCodes.NOT_FOUND, "QAR 레코드 없음: " + what + " " + id);
    }

    public static String json(Object v) {
        try {
            return v == null ? null : MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode tree(Object pgJson) {
        if (pgJson == null) {
            return null;
        }
        try {
            return MAPPER.readTree(pgJson.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object pgJson) {
        if (pgJson == null) {
            return null;
        }
        try {
            return MAPPER.readValue(pgJson.toString(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Date d(LocalDate x) {
        return x == null ? null : Date.valueOf(x);
    }

    public static Timestamp ts(Instant x) {
        return x == null ? null : Timestamp.from(x);
    }

    public static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof Instant i) {
            return i;
        }
        return OffsetDateTime.parse(o.toString()).toInstant();
    }

    public static UUID uuid(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    public static String str(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v.toString().strip();
    }

    public static int integer(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? 0 : ((Number) v).intValue();
    }

    public static long version(Map<String, Object> row) {
        Object v = row.get("version");
        return v == null ? 0L : ((Number) v).longValue();
    }
}
