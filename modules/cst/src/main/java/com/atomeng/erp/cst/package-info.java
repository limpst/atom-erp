/**
 * CST 원가·수익성 (설계 08 v0.4). 원가 원장(append-only·역분개), 인건비 전기(hrq.timesheet.confirmed 유일 원천),
 * 정정 역분개·재전기(hrq.timesheet.corrected), HRQ 월 마감 대사(hrq.manday.closed — 원장 재기록 금지), EVM.
 * 다른 업무 모듈에는 {@code core::api}, {@code hrq::api}, {@code fac::api} 공개 타입으로만 의존한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "CST 원가·수익성",
        allowedDependencies = {"core::api", "hrq::api", "fac::api"})
package com.atomeng.erp.cst;
