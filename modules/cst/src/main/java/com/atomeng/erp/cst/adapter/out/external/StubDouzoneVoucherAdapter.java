package com.atomeng.erp.cst.adapter.out.external;

import com.atomeng.erp.cst.application.port.DouzoneVoucherPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 더존 전표 스텁 — 외부 호출 없이 결정적(합성) 전표번호 {@code DZ-STUB-YYYYMM-xxxxxxxx} 를 돌려준다.
 * 더존 제품·중계 스펙 확정(08-IMPL Step 0·9) 후 실제 Adapter 로 교체한다.
 */
@Component
public class StubDouzoneVoucherAdapter implements DouzoneVoucherPort {

    @Override
    public String syncExpenseVoucher(UUID expenseReportId, List<UUID> lineIds, String periodYm, BigDecimal amountKrw,
                                     boolean reversal) {
        String seed = expenseReportId + ":" + lineIds + ":" + reversal;
        String h = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
        return "DZ-STUB-" + periodYm + "-" + h + (reversal ? "-R" : "");
    }
}
