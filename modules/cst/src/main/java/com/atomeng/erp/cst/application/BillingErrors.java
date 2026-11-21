package com.atomeng.erp.cst.application;

/** CST 청구 · 수금 · 대사 오류 코드 (docs/CST_BILLING_COLLECTION_DESIGN.md). DB 트리거 메시지와 같은 번호를 쓴다. */
public final class BillingErrors {
    private BillingErrors() {}

    public static final String NOT_FOUND = "CST-404-020";
    /** 두 사람 규칙 — 제안자 = 확정 · 반려 · 역분개자 */
    public static final String SAME_PERSON = "CST-403-010";
    public static final String INVOICE_HAS_RECEIPTS = "CST-409-021";
    public static final String INVOICE_NOT_ISSUED = "CST-409-024";
    public static final String OVER_INVOICE = "CST-409-025";
    public static final String OVER_RECEIPT = "CST-409-026";
    /** 제안 상태(PENDING 만 확정 · 반려) */
    public static final String PROPOSAL_STATE = "CST-409-027";
    public static final String PROPOSAL_EXISTS = "CST-409-028";
    public static final String ALREADY_REVERSED = "CST-409-029";
    /** 통장 줄: 이미 확정된 계정을 다시 확정 — 바꾸려면 사유와 함께 재확정(force) */
    public static final String LINE_CONFIRMED = "CST-409-035";
    public static final String INVALID = "CST-422-021";
}
