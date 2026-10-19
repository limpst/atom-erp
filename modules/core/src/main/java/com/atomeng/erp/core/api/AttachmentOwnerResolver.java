package com.atomeng.erp.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * 첨부 소유자 → 프로젝트 해석 SPI (보안 검토 CORE-01(a) · AIP-03). 첨부를 받는 모듈(FLD · DEM …)이 bean 으로 등록한다.
 * presign 때 ownerModule 의 해석기가 없거나 소유자 · 체인 범위를 프로젝트로 풀지 못하면 첨부를 받지 않는다.
 */
public interface AttachmentOwnerResolver {

    /** 3~4자 대문자 모듈 코드(FLD, DEM …) */
    String ownerModule();

    /** 소유자(ownerType · ownerId) + 해시 체인 범위(chainScope) 가 가리키는 프로젝트. 풀 수 없으면 empty */
    Optional<UUID> projectOf(String ownerType, UUID ownerId, UUID chainScope);

    /** 프로젝트 투입 인원이 아니어도 이 체인에 첨부를 올릴 수 있는 직원인가(예: DEM 활성 감리자). 기본 false */
    default boolean mayAttach(UUID employeeId, String ownerType, UUID ownerId, UUID chainScope) {
        return false;
    }
}
