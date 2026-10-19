package com.atomeng.erp.fld.application;

import com.atomeng.erp.core.api.AttachmentOwnerResolver;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FLD 첨부 소유자 해석 (보안 검토 CORE-01(a)) — 해시 체인 범위 = 조사 회차(roundId) → 그 회차의 프로젝트.
 * ownerId 가 이미 등록된 FLD 사진이면 같은 회차의 사진이어야 한다(다른 회차 사진을 소유자로 끌어오기 금지).
 */
@Component
class FldAttachmentOwnerResolver implements AttachmentOwnerResolver {

    private final FldJdbc fld;

    FldAttachmentOwnerResolver(FldJdbc fld) {
        this.fld = fld;
    }

    @Override
    public String ownerModule() {
        return "FLD";
    }

    @Override
    public Optional<UUID> projectOf(String ownerType, UUID ownerId, UUID chainScope) {
        if (chainScope == null) {
            return Optional.empty();
        }
        Optional<UUID> project = fld.jdbc().query("SELECT project_id FROM fld.survey_round WHERE id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), chainScope).stream().findFirst();
        if (project.isEmpty() || ownerId == null) {
            return project;
        }
        Optional<UUID> photoRound = fld.jdbc().query("SELECT round_id FROM fld.photo WHERE id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), ownerId).stream().findFirst();
        return photoRound.isPresent() && !photoRound.get().equals(chainScope) ? Optional.empty() : project;
    }
}
