/**
 * PTL 고객 포털 (설계 09 v0.3 / 09-IMPL). 고객사(core.client = 포털 토큰 cid) 단위 엄격 격리 — 모든 ptl 행의 customer_id +
 * PostgreSQL RLS(요청 · 이벤트마다 트랜잭션 안 SET LOCAL ROLE ptl_customer + app.customer_id). 시설물 · 점검 일정(fac::api),
 * 납품 보고서 목록 · 워터마크 다운로드(rpt::api, 다운로드 감사), 서비스 요청(→ ptl.service-request.submitted → SAL 리드),
 * 알림함(rpt 납품 · 정정, fac 등급 · 점검 도래 · 경과, sal 견적 · 서명 요청 구독).
 * 다른 업무 모듈에는 {@code core::api} · {@code fac::api} · {@code rpt::api} · {@code sal::api} 공개 타입으로만 의존한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "PTL 고객 포털",
        allowedDependencies = {"core::api", "fac::api", "rpt::api", "sal::api"})
package com.atomeng.erp.ptl;
