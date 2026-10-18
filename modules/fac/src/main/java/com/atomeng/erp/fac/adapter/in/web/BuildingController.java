package com.atomeng.erp.fac.adapter.in.web;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.common.web.http.PageResponse;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.application.BuildingService;
import com.atomeng.erp.fac.application.MergeService;
import com.atomeng.erp.fac.domain.model.Building;
import com.atomeng.erp.fac.domain.model.FacTypes.BuildingStatus;
import com.atomeng.erp.fac.domain.model.FacTypes.DataSource;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/fac/buildings (설계 02 B4). POST 는 Idempotency-Key(공통 필터), 단건·수정은 ETag/If-Match.
 */
@RestController
@RequestMapping("/api/v1/fac/buildings")
class BuildingController {

    record CreateBuildingRequest(
            UUID siteId,
            @Size(max = 200) String siteName,
            UUID ownerClientId,
            @Size(max = 200) String name,
            @Size(max = 100) String dongName,
            @Pattern(regexp = "^\\d{19}$", message = "PNU 는 19자리 숫자") String pnu,
            @Size(max = 300) String roadAddress,
            @Size(max = 300) String jibunAddress,
            @Size(max = 100) String mainPurposeNm,
            @Size(max = 100) String structureNm,
            @PositiveOrZero BigDecimal grossFloorArea,
            @Min(0) @Max(200) Short floorsAbove,
            @Min(0) @Max(30) Short floorsBelow,
            LocalDate approvalDate,
            DataSource dataSource,
            boolean force,
            @Size(max = 500) String forceReason,
            @Size(max = 300) String lookupAddress,
            @Size(max = 40) String mgmBldrgstPk) {}

    record MergeRequest(@NotNull UUID targetId, @NotBlank @Size(max = 500) String reason) {}

    record ChangeBuildingRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 300) String roadAddress,
            @Size(max = 300) String jibunAddress,
            @Size(max = 100) String mainPurposeNm,
            @Size(max = 100) String structureNm,
            @PositiveOrZero BigDecimal grossFloorArea,
            @Min(0) @Max(200) Short floorsAbove,
            @Min(0) @Max(30) Short floorsBelow,
            LocalDate approvalDate) {}

    record ClassificationRequest(@NotNull SitbeopClass sitbeopClass, boolean bmaTarget, List<UUID> ruleIds,
                                 @NotNull Map<String, Object> evidence) {}

    record BuildingResponse(UUID id, UUID siteId, String name, String dongName, String pnu, String roadAddress,
                            String mainPurposeNm, String structureNm, BigDecimal grossFloorArea, Short floorsAbove,
                            Short floorsBelow, LocalDate approvalDate, BuildingStatus status, DataSource dataSource,
                            boolean useRestrictionFlag, long version) {
        static BuildingResponse of(Building b) {
            return new BuildingResponse(b.getId(), b.getSiteId(), b.getName(), b.getDongName(), b.getPnu(),
                    b.getRoadAddress(), b.getMainPurposeNm(), b.getStructureNm(), b.getGrossFloorArea(),
                    b.getFloorsAbove(), b.getFloorsBelow(), b.getApprovalDate(), b.getStatus(), b.getDataSource(),
                    b.isUseRestrictionFlag(), b.getVersion());
        }
    }

    record DueResponse(UUID id, String inspectionType, LocalDate baseDate, LocalDate dueDate, String status,
                       JsonNode calcTrace) {}

    private final BuildingService service;
    private final MergeService merges;
    private final FacJdbc jdbc;
    private final CurrentActor actor;
    private final ObjectMapper om;

    BuildingController(BuildingService service, MergeService merges, FacJdbc jdbc, CurrentActor actor,
                       ObjectMapper om) {
        this.service = service;
        this.merges = merges;
        this.jdbc = jdbc;
        this.actor = actor;
        this.om = om;
    }

    @PostMapping
    @RequirePermission("fac:building:create")
    public ResponseEntity<BuildingResponse> create(@Valid @RequestBody CreateBuildingRequest r) {
        var b = service.register(new BuildingService.Register(r.siteId(), r.siteName(), r.ownerClientId(),
                new Building.Spec(r.name(), r.dongName(), r.pnu(), r.roadAddress(), r.jibunAddress(),
                        r.mainPurposeNm(), r.structureNm(), r.grossFloorArea(), r.floorsAbove(), r.floorsBelow(),
                        r.approvalDate(), r.dataSource()), r.force(), r.forceReason(), r.lookupAddress(),
                r.mgmBldrgstPk()));
        return ResponseEntity.created(URI.create("/api/v1/fac/buildings/" + b.getId()))
                .eTag(IfMatch.etagOf(b.getVersion())).body(BuildingResponse.of(b));
    }

    @GetMapping
    @RequirePermission("fac:building:read")
    public PageResponse<BuildingResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        var p = service.search(q, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500)));
        return PageResponse.of(p.map(BuildingResponse::of));
    }

    @GetMapping("/{id}")
    @RequirePermission("fac:building:read")
    public ResponseEntity<BuildingResponse> get(@PathVariable UUID id) {
        var b = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etagOf(b.getVersion())).body(BuildingResponse.of(b));
    }

    @PatchMapping("/{id}")
    @RequirePermission("fac:building:update")
    public ResponseEntity<BuildingResponse> change(@PathVariable UUID id,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                   @Valid @RequestBody ChangeBuildingRequest r) {
        long expected = IfMatch.require(ifMatch);
        var b = service.change(id, expected, new Building.Spec(r.name(), null, null, r.roadAddress(),
                r.jibunAddress(), r.mainPurposeNm(), r.structureNm(), r.grossFloorArea(), r.floorsAbove(),
                r.floorsBelow(), r.approvalDate(), null));
        return ResponseEntity.ok().eTag(IfMatch.etagOf(b.getVersion())).body(BuildingResponse.of(b));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("fac:building:delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        service.delete(id, IfMatch.require(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/classification")
    @RequirePermission("fac:classification:confirm")
    public ResponseEntity<BuildingResponse> classify(@PathVariable UUID id,
                                                     @Valid @RequestBody ClassificationRequest r) {
        var b = service.confirmClassification(id, r.sitbeopClass(), r.bmaTarget(), r.ruleIds(), r.evidence(),
                actor.actorIdOrSystem());
        return ResponseEntity.ok().eTag(IfMatch.etagOf(b.getVersion())).body(BuildingResponse.of(b));
    }

    /** 중복 병합: 이 시설물(source) → targetId(존속). PM (TC-FAC-011) */
    @PostMapping("/{id}/merge")
    @RequirePermission("fac:building:merge")
    public MergeService.MergeReport merge(@PathVariable UUID id, @Valid @RequestBody MergeRequest r) {
        return merges.merge(id, r.targetId(), r.reason());
    }

    /** 등급 이력 (append-only, 최신순) */
    @GetMapping("/{id}/grades")
    @RequirePermission("fac:grade:read")
    public List<FacJdbc.GradeRow> grades(@PathVariable UUID id) {
        service.get(id);
        return jdbc.grades(id);
    }

    /** 활성 점검 도래 + 계산 근거(calcTrace) */
    @GetMapping("/{id}/dues")
    @RequirePermission("fac:due:read")
    public List<DueResponse> dues(@PathVariable UUID id) {
        service.get(id);
        return jdbc.activeDues(id).stream().map(d -> new DueResponse(d.id(), d.inspectionType(), d.baseDate(),
                d.dueDate(), d.status(), readTree(d.calcTrace()))).toList();
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
