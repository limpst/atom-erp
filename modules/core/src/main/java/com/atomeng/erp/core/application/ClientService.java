package com.atomeng.erp.core.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.pii.Pii;
import com.atomeng.common.core.pii.PiiMasking;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.adapter.out.persistence.ClientRepository;
import com.atomeng.erp.core.adapter.out.persistence.DocNumberGenerator;
import com.atomeng.erp.core.domain.model.Client;
import com.atomeng.erp.core.domain.model.ClientType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 유스케이스 — 생성·조회·수정(낙관적 락), 감사 로그 동기 기록 */
@Service
public class ClientService {

    public record CreateClient(String name, ClientType clientType, String bizRegNo, String address) {}

    public record ChangeClient(String name, ClientType clientType, String bizRegNo, String address, String note) {}

    private final ClientRepository clients;
    private final DocNumberGenerator numbers;
    private final AuditLog audit;

    public ClientService(ClientRepository clients, DocNumberGenerator numbers, AuditLog audit) {
        this.clients = clients;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional
    public Client create(CreateClient cmd) {
        var client = Client.create(numbers.nextClientNo(), cmd.name(), cmd.clientType(), cmd.bizRegNo(),
                cmd.address());
        clients.saveAndFlush(client);
        audit.append("CORE", AuditLog.Action.CREATE, "Client", client.getId(), null, snapshot(client));
        return client;
    }

    @Transactional(readOnly = true)
    public java.util.List<Client> list() {
        return clients.findAll(org.springframework.data.domain.Sort.by("clientNo"));
    }

    @Transactional(readOnly = true)
    public Client get(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new DomainException(CoreErrors.NOT_FOUND, "고객을 찾을 수 없습니다: " + id));
    }

    /** If-Match 기대 version 이 현재와 다르면 412. 동시 커밋 경합은 JPA @Version 이 412로 변환된다. */
    @Transactional
    public Client change(UUID id, long expectedVersion, ChangeClient cmd) {
        var client = get(id);
        IfMatch.check(expectedVersion, client.getVersion());
        var before = snapshot(client);
        client.change(cmd.name(), cmd.clientType(), cmd.bizRegNo(), cmd.address(), cmd.note());
        clients.saveAndFlush(client);
        audit.append("CORE", AuditLog.Action.UPDATE, "Client", client.getId(), before, snapshot(client));
        return client;
    }

    private static Map<String, Object> snapshot(Client c) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("clientNo", c.getClientNo());
        // SEC-CORE-001: 개인 고객(INDIVIDUAL)은 이름 · 등록번호 · 주소가 개인정보 → 감사 로그에는 가린 값만 (설계 14-1)
        boolean individual = c.getClientType() == ClientType.INDIVIDUAL;
        m.put("name", individual ? PiiMasking.mask(Pii.Kind.NAME, c.getName()) : c.getName());
        m.put("clientType", c.getClientType().name());
        m.put("bizRegNo", individual ? PiiMasking.mask(Pii.Kind.RRN, c.getBizRegNo()) : c.getBizRegNo());
        m.put("address", individual ? PiiMasking.mask(Pii.Kind.GENERIC, c.getAddress()) : c.getAddress());
        return m;
    }
}
