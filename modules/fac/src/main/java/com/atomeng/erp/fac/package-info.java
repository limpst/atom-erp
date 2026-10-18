/**
 * FAC 시설물 마스터 (설계 02 v0.3). 시설물(building)의 정본·안전등급 이력·법정 점검주기 엔진.
 * 다른 업무 모듈에는 {@code core::api} 로만 의존하고, 다른 모듈은 {@code fac::api}(이벤트·DTO)만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "FAC 시설물 마스터", allowedDependencies = {"core::api"})
package com.atomeng.erp.fac;
