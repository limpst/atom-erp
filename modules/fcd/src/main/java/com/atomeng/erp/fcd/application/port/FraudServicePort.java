package com.atomeng.erp.fcd.application.port;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2 맥락 · L3 이상점수 포트 — Python 서비스(atomeng_aip.fraud, 설계 B7.5.5 /v1/fraud/score, 800ms 타임아웃)를 호출한다.
 * 운영 어댑터: {@code HttpFraudServiceAdapter}(atom.fcd.aip.base-url 설정 시). 기본 · 테스트: 스텁(맥락 없음 · L3 DISABLED).
 * <p>맥락이 없으면 L2 규칙은 '판정 보류'(위험 가산 없음) — 12-IMPL 리스크 표 "데이터 없음은 판정 보류".
 */
public interface FraudServicePort {

    /** 채점 대상 거래(원천 값만 — 임직원 실명 · PAN 없음) */
    record TxnRef(UUID txnId, UUID employeeId, UUID cardId, LocalDateTime approvedAt, String merchantName,
                  UUID merchantId, double amount) {}

    /** L2 맥락(ctx.* — 휴가 · 일정 · 출장 · 사전결재 · 정산 입력 등). 키는 룰셋 YAML 의 ctx 네임스페이스 이름 그대로 */
    Map<UUID, Map<String, Object>> context(List<TxnRef> txns);

    /** L3 결과 */
    record L3Result(Map<String, Object> l3, String status, String modelVersion) {
        public static final L3Result DISABLED = new L3Result(Map.of(), "DISABLED", null);
    }

    /** L3 이상점수(l3.* — s3 · rz · top …). tx 특징을 함께 보낸다(Python 서비스가 L1 특징을 재계산하지 않도록) */
    Map<UUID, L3Result> anomaly(List<TxnRef> txns, Map<UUID, Map<String, Object>> txFeatures);
}
