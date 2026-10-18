/**
 * RPT 보고서 엔진 (설계 06 v0.2 / 06-IMPL). 보고서 · 리비전(발행 후 불변) · 스냅샷 동결 · 사전검증 · 상태평가/안전등급 엔진
 * (effective-dated rule table) · 섹션/블록 · 외관조사망도 DXF(R12) · HWPX 생성 · PDF 변환 포트(Gotenberg) · anchors.json ·
 * 발행 · 납품 확인 · 정정본(superseded).
 * 다른 업무 모듈에는 {@code core::api} · {@code fac::api}(rpt.report.published 구독 계약) · {@code eqp::api}(검교정 유효성 포트)
 * 공개 타입으로만 의존한다. QAR 등 다른 모듈은 {@code rpt::api}(이벤트 · RptRevisionQuery)만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "RPT 보고서 엔진",
        allowedDependencies = {"core::api", "fac::api", "eqp::api"})
package com.atomeng.erp.rpt;
