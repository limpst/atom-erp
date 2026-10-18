package com.atomeng.erp.fcd.adapter.out.aip;

import com.atomeng.erp.fcd.application.port.FraudServicePort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 기본(스텁) 어댑터 — 맥락 없음 · L3 DISABLED. Python 서비스 미연결 환경 · 테스트용 */
public class StubFraudServiceAdapter implements FraudServicePort {

    @Override
    public Map<UUID, Map<String, Object>> context(List<TxnRef> txns) {
        return new HashMap<>();
    }

    @Override
    public Map<UUID, L3Result> anomaly(List<TxnRef> txns, Map<UUID, Map<String, Object>> txFeatures) {
        Map<UUID, L3Result> out = new HashMap<>();
        txns.forEach(t -> out.put(t.txnId(), L3Result.DISABLED));
        return out;
    }
}
