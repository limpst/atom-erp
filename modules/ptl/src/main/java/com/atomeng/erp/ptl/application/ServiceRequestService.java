package com.atomeng.erp.ptl.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.ptl.api.PtlEvents;
import com.atomeng.erp.ptl.domain.ServiceRequestKind;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 요청(견적 · 긴급점검 · 재점검 · 확인점검, FR-PTL-010 · 011, 09-IMPL Step 9 일부).
 * 시설물을 지정하면 고객 소유인지 fac::api 로 확인(아니면 404 존재 은닉). 저장(RLS)과 같은 트랜잭션에서
 * {@code ptl.service-request.submitted} 를 outbox 에 기록 → SAL 이 PORTAL 리드를 만든다(SalEventListener, 멱등 serviceRequestId).
 * 긴급점검은 sla_due_at = 접수 + 2시간(BR-PTL-007). SLA 타이머 · 에스컬레이션 · 당직 알림은 후속(Step 9 나머지).
 */
@Service
public class ServiceRequestService {

    public record Submit(String kind, UUID facilityId, String serviceType, String reasonCode, String description,
                         String contactName) {}

    private final JdbcTemplate jdbc;
    private final PortalRls rls;
    private final PortalFacilityService facilities;
    private final PortalAccessLog accessLog;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final Clock clock;

    public ServiceRequestService(JdbcTemplate jdbc, PortalRls rls, PortalFacilityService facilities,
                                 PortalAccessLog accessLog, OutboxPublisher outbox, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.rls = rls;
        this.facilities = facilities;
        this.accessLog = accessLog;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> submit(PortalAccess.Customer c, Submit cmd) {
        ServiceRequestKind kind;
        try {
            kind = ServiceRequestKind.valueOf(cmd.kind());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(PtlErrors.INVALID, "알 수 없는 요청 종류: " + cmd.kind());
        }
        if (kind.requiresReason() && (cmd.reasonCode() == null || cmd.reasonCode().isBlank())) {
            throw new DomainException(PtlErrors.INVALID, "긴급점검 요청은 사유 코드(reasonCode)가 필요합니다");
        }
        if (cmd.facilityId() != null) {
            facilities.owned(c, cmd.facilityId());   // 다른 고객사 시설물 → 404 + DENIED
        }
        UUID id = UuidV7.next();
        Instant now = clock.instant();
        Instant slaDue = kind == ServiceRequestKind.EMERGENCY
                ? now.plus(Duration.ofHours(ServiceRequestKind.EMERGENCY_SLA_HOURS)) : null;
        String serviceType = cmd.serviceType() == null || cmd.serviceType().isBlank()
                ? kind.defaultServiceType() : cmd.serviceType();
        Map<String, Object> saved = rls.asCustomer(c.customerId(), () -> {
            jdbc.update("""
                    INSERT INTO ptl.service_request(id, customer_id, facility_id, kind, service_type, reason_code,
                                                    description, contact_name, sla_due_at, submitted_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, c.customerId(), cmd.facilityId(), kind.name(),
                    serviceType, blankToNull(cmd.reasonCode()), cmd.description(), cmd.contactName(),
                    slaDue == null ? null : Timestamp.from(slaDue), c.actorId(), Timestamp.from(now));
            return find(id);
        });
        String tag = kind.name() + (cmd.reasonCode() == null || cmd.reasonCode().isBlank() ? "" : "/" + cmd.reasonCode());
        outbox.publish(new PtlEvents.ServiceRequestSubmitted(id, c.customerId(), cmd.facilityId(), serviceType,
                "[" + tag + "] " + cmd.description()));
        audit.append("PTL", AuditLog.Action.CREATE, "ServiceRequest", id, null,
                Map.of("kind", kind.name(), "customerId", c.customerId(), "serviceType", serviceType));
        return view(saved);
    }

    @Transactional
    public List<Map<String, Object>> list(PortalAccess.Customer c) {
        return rls.asCustomer(c.customerId(), () -> jdbc.queryForList(
                "SELECT * FROM ptl.service_request ORDER BY created_at DESC, id DESC").stream()
                .map(ServiceRequestService::view).toList());
    }

    @Transactional
    public Map<String, Object> get(PortalAccess.Customer c, UUID id) {
        Map<String, Object> row = rls.asCustomer(c.customerId(), () -> find(id));
        if (row == null) {
            accessLog.denied(c, "ServiceRequest", id, "NOT_FOUND_OR_OTHER_CUSTOMER");
            throw new DomainException(PtlErrors.NOT_FOUND, "서비스 요청 없음: " + id);
        }
        return view(row);
    }

    private Map<String, Object> find(UUID id) {
        return jdbc.queryForList("SELECT * FROM ptl.service_request WHERE id = ?", id).stream().findFirst().orElse(null);
    }

    private static Map<String, Object> view(Map<String, Object> raw) {
        Map<String, Object> r = PtlRows.normalize(raw);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("kind", r.get("kind"));
        m.put("facilityId", r.get("facility_id"));
        m.put("serviceType", r.get("service_type"));
        m.put("reasonCode", r.get("reason_code"));
        m.put("description", r.get("description"));
        m.put("status", r.get("status"));
        m.put("slaDueAt", r.get("sla_due_at"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
