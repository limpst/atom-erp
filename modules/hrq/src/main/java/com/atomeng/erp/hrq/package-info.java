/**
 * HRQ 인력·자격 (설계 03 v0.4) — 등록요건 유지·행정처분 방지 컴플라이언스, 타임시트(원가의 유일 원천).
 * 다른 업무 모듈에는 {@code core::api} 로만 의존하고, 다른 모듈은 {@code hrq::api}(이벤트)만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "HRQ 인력·자격", allowedDependencies = {"core::api"})
package com.atomeng.erp.hrq;
