/**
 * EQP 장비 관리 (설계 04 v0.2 / 04-IMPL). 장비 대장 · 검교정(valid_until = next_due_on) · 유효성 판정 단일 진입점
 * (예약 · 반출 · 측정 확정 공통, BR-EQP-001·002·003) · 예약(EXCLUDE 이중 예약 금지) · QR(HMAC) 반출 · 반납 · 드론 비행로그 ·
 * 일일 검교정 스캔({@code eqp.calibration.due-soon} / {@code eqp.calibration.expired}).
 * 다른 업무 모듈에는 {@code core::api} 공개 타입으로만 의존한다(CST 와는 {@code eqp.equipment.returned} 이벤트 계약만).
 */
@org.springframework.modulith.ApplicationModule(displayName = "EQP 장비 관리", allowedDependencies = {"core::api"})
package com.atomeng.erp.eqp;
