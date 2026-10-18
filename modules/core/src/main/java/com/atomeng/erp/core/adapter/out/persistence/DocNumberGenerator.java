package com.atomeng.erp.core.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 문서 번호 채번 — core.next_doc_no() (동시성 안전, 호출자 트랜잭션 참여) */
@Component
public class DocNumberGenerator {

    private final JdbcTemplate jdbc;

    public DocNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** CL-000001 */
    public String nextClientNo() {
        return jdbc.queryForObject("SELECT core.next_doc_no(?::text, NULL::int, ?::int)", String.class, "CL", 6);
    }

    /** PRJ-2026-0001 */
    public String nextProjectNo(int year) {
        return jdbc.queryForObject("SELECT core.next_doc_no(?::text, ?::int, ?::int)", String.class, "PRJ", year, 4);
    }
}
