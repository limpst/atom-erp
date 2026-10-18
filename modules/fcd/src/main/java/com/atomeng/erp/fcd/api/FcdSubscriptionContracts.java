package com.atomeng.erp.fcd.api;

import java.util.List;
import java.util.UUID;

/**
 * FCD 구독 계약 — cst::api 에 의존하지 않도록 payload 를 FCD 쪽에 고정(모듈 경계: core::api · hrq::api 만 허용).
 * CST 발행 레코드(CstEvents.VoucherSynced)와의 호환은 앱 계약 테스트가 확인한다.
 */
public final class FcdSubscriptionContracts {

    private FcdSubscriptionContracts() {}

    /** cst.voucher.synced — status SYNCED | FAILED, kind POSTED | REVERSED */
    public record CstVoucherSynced(UUID expenseReportId, List<UUID> lineIds, String douzoneDocNo, String periodYm,
                                   String status, String kind) {
        public static final String TYPE = "cst.voucher.synced";
    }
}
