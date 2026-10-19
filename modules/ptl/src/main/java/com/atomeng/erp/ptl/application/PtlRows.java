package com.atomeng.erp.ptl.application;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/** JDBC 행 → API 값 정규화(Timestamp → Instant, Date → LocalDate, char 공백 제거) */
final class PtlRows {

    private PtlRows() {}

    static Object value(Object v) {
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        return v;
    }

    static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, value(v)));
        return m;
    }
}
