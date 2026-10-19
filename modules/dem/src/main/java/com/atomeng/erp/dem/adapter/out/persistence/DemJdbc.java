package com.atomeng.erp.dem.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.dem.application.DemErrors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * dem 스키마 공통 JDBC 도우미 — 채번 카운터 · 날짜/JSON 변환 · 단건 조회(없으면 404).
 * 불변 · append-only 테이블이 많아 JPA dirty checking 대신 JDBC (HRQ · EQP · RPT 와 같은 방식).
 */
@Repository
public class DemJdbc {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public DemJdbc(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
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

    public static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date v = rs.getDate(col);
        return v == null ? null : v.toLocalDate();
    }

    public static LocalDate ld(Object v) {
        return v == null ? null : ((Date) v).toLocalDate();
    }

    public static Instant inst(Object v) {
        return v == null ? null : ((Timestamp) v).toInstant();
    }

    /** uuid[] 리터럴 — ?::uuid[] 바인딩용 */
    public static String uuidArray(Collection<UUID> ids) {
        return ids == null ? "{}" : ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    public String json(Object o) {
        try {
            return o == null ? null : om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new DomainException(DemErrors.INVALID, "JSON 직렬화 오류");
        }
    }

    public JsonNode tree(String json) {
        try {
            return json == null ? null : om.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 JSON 오류", e);
        }
    }

    /** 단건 행(없으면 404 DEM-404-001) */
    public Map<String, Object> one(String what, String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new DomainException(DemErrors.NOT_FOUND, what + " 없음");
        }
        return rows.get(0);
    }

    /** 채번: {prefix}-{year}-{0000} (INSERT … ON CONFLICT 카운터, max()+1 금지) */
    public String nextCode(String prefix, int year) {
        Integer seq = jdbc.queryForObject("""
                INSERT INTO dem.code_sequence(prefix, year, last_seq) VALUES (?, ?, 1)
                ON CONFLICT (prefix, year) DO UPDATE SET last_seq = dem.code_sequence.last_seq + 1
                RETURNING last_seq""", Integer.class, prefix, year);
        return "%s-%d-%04d".formatted(prefix, year, seq);
    }
}
