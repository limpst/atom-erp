package com.atomeng.erp.fac.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.adapter.out.persistence.SiteRepository;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.domain.model.Building;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import com.atomeng.erp.fac.domain.model.Site;
import com.atomeng.erp.fac.application.port.RegisterPorts.AddressPort;
import com.atomeng.erp.fac.application.port.RegisterPorts.BuildingRegisterPort;
import com.atomeng.erp.fac.application.port.RegisterPorts.ExternalUnavailable;
import com.atomeng.erp.fac.domain.model.FacTypes.DataSource;
import com.atomeng.erp.fac.domain.register.RegisterModels.BuildingCandidate;
import com.atomeng.erp.fac.domain.register.RegisterModels.NormalizedAddress;
import com.atomeng.erp.fac.domain.register.RegisterModels.RegisterLookupResult;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시설물 등록·조회·수정·삭제·분류 확정 (02-IMPL Step 4, 6 일부) */
@Service
public class BuildingService {

    public static final String NOT_FOUND = "FAC-404-001";
    public static final String DUPLICATE = "FAC-409-001";
    public static final String INVALID = "FAC-422-001";

    /**
     * @param lookupAddress 주소가 오면 도로명주소 → PNU → 건축물대장 자동 입력(Step 3). 장애 시 수기 모드(TC-FAC-002)
     * @param mgmBldrgstPk  표제부(동) 선택 키 — 다동이면 필수
     */
    public record Register(UUID siteId, String siteName, UUID ownerClientId, Building.Spec spec, boolean force,
                           String forceReason, String lookupAddress, String mgmBldrgstPk) {}

    /** 등록 마법사용 조회 결과 (GET /register-lookup) */
    public record LookupView(NormalizedAddress address, RegisterLookupResult register, String failureReason) {}

    private record Enriched(Building.Spec spec, BuildingCandidate picked, String siteName, String failure) {}

    public record Candidate(UUID id, String name, Double nameSimilarity, String matchedBy) {}

    private final BuildingRepository buildings;
    private final SiteRepository sites;
    private final FacJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CycleRecomputeService cycle;
    private final Clock clock;
    private final AddressPort address;
    private final BuildingRegisterPort register;
    private final LocationNodeService nodes;

    public BuildingService(BuildingRepository buildings, SiteRepository sites, FacJdbc jdbc, OutboxPublisher outbox,
                           AuditLog audit, CycleRecomputeService cycle, Clock clock, AddressPort address,
                           BuildingRegisterPort register, LocationNodeService nodes) {
        this.address = address;
        this.register = register;
        this.nodes = nodes;
        this.buildings = buildings;
        this.sites = sites;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.cycle = cycle;
        this.clock = clock;
    }

    // 등록 — 중복 후보가 있으면 409 FAC-409-001 + candidates (TC-FAC-001). force=true 는 사유 필수·감사
    /** 주소 → 정규화·PNU → 건축물대장. 외부 장애는 예외 대신 failureReason (수기 모드로 계속) */
    public LookupView lookup(String rawAddress) {
        try {
            var addr = address.normalize(rawAddress);
            if (addr.isEmpty()) {
                return new LookupView(null, null, "ADDRESS_NOT_FOUND");
            }
            var reg = register.lookup(addr.get().pnu());
            return new LookupView(addr.get(), reg, reg.available() ? null : reg.failureReason());
        } catch (ExternalUnavailable e) {
            return new LookupView(null, null, e.reason());
        } catch (RuntimeException e) { // 4xx 등 예기치 못한 외부 오류도 수기 모드
            return new LookupView(null, null, "API_ERROR");
        }
    }

