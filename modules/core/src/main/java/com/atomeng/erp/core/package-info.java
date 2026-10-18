/**
 * CORE 공통 업무 모듈 — 다른 업무 모듈에 의존하지 않는다(설계 4장 의존성 그래프의 바닥).
 * 다른 모듈은 {@code core::api} 명명 인터페이스만 참조할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "CORE 공통", allowedDependencies = {})
package com.atomeng.erp.core;
