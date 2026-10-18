/**
 * SAL 수주 · 영업 (설계 01 v0.3 / 01-IMPL). 기회 파이프라인(A6.1) · 대가 산정 엔진(B7.1, 유효기간 규칙) · 견적 버전/불변/할인 승인
 * (BR-SAL-002·005·006) · 계약(사내 결재 → 고객 서명 → 프로젝트 생성, BR-SAL-008) · 적격심사 시뮬레이션(performance_record 만,
 * BR-SAL-014) · 나라장터 공고 수집(collect_log 구간, BR-SAL-013) · 점검 도래 리드(BR-SAL-007).
 * 다른 업무 모듈에는 {@code core::api} · {@code fac::api} 공개 타입으로만 의존한다(CST 와는 {@code sal.contract.signed} 이벤트 계약만).
 */
@org.springframework.modulith.ApplicationModule(displayName = "SAL 수주·영업", allowedDependencies = {"core::api", "fac::api"})
package com.atomeng.erp.sal;
