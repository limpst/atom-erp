package com.atomeng.erp.aip.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.domain.AipCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** aip 스키마 공통 JDBC — 행(Map, snake_case) 조회 · 잠금 · JSON 변환. API 뷰는 서비스가 만든다(QAR · RPT 와 같은 방식). */
@Repository
public class AipJdbc {

    public static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private static final Set<String> TABLES = Set.of("job", "suggestion", "feedback", "label_task", "model_version",
            "dataset_version", "deployment", "prompt_version");

    private final JdbcTemplate jdbc;

    public AipJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Optional<Map<String, Object>> row(String table, UUID id, boolean forUpdate) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException(table);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM aip." + table + " WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : ""), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> get(String table, UUID id) {
        return row(table, id, false).orElseThrow(() -> notFound(table, id));
    }

    public Map<String, Object> lock(String table, UUID id) {
        return row(table, id, true).orElseThrow(() -> notFound(table, id));
    }

    public static DomainException notFound(String what, Object id) {
        return new DomainException(AipCodes.NOT_FOUND, what + " 없음: " + id);
    }

    /** 응답용 행 정리: jsonb/json(PGobject) → JsonNode, SQL 배열 → List (드라이버 객체를 그대로 직렬화하지 않도록) */
    public static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, cleanValue(v)));
        return out;
    }

    public static List<Map<String, Object>> cleanAll(List<Map<String, Object>> rows) {
        return rows.stream().map(AipJdbc::clean).toList();
    }

    private static Object cleanValue(Object v) {
        if (v != null && "org.postgresql.util.PGobject".equals(v.getClass().getName())) {   // 드라이버 컴파일 의존 없이
            String text = v.toString();
            try {
                return text == null ? null : MAPPER.readTree(text);
            } catch (Exception notJson) {
                return text;
            }
        }
        if (v instanceof java.sql.Array a) {
            try {
                Object[] arr = (Object[]) a.getArray();
                return java.util.Arrays.stream(arr).map(AipJdbc::cleanValue).toList();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("배열 변환 실패", e);
            }
        }
        return v;
    }

    public static String json(Object o) {
        try {
            return o instanceof String s ? s : MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 직렬화 실패", e);
        }
    }

    public static JsonNode tree(Object pgJson) {
        if (pgJson == null) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.readTree(pgJson.toString());
        } catch (Exception e) {
            throw new IllegalStateException("JSON 파싱 실패", e);
        }
    }

    public static UUID uuid(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    public static String str(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? null : v.toString();
    }

    public static int integer(Map<String, Object> row, String col) {
        Object v = row.get(col);
        return v == null ? 0 : ((Number) v).intValue();
    }

    public static Instant instant(Map<String, Object> row, String col) {
        Object v = row.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return Instant.parse(v.toString());
    }
}
