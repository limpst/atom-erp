/**
 * FLD 현장조사 앱 — 서버 측 (설계 05 v0.2 / 05-IMPL). 조사 회차 · 구역(배정 · 잠금 BR-FLD-009) · 결함 이벤트 소싱
 * (defect_event append-only → defect_current 프로젝션, 재구축 · 시점 재현) · 오프라인 동기화 프로토콜(push: clientOpId 멱등 ·
 * HLC LWW · 측정값 수동 충돌 B7.2 / pull: change token · 가시성 지연 창 / 충돌 해결 API) · 측정값 장비 검증(eqp::api) ·
 * 사진 이어 올리기(tus 1.0 core 의미, SHA-256 검증 → core 첨부 WORM · 해시 체인) · 음성 메모 · AI 제안(aip::api).
 * 다른 업무 모듈에는 {@code core::api} · {@code fac::api} · {@code eqp::api} · {@code aip::api} 공개 타입으로만 의존하고,
 * 다른 모듈은 {@code fld::api}(이벤트 · FldSurveyQuery)만 참조한다. Flutter 앱은 이 저장소 범위 밖.
 */
@org.springframework.modulith.ApplicationModule(displayName = "FLD 현장조사",
        allowedDependencies = {"core::api", "fac::api", "eqp::api", "aip::api"})
package com.atomeng.erp.fld;