    private Enriched enrich(Register cmd) {
        var s = cmd.spec();
        if (cmd.lookupAddress() == null || cmd.lookupAddress().isBlank()) {
            return new Enriched(s, null, cmd.siteName(), null);
        }
        LookupView v = lookup(cmd.lookupAddress());
        if (v.address() == null) {
            return new Enriched(manual(s), null, cmd.siteName(), v.failureReason());
        }
        var a = v.address();
        Building.Spec withAddr = new Building.Spec(s.name(), s.dongName(), a.pnu().value(),
                or(s.roadAddress(), a.roadAddress()), or(s.jibunAddress(), a.jibunAddress()), s.mainPurposeNm(),
                s.structureNm(), s.grossFloorArea(), s.floorsAbove(), s.floorsBelow(), s.approvalDate(),
                DataSource.MANUAL);
        if (!v.register().available()) {
            return new Enriched(withAddr, null, cmd.siteName(), v.register().failureReason());
        }
        var picked = v.register().pick(cmd.mgmBldrgstPk(), s.dongName());
        if (picked.isEmpty()) {
            throw new DomainException(INVALID, "표제부(동)를 선택하세요(mgmBldrgstPk)").with("registerCandidates",
                    v.register().titles().stream().map(t -> Map.of("mgmBldrgstPk", String.valueOf(t.mgmBldrgstPk()),
                            "dongName", t.dongName(), "buildingName", t.buildingName())).toList());
        }
        var t = picked.get();
        Building.Spec filled = new Building.Spec(or(s.name(), or(blankToNull(t.buildingName()), a.buildingName())),
                or(s.dongName(), t.dongName()), a.pnu().value(), withAddr.roadAddress(), withAddr.jibunAddress(),
                or(s.mainPurposeNm(), t.mainPurposeNm()), or(s.structureNm(), t.structureNm()),
                or(s.grossFloorArea(), t.grossFloorArea()), or(s.floorsAbove(), t.floorsAbove()),
                or(s.floorsBelow(), t.floorsBelow()), or(s.approvalDate(), t.approvalDate()), DataSource.REGISTER);
        return new Enriched(filled, t, or(cmd.siteName(), v.register().siteName()), null);
    }

    private static Building.Spec manual(Building.Spec s) {
        return new Building.Spec(s.name(), s.dongName(), s.pnu(), s.roadAddress(), s.jibunAddress(), s.mainPurposeNm(),
                s.structureNm(), s.grossFloorArea(), s.floorsAbove(), s.floorsBelow(), s.approvalDate(),
                DataSource.MANUAL);
    }

