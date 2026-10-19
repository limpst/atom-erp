package com.atomeng.erp.dem.application;

import com.atomeng.erp.core.api.AttachmentOwnerResolver;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * DEM 첨부 소유자 해석 (보안 검토 CORE-01(a)) — 해시 체인 범위 = 업무 패키지(workPackageId) → 그 패키지의 프로젝트.
 * 투입 인원이 아니어도 그 패키지의 활성 감리자는 증거 사진을 올릴 수 있다(일지 작성 권한과 같은 기준).
 */
@Component
class DemAttachmentOwnerResolver implements AttachmentOwnerResolver {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DemJdbc dem;
    private final SupervisorService supervisors;
    private final Clock clock;

    DemAttachmentOwnerResolver(DemJdbc dem, SupervisorService supervisors, Clock clock) {
        this.dem = dem;
        this.supervisors = supervisors;
        this.clock = clock;
    }

    @Override
    public String ownerModule() {
        return "DEM";
    }

    @Override
    public Optional<UUID> projectOf(String ownerType, UUID ownerId, UUID chainScope) {
        if (chainScope == null) {
            return Optional.empty();
        }
        return dem.jdbc().query("SELECT project_id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getObject(1, UUID.class), chainScope).stream().findFirst();
    }

    @Override
    public boolean mayAttach(UUID employeeId, String ownerType, UUID ownerId, UUID chainScope) {
        return supervisors.isActiveSupervisor(chainScope, employeeId, LocalDate.now(clock.withZone(SEOUL)));
    }
}
