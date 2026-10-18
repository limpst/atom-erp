package com.atomeng.erp.hrq.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * HRQ 공개 조회 API (08-IMPL 0.5 계약 {@code HrqQueryApi.gradeOf(empId, date)}).
 * CST 는 원장 전기 시 근무일 기준 등급으로 표준단가를 고른다(등급 승급일 기준 단가 분할 — 설계 08 A8).
 */
public interface HrqQueryApi {

    /** 근무일 시점 기술자 등급(JUNIOR/INTER/SENIOR/MASTER). 등급 이력이 없으면 empty */
    Optional<String> gradeOf(UUID employeeId, LocalDate date);
}
