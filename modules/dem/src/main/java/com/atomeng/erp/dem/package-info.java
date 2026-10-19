/**
 * DEM 해체공사 · 구조감리 (설계 10 v0.3 / 10-IMPL). DEM 프로젝트(A6.1) · 업무 패키지 · 감리자 지정(HRQ 자격 · 동시 감리 한도 ·
 * 책임감리자 기간 EXCLUDE) · 공정별 점검표 템플릿 · 감리일지(오프라인 clientOpId 동기화 · 자동 잠금 · Amendment) ·
 * 증거 사진(core 첨부 + 해시 재검증) · 도서 리비전(발행 불변 트리거) · Transmittal · 검토의견서(BR-DEM-009) ·
 * 지자체 제출 · 인접건축물 사전/사후 비교(B7.2 헝가리안 매칭).
 * 다른 업무 모듈에는 {@code core::api} · {@code fac::api}(dem.demolition.completed 구독 계약) · {@code hrq::api}(자격 조회 ·
 * 자격 만료/퇴사 이벤트) 공개 타입으로만 의존한다. 다른 모듈은 {@code dem::api}(이벤트)만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "DEM 해체공사 · 구조감리",
        allowedDependencies = {"core::api", "fac::api", "hrq::api"})
package com.atomeng.erp.dem;
