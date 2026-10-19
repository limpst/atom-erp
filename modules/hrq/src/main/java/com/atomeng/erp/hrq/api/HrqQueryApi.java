package com.atomeng.erp.hrq.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HRQ 공개 조회 API (08-IMPL 0.5 계약 {@code HrqQueryApi.gradeOf(empId, date)} · 10-IMPL 0.3 감리자 자격 검증).
 * CST 는 원장 전기 시 근무일 기준 등급으로 표준단가를 고른다(등급 승급일 기준 단가 분할 — 설계 08 A8).
 * DEM 은 감리자 지정 때 자격 보유 · 유효성(BR-DEM-002)을 확인한다.
 */
public interface HrqQueryApi {

    /** 근무일 시점 기술자 등급(JUNIOR/INTER/SENIOR/MASTER). 등급 이력이 없으면 empty */
    Optional<String> gradeOf(UUID employeeId, LocalDate date);

    /** 직원의 자격 기록(모든 상태, 최근 취득 순). 소비자: DEM 감리자 자격 검증 */
    List<CredentialView> credentialsOf(UUID employeeId);

    /** 자격 1건 읽기 모델. status = PENDING · VALID · EXPIRING · EXPIRED · REJECTED · SUPERSEDED · REVOKED */
    record CredentialView(UUID credentialId, String typeCode, String status, LocalDate acquiredOn,
                          LocalDate expiresOn) {

        /** 승인된(VALID · EXPIRING) 자격이고 기준일에 만료 전(만료일 포함)인가 */
        public boolean validOn(LocalDate date) {
            return ("VALID".equals(status) || "EXPIRING".equals(status))
                    && !acquiredOn.isAfter(date) && (expiresOn == null || !expiresOn.isBefore(date));
        }
    }
}