    private static <T> T or(T a, T b) {
        return a != null ? a : b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Transactional
    public Building register(Register original) {
        Enriched en = enrich(original);
        var s = en.spec();
        if (s.name() == null || s.name().isBlank()) {
            throw new DomainException(INVALID, "건물명(name)이 필요합니다");
        }
        Register cmd = new Register(original.siteId(), en.siteName(), original.ownerClientId(), s, original.force(),
                original.forceReason(), original.lookupAddress(), original.mgmBldrgstPk());
        String dong = s.dongName() == null ? "" : s.dongName().trim();
        List<Candidate> dupes = buildings.findDuplicateCandidates(s.pnu(), dong, s.name(), s.roadAddress()).stream()
                .map(c -> new Candidate(c.getId(), c.getName(), c.getNameSimilarity(), c.getMatchedBy())).toList();
        boolean pnuDong = dupes.stream().anyMatch(c -> "PNU_DONG".equals(c.matchedBy()));
        if (!dupes.isEmpty() && (!cmd.force() || pnuDong)) {
            // PNU+동 완전 일치는 유니크 인덱스(BR-FAC-002) 대상이라 force 로도 우회 불가
            throw new DomainException(DUPLICATE, "중복 시설물 후보가 존재합니다").with("candidates", dupes);
        }
        if (!dupes.isEmpty() && (cmd.forceReason() == null || cmd.forceReason().isBlank())) {
            throw new DomainException(INVALID, "force 등록에는 사유(forceReason)가 필요합니다");
        }
        UUID siteId = cmd.siteId();
        if (siteId == null) { // BR-FAC-001
            Site site = sites.saveAndFlush(Site.autoFor(cmd.siteName() != null ? cmd.siteName() : s.name(),
                    cmd.ownerClientId()));
            siteId = site.getId();
        } else if (!sites.existsById(siteId)) {
            throw new DomainException(INVALID, "존재하지 않는 site: " + siteId);
        }
        Building b = Building.register(siteId, new Building.Spec(s.name(), dong, s.pnu(), s.roadAddress(),
                s.jibunAddress(), s.mainPurposeNm(), s.structureNm(), s.grossFloorArea(), s.floorsAbove(),
                s.floorsBelow(), s.approvalDate(), s.dataSource()));
        if (en.picked() != null) {
            b.attachRegisterSnapshot(en.picked().snapshotJson(), clock.instant());
            b.verify(); // 대장 조회 성공 = VERIFIED (A6.1). 수기·장애는 DRAFT 유지
        }
        buildings.saveAndFlush(b);
        nodes.createFloors(b.getId(), b.getFloorsBelow(), b.getFloorsAbove()); // 층수 → FLOOR 노드
        outbox.publish(new FacEvents.BuildingRegistered(b.getId(), siteId, b.getPnu(), b.getName(),
                cmd.ownerClientId()));
        Map<String, Object> after = snapshot(b);
        after.put("dataSource", b.getDataSource().name());
        if (en.failure() != null) {
            after.put("registerFailure", en.failure()); // TC-FAC-002: 수기 모드 전환 사유
        }
        if (!dupes.isEmpty()) {
            after.put("forceReason", cmd.forceReason());
            after.put("candidates", dupes.stream().map(Candidate::id).toList());
        }
        audit.append("FAC", AuditLog.Action.CREATE, "Building", b.getId(), null, after);
        return b;
    }

    @Transactional(readOnly = true)
    public Building get(UUID id) {
        return buildings.findById(id).orElseThrow(() -> new DomainException(NOT_FOUND, "시설물 없음: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Building> search(String q, Pageable pageable) {
        return buildings.search(q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional
    public Building change(UUID id, long expectedVersion, Building.Spec spec) {
        Building b = get(id);
        IfMatch.check(expectedVersion, b.getVersion());
        var before = snapshot(b);
        b.change(spec);
        buildings.saveAndFlush(b);
        audit.append("FAC", AuditLog.Action.UPDATE, "Building", id, before, snapshot(b));
        if (spec.approvalDate() != null) {
            cycle.recompute(id);
        }
        return b;
    }

    /** 논리 삭제 (SYSADMIN). 등급 이력은 영구 보존(NFR-FAC-05) */
    @Transactional
    public void delete(UUID id, long expectedVersion) {
        Building b = get(id);
        IfMatch.check(expectedVersion, b.getVersion());
        var before = snapshot(b);
        b.softDelete(clock);
        buildings.saveAndFlush(b);
        audit.append("FAC", AuditLog.Action.DELETE, "Building", id, before, null);
    }

    /** 법적 분류 확정 (BR-FAC-003: 근거 필수) → 상태 CLASSIFIED → 도래 재계산 */
    @Transactional
    public Building confirmClassification(UUID id, SitbeopClass cls, boolean bmaTarget, List<UUID> ruleIds,
                                          Map<String, Object> evidence, UUID actor) {
        if (evidence == null || evidence.isEmpty()) {
            throw new DomainException(INVALID, "분류 확정에는 근거(evidence)가 필요합니다(BR-FAC-003)");
        }
        Building b = get(id);
        b.classify();
        buildings.saveAndFlush(b);
        jdbc.confirmClassification(id, cls, bmaTarget, ruleIds, evidence, actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sitbeopClass", cls.name());
        after.put("bmaTarget", bmaTarget);
        after.put("evidence", evidence);
        audit.append("FAC", AuditLog.Action.APPROVE, "BuildingClassification", id, null, after);
        cycle.recompute(id);
        return b;
    }

    private static Map<String, Object> snapshot(Building b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.getName());
        m.put("dongName", b.getDongName());
        m.put("pnu", b.getPnu());
        m.put("roadAddress", b.getRoadAddress());
        m.put("status", b.getStatus().name());
        m.put("approvalDate", b.getApprovalDate() == null ? null : b.getApprovalDate().toString());
        return m;
    }
}
