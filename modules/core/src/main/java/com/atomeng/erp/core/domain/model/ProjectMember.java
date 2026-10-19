package com.atomeng.erp.core.domain.model;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * 프로젝트 투입(core.project_member) — 행 수준 권한(PROJECT 범위)의 원천.
 * 역할: RESP(책임기술자 = PM) · PART(참여) · QA · SUPPORT. 투입 기간 [fromDate, toDate] 양 끝 포함.
 */
public record ProjectMember(UUID projectId, UUID employeeId, String roleInProject, LocalDate fromDate,
                            LocalDate toDate) {

    public static final Set<String> ROLES = Set.of("RESP", "PART", "QA", "SUPPORT");

    public ProjectMember {
        if (projectId == null || employeeId == null) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "projectId · employeeId 필수");
        }
        if (roleInProject == null || !ROLES.contains(roleInProject)) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "투입 역할은 RESP · PART · QA · SUPPORT 중 하나: "
                    + roleInProject);
        }
        if (fromDate == null || toDate == null) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "투입 시작일 · 종료일 필수");
        }
        if (toDate.isBefore(fromDate)) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "투입 종료일은 시작일 이후여야 합니다");
        }
    }

    /** 책임기술자(RESP) 투입이면 프로젝트 PM 으로도 기록한다 */
    public boolean isResponsible() {
        return "RESP".equals(roleInProject);
    }
}
