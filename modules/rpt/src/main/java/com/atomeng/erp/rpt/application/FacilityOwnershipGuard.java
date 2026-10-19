package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.rpt.domain.RptCodes;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 보안 검토 2026-10-19 SEC-RPT-04: 보고서 시설물(facilityId) · 스냅샷 동(buildingId)이 다른 고객사 시설물이면 거부.
 * 이전에는 임의 buildingId 를 동결해 발행하면 rpt.report.published 로 다른 고객사 건물에 등급(D/E 사용제한 포함)이 기록됐다.
 * 판단 근거는 fac::api 소유 고객사(fac.site.owner_client_id) ↔ 프로젝트 발주 고객사. 소유 고객사가 아직 정해지지 않은
 * 시설물(수기 등록 · 미연결)은 판단 근거가 없어 통과시킨다(정책 결정 대상 — 보고서 참조).
 */
@Component
public class FacilityOwnershipGuard {

    private final ObjectProvider<FacQueryApi> fac;
    private final ObjectProvider<ProjectScopeResolver> projects;

    public FacilityOwnershipGuard(ObjectProvider<FacQueryApi> fac, ObjectProvider<ProjectScopeResolver> projects) {
        this.fac = fac;
        this.projects = projects;
    }

    public void requireSameClient(UUID projectId, UUID buildingId) {
        if (buildingId == null) {
            return;
        }
        FacQueryApi facApi = fac.getIfAvailable();
        ProjectScopeResolver resolver = projects.getIfAvailable();
        if (facApi == null || resolver == null) {
            return;
        }
        Optional<UUID> owner = facApi.building(buildingId).map(FacQueryApi.BuildingSummary::ownerClientId);
        Optional<UUID> client = resolver.clientOf(projectId);
        if (owner.isPresent() && client.isPresent() && !owner.get().equals(client.get())) {
            throw new DomainException(RptCodes.INVALID, "보고서 프로젝트 발주처의 시설물이 아닙니다: " + buildingId)
                    .with("reason", "FACILITY_NOT_OF_PROJECT_CLIENT");
        }
    }
}
