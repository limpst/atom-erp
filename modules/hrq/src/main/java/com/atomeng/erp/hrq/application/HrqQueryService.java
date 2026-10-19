package com.atomeng.erp.hrq.application;

import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.api.HrqQueryApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link HrqQueryApi} 구현 — 등급 이력(hrq.grade_history, [valid_from, valid_to)) · 자격(hrq.credential) 조회 */
@Service
class HrqQueryService implements HrqQueryApi {

    private final HrqJdbc jdbc;

    HrqQueryService(HrqJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> gradeOf(UUID employeeId, LocalDate date) {
        return jdbc.gradeAt(employeeId, date);
    }

    @Override
    public List<CredentialView> credentialsOf(UUID employeeId) {
        return jdbc.credentials(employeeId).stream()
                .map(c -> new CredentialView(c.id(), c.typeCode(), c.status(), c.acquiredOn(), c.expiresOn()))
                .toList();
    }
}
