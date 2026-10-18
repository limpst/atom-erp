/**
 * FCD 법인카드 관리 · 부정사용 탐지 (설계 12 / 12-IMPL). 3채널 수집(CSV · Excel · API) → 채널 간 중복 제거 →
 * L1(룰셋 YAML, Python 프로토타입과 같은 포맷 · 안전 평가기) · L2/L3(포트 — Python 서비스) → L4 결합 · 등급 →
 * 월 예산 검토 큐 → 케이스(소명 · 판정, 이해충돌) → 정산 · {@code fcd.expense.posted} → CST 원가 · 더존 → {@code cst.voucher.synced}.
 * 다른 업무 모듈에는 {@code core::api}, {@code hrq::api} 공개 타입으로만 의존한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "FCD 법인카드 · 부정사용 탐지",
        allowedDependencies = {"core::api", "hrq::api"})
package com.atomeng.erp.fcd;
