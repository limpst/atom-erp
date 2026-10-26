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
 * 시설물(수기 등록 · 미연결)은 판단 근거가 없어 통과시킨다 — 보고서 생성({@link #requireSameClient}) 한정.
 * <p>보안 검토 2026-10-19 RPT-02b(정책 결정): 스냅샷 동결({@link #requireLinkedToProject})은 실패하는 쪽으로 닫힌다 —
 * FAC 에 없는 건물(422 FACILITY_NOT_IN_FAC), 소유 고객사가 없는 건물(422 FACILITY_OWNER_UNKNOWN),
 * 소유 고객사 ≠ 프로젝트 발주처(422 FACILITY_NOT_OF_PROJECT_CLIENT) 모두 거부. 프로젝트 ↔ 시설물 연결의 정본은
 * fac::api 소유 고객사 = core 프로젝트 발주 고객사(cst.project_facility 는 채우는 흐름이 없어 쓰지 않는다).
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

    /**
     * RPT-02b: 스냅샷 동 — FAC 에 있고, 소유 고객사가 정해져 있고, 그 고객사가 프로젝트 발주처여야 한다.
     * buildingId 가 없는 동(FAC 미연결 — 발행해도 FAC 등급 이력에 남지 않음)은 검사 대상이 아니다.
     */
    public void requireLinkedToProject(UUID projectId, UUID buildingId) {
        if (buildingId == null) {
            return;
        }
        FacQueryApi facApi = fac.getIfAvailable();
        ProjectScopeResolver resolver = projects.getIfAvailable();
        if (facApi == null || resolver == null) {
            throw new IllegalStateException("fac::api · 프로젝트 범위 해석기 없음 — 스냅샷 시설물 확인 불가");
        }
        FacQueryApi.BuildingSummary b = facApi.building(buildingId).orElseThrow(() -> new DomainException(
                RptCodes.INVALID, "FAC 에 등록되지 않은 시설물입니다: " + buildingId).with("reason", "FACILITY_NOT_IN_FAC")
                .with("buildingId", buildingId));
        if (b.ownerClientId() == null) {
            throw new DomainException(RptCodes.INVALID, "소유 고객사가 정해지지 않은 시설물은 스냅샷에 넣을 수 없습니다: "
                    + buildingId).with("reason", "FACILITY_OWNER_UNKNOWN").with("buildingId", buildingId);
        }
        Optional<UUID> client = resolver.clientOf(projectId);
        if (client.isEmpty() || !b.ownerClientId().equals(client.get())) {
            throw new DomainException(RptCodes.INVALID, "보고서 프로젝트 발주처의 시설물이 아닙니다: " + buildingId)
                    .with("reason", "FACILITY_NOT_OF_PROJECT_CLIENT").with("buildingId", buildingId);
        }
    }

    /** RPT-02b 발행 대체 경로(스냅샷에 FAC 동이 없을 때 보고서 시설물): 프로젝트에 연결된 시설물인가(예외 없이) */
    public boolean isLinkedToProject(UUID projectId, UUID buildingId) {
        try {
            requireLinkedToProject(projectId, buildingId);
            return buildingId != null;
        } catch (DomainException e) {
            return false;
        }
    }
}
