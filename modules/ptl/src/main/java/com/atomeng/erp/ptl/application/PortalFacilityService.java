package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.ptl.domain.InspectionSeverity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 포트폴리오 · 등급 이력 · 법정점검 일정 (FR-PTL-003·004·005, 09-IMPL Step 5) — fac::api {@link FacQueryApi} 실시간 조회.
 * 고객 범위 = 시설물 소유 고객사(fac.site.owner_client_id) = 토큰 cid. 다른 고객사 시설물은 존재를 숨기고 404 + DENIED 기록.
 */
@Service
public class PortalFacilityService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FacQueryApi fac;
    private final PortalAccessLog accessLog;
    private final Clock clock;

    public PortalFacilityService(FacQueryApi fac, PortalAccessLog accessLog, Clock clock) {
        this.fac = fac;
        this.accessLog = accessLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> buildings(PortalAccess.Customer c) {
        LocalDate today = today();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FacQueryApi.BuildingSummary b : fac.buildingsOwnedBy(c.customerId())) {
            Map<String, Object> v = summary(b);
            fac.openDues(b.buildingId()).stream().min(Comparator.comparing(FacQueryApi.InspectionDue::dueDate))
                    .ifPresent(d -> v.put("nextInspection", due(d, b, today)));
            out.add(v);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> building(PortalAccess.Customer c, UUID buildingId) {
        FacQueryApi.BuildingSummary b = owned(c, buildingId);
        Map<String, Object> v = summary(b);
        v.put("grades", grades(b));
        v.put("inspectionSchedule", schedule(b, today()));
        return v;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> grades(PortalAccess.Customer c, UUID buildingId) {
        return grades(owned(c, buildingId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> schedule(PortalAccess.Customer c, UUID buildingId) {
        return schedule(owned(c, buildingId), today());
    }

    /** 내 시설물 전체 법정점검 일정(도래일 순) — 대시보드 "차기 법정점검 임박" · 캘린더 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scheduleAll(PortalAccess.Customer c) {
        LocalDate today = today();
        List<Map<String, Object>> all = new ArrayList<>();
        for (FacQueryApi.BuildingSummary b : fac.buildingsOwnedBy(c.customerId())) {
            all.addAll(schedule(b, today));
        }
        all.sort(Comparator.comparing(m -> (LocalDate) m.get("dueDate")));
        return all;
    }

    /** 고객 소유 시설물인지(서비스 요청 등 다른 경로에서 재사용) — 아니면 404 + DENIED */
    public FacQueryApi.BuildingSummary owned(PortalAccess.Customer c, UUID buildingId) {
        FacQueryApi.BuildingSummary b = fac.building(buildingId).orElse(null);
        if (b == null || !c.customerId().equals(b.ownerClientId()) || "MERGED".equals(b.status())) {
            accessLog.denied(c, "Building", buildingId, b == null ? "NOT_FOUND" : "NOT_OWNER_OR_MERGED");
            throw new DomainException(PtlErrors.NOT_FOUND, "시설물 없음: " + buildingId);
        }
        return b;
    }

    private List<Map<String, Object>> grades(FacQueryApi.BuildingSummary b) {
        return fac.gradeHistory(b.buildingId()).stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("grade", g.grade());
            m.put("inspectionType", g.inspectionType());
            m.put("performedOn", g.performedOn());
            m.put("reportId", g.reportId());
            m.put("correction", g.correction());
            return m;
        }).toList();
    }

    private List<Map<String, Object>> schedule(FacQueryApi.BuildingSummary b, LocalDate today) {
        return fac.openDues(b.buildingId()).stream().map(d -> due(d, b, today)).toList();
    }

    private static Map<String, Object> due(FacQueryApi.InspectionDue d, FacQueryApi.BuildingSummary b, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buildingId", b.buildingId());
        m.put("buildingName", b.name());
        m.put("inspectionType", d.inspectionType());
        m.put("dueDate", d.dueDate());
        m.put("dDay", InspectionSeverity.dDay(d.dueDate(), today));
        m.put("severity", InspectionSeverity.of(d.dueDate(), today).name());
        m.put("facStatus", d.status());
        return m;
    }

    private static Map<String, Object> summary(FacQueryApi.BuildingSummary b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.buildingId());
        m.put("name", b.name());
        m.put("dongName", b.dongName());
        m.put("roadAddress", b.roadAddress());
        m.put("structure", b.structureNm());
        m.put("grossFloorArea", b.grossFloorArea());
        m.put("floorsAbove", b.floorsAbove());
        m.put("floorsBelow", b.floorsBelow());
        m.put("approvalDate", b.approvalDate());
        m.put("status", b.status());
        m.put("currentGrade", b.currentGrade());
        m.put("gradePerformedOn", b.gradePerformedOn());
        return m;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }
}
