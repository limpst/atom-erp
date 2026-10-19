package com.atomeng.erp.qar.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.qar.application.ReviewerProfileService;
import com.atomeng.erp.qar.application.ReviewerProfileService.Profile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/qar/reviewer-profiles — QA 검토자 프로필 관리(ADMIN 전용, V0037). 수정 · 비활성화는 If-Match.
 */
@RestController
@RequestMapping("/api/v1/qar/reviewer-profiles")
class ReviewerProfileController {

    record CreateRequest(@NotNull UUID employeeId, String stepCode, @Min(1) @Max(50) Integer maxOpen,
                         Map<String, Integer> expertise, @Size(max = 20) List<String> qualifications,
                         @Size(max = 200) String note, Boolean active) {}

    record UpdateRequest(String stepCode, @Min(1) @Max(50) Integer maxOpen, Map<String, Integer> expertise,
                         @Size(max = 20) List<String> qualifications, @Size(max = 200) String note, Boolean active) {}

    private final ReviewerProfileService service;

    ReviewerProfileController(ReviewerProfileService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("qar:reviewer-profile:read")
    public List<Profile> list() {
        return service.list();
    }

    @GetMapping("/{employeeId}")
    @RequirePermission("qar:reviewer-profile:read")
    public ResponseEntity<Profile> get(@PathVariable UUID employeeId) {
        return etag(service.get(employeeId), HttpStatus.OK);
    }

    @PostMapping
    @RequirePermission("qar:reviewer-profile:write")
    public ResponseEntity<Profile> create(@Valid @RequestBody CreateRequest r) {
        return etag(service.create(r.employeeId(), new ReviewerProfileService.Upsert(r.stepCode(), r.maxOpen(),
                r.expertise(), r.qualifications(), r.note(), r.active())), HttpStatus.CREATED);
    }

    @PatchMapping("/{employeeId}")
    @RequirePermission("qar:reviewer-profile:write")
    public ResponseEntity<Profile> update(@PathVariable UUID employeeId, @Valid @RequestBody UpdateRequest r,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return etag(service.update(employeeId, IfMatch.require(ifMatch), new ReviewerProfileService.Upsert(
                r.stepCode(), r.maxOpen(), r.expertise(), r.qualifications(), r.note(), r.active())), HttpStatus.OK);
    }

    private static ResponseEntity<Profile> etag(Profile p, HttpStatus status) {
        return ResponseEntity.status(status).eTag(IfMatch.etagOf(p.version())).body(p);
    }
}
