package com.atomeng.erp.cst.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.domain.shared.PeriodYm;

/** CST 오류 코드 (설계 08 · 08-IMPL) */
public final class CstErrors {
    private CstErrors() {}

    public static final String NOT_FOUND = "CST-404-001";
    /** 원장 append-only (DB 트리거 메시지와 동일 코드) */
    public static final String APPEND_ONLY = "CST-409-001";
    public static final String PERIOD_CLOSED = "CST-409-002";
    /** 원 원장 행 미도착(순서 역전) - 예외로 트랜잭션을 롤백해 outbox 재시도(지수 백오프)에 맡긴다 (BR-CST-015 ⑤, TC-CST-14) */
    public static final String ORIGINAL_NOT_POSTED = "CST-409-003";
    public static final String PERIOD_STATE = "CST-409-004";
    public static final String INVALID = "CST-422-001";
    public static final String RATE_NOT_FOUND = "CST-422-004";
    public static final String PROGRESS_LIMIT = "CST-422-013";

    public static PeriodYm period(String ym) {
        try {
            return PeriodYm.parse(ym);
        } catch (IllegalArgumentException e) {
            throw new DomainException(INVALID, e.getMessage());
        }
    }
}
