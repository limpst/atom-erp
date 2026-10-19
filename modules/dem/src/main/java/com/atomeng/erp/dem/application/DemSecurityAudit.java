package com.atomeng.erp.dem.application;

import com.atomeng.common.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보안 감사 기록(A8 사진 해시 불일치) — 업무 트랜잭션이 409 로 롤백돼도 남도록 별도 트랜잭션(REQUIRES_NEW).
 * QAR SoD 위반 감사와 같은 방식.
 */
@Component
public class DemSecurityAudit {

    private final AuditLog audit;

    public DemSecurityAudit(AuditLog audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hashMismatch(String targetType, UUID targetId, Map<String, Object> detail) {
        audit.append("DEM", AuditLog.Action.UPDATE, targetType, targetId, null, withCode(detail));
    }

    private static Map<String, Object> withCode(Map<String, Object> detail) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("security", "EVIDENCE_HASH_MISMATCH");
        m.putAll(detail);
        return m;
    }
}
