package com.atomeng.erp.sal.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.sal.application.SalesGroupService;
import com.atomeng.erp.sal.application.SalesGroupService.Member;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * /api/v1/sal/sales-group-members — 영업팀장(SALES_LEAD) 그룹 소속 관리(ADMIN 전용, V0036).
 * 수정 · 비활성화는 If-Match(ETag "v{version}").
 */
@RestController
@RequestMapping("/api/v1/sal/sales-group-members")
class SalesGroupController {

    record AddRequest(@NotNull UUID employeeId, @NotBlank String groupCode, @Size(max = 200) String note) {}

    record UpdateRequest(Boolean active, @Size(max = 200) String note) {}

    private final SalesGroupService service;

    SalesGroupController(SalesGroupService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("sal:sales-group:read")
    public List<Member> list() {
        return service.list();
    }

    @GetMapping("/{employeeId}/{groupCode}")
    @RequirePermission("sal:sales-group:read")
    public ResponseEntity<Member> get(@PathVariable UUID employeeId, @PathVariable String groupCode) {
        return etag(service.get(employeeId, groupCode), HttpStatus.OK);
    }

    @PostMapping
    @RequirePermission("sal:sales-group:write")
    public ResponseEntity<Member> add(@Valid @RequestBody AddRequest r) {
        return etag(service.add(r.employeeId(), r.groupCode(), r.note()), HttpStatus.CREATED);
    }

    @PatchMapping("/{employeeId}/{groupCode}")
    @RequirePermission("sal:sales-group:write")
    public ResponseEntity<Member> update(@PathVariable UUID employeeId, @PathVariable String groupCode,
                                         @Valid @RequestBody UpdateRequest r,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return etag(service.update(employeeId, groupCode, IfMatch.require(ifMatch), r.active(), r.note()),
                HttpStatus.OK);
    }

    private static ResponseEntity<Member> etag(Member m, HttpStatus status) {
        return ResponseEntity.status(status).eTag(IfMatch.etagOf(m.version())).body(m);
    }
}
