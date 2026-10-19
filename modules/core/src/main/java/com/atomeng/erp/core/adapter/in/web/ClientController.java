package com.atomeng.erp.core.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.application.ClientService;
import com.atomeng.erp.core.domain.model.Client;
import com.atomeng.erp.core.domain.model.ClientType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/core/clients (설계 9.1: 케밥 복수형, ETag/If-Match, Idempotency-Key 는 필터가 처리) */
@RestController
@RequestMapping("/api/v1/core/clients")
class ClientController {

    record CreateClientRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull ClientType clientType,
            @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자번호 형식 000-00-00000") String bizRegNo,
            @Size(max = 300) String address) {}

    record ChangeClientRequest(
            @Size(min = 1, max = 200) String name,
            ClientType clientType,
            @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자번호 형식 000-00-00000") String bizRegNo,
            @Size(max = 300) String address,
            String note) {}

    record ClientResponse(UUID id, String clientNo, String name, ClientType clientType, String bizRegNo,
                          String address, long version) {
        static ClientResponse of(Client c) {
            return new ClientResponse(c.getId(), c.getClientNo(), c.getName(), c.getClientType(), c.getBizRegNo(),
                    c.getAddress(), c.getVersion());
        }
    }

    private final ClientService service;

    ClientController(ClientService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission("core:client:create")
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest req) {
        var c = service.create(new ClientService.CreateClient(req.name(), req.clientType(), req.bizRegNo(),
                req.address()));
        return ResponseEntity.created(URI.create("/api/v1/core/clients/" + c.getId()))
                .eTag(IfMatch.etagOf(c.getVersion()))
                .body(ClientResponse.of(c));
    }

    /** 목록(웹 고객 선택용) — core:client:read 는 보유 역할 모두 ALL 범위 */
    @GetMapping
    @RequirePermission("core:client:read")
    public java.util.List<ClientResponse> list() {
        return service.list().stream().map(ClientResponse::of).toList();
    }

    @GetMapping("/{id}")
    @RequirePermission("core:client:read")
    public ResponseEntity<ClientResponse> get(@PathVariable UUID id) {
        var c = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etagOf(c.getVersion())).body(ClientResponse.of(c));
    }

    @PatchMapping("/{id}")
    @RequirePermission("core:client:update")
    public ResponseEntity<ClientResponse> change(@PathVariable UUID id,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                          @Valid @RequestBody ChangeClientRequest req) {
        long expected = IfMatch.require(ifMatch);
        var c = service.change(id, expected, new ClientService.ChangeClient(req.name(), req.clientType(),
                req.bizRegNo(), req.address(), req.note()));
        return ResponseEntity.ok().eTag(IfMatch.etagOf(c.getVersion())).body(ClientResponse.of(c));
    }
}
