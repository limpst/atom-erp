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

    public record Register(UUID siteId, String siteName, UUID ownerClientId, Building.Spec spec, boolean force,
                           String forceReason) {}

    public record Candidate(UUID id, String name, Double nameSimilarity, String matchedBy) {}

    private final BuildingRepository buildings;
    private final SiteRepository sites;
    private final FacJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CycleRecomputeService cycle;
    private final Clock clock;

    public BuildingService(BuildingRepository buildings, SiteRepository sites, FacJdbc jdbc, OutboxPublisher outbox,
                           AuditLog audit, CycleRecomputeService cycle, Clock clock) {
        this.buildings = buildings;
        this.sites = sites;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.cycle = cycle;
        this.clock = clock;
    }

    /** 등록 — 중복 후보가 있으면 409 FAC-409-001 + candidates (TC-FAC-001). force=true 는 사유 필수·감사 */
    @Transactional
    public Building register(Register cmd) {
        var s = cmd.spec();
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
        if (b.getPnu() != null) {
            b.verify(); // 대장 ACL(Step 3) 전까지: PNU 가 있으면 VERIFIED 로 간주
        }
        buildings.saveAndFlush(b);
        outbox.publish(new FacEvents.BuildingRegistered(b.getId(), siteId, b.getPnu(), b.getName(),
                cmd.ownerClientId()));
        Map<String, Object> after = snapshot(b);
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
