package com.atomeng.erp.cst.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 더존 전표 동기화 포트 (FR-CST-013, Step 9). 실제 Adapter(중계 테이블 dz_if.*)는 미구현 — 현재 스텁 */
public interface DouzoneVoucherPort {

    /** @return 더존 전표번호. 실패 시 예외 */
    String syncExpenseVoucher(UUID expenseReportId, List<UUID> lineIds, String periodYm, BigDecimal amountKrw,
                              boolean reversal);
}
