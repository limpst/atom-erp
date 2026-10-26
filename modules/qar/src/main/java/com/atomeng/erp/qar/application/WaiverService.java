package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Waiver(예외 승인) (FR-QAR-009, BR-QAR-001 · 006, TC-QAR-009): WARNING 결과에만, PM · QA 두 사람의 사유.
 * 먼저 입력한 쪽이 PENDING, 다른 역할 · 다른 사람이 입력하면 GRANTED. BLOCKING · INFO 는 422(서비스) + DB 트리거(이중 방어).
 */
@Service
public class WaiverService {

    private final ReviewSupport sup;
    private final QarJdbc qar;

    public WaiverService(ReviewSupport sup) {
        this.sup = sup;
        this.qar = sup.jdbc();
    }

    @Transactional
    public Map<String, Object> addReason(UUID resultId, String reason) {
        Map<String, Object> result = qar.get("autocheck_result", resultId);
        Map<String, Object> round = sup.round(QarJdbc.uuid(result, "round_id"));
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:waiver:create", true);
        // SEC-QAR-03: 승인 완료된 검토 · 지난 라운드의 자동검사 결과에는 예외 사유를 더 붙일 수 없다
        if ("APPROVED".equals(QarJdbc.str(review, "status"))
                || QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")) {
            throw new DomainException(QarCodes.STATE, "승인 완료된 검토이거나 현재 라운드가 아닌 결과에는 예외 승인을 할 수 없습니다");
        }
        String sev = QarJdbc.str(result, "severity");
        if (!"WARNING".equals(sev)) {
            throw new DomainException(QarCodes.WAIVER_NOT_ALLOWED, "WARNING 결과만 예외 승인할 수 있습니다(현재 " + sev + ")")
                    .with("reason", "QAR_WAIVER_NOT_ALLOWED");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException(QarCodes.INVALID, "예외 승인 사유가 필요합니다");
        }
        boolean pm = sup.access().has(Role.PM);
        boolean qa = sup.access().has(Role.QA);
        if (pm == qa) {
            throw new DomainException(QarCodes.INVALID, "PM 또는 QA 중 한 역할로 사유를 입력하세요");
        }
        String side = pm ? "pm" : "qa";
        UUID actor = sup.actorId();
        // 보안 검토 2026-10-19 QAR-04b: QA 측 서명은 이 라운드의 현재 QA 담당자만, PM 측 서명자 ≠ 작성자(작성 · 제출 · 섹션 편집)
        if (qa) {
            UUID assignee = qar.jdbc().queryForList("""
                    SELECT assignee_id FROM qar.review_assignment WHERE round_id = ? AND step_code = 'QA_REVIEW'
                       AND completed_at IS NULL ORDER BY created_at DESC LIMIT 1""", UUID.class, round.get("id"))
                    .stream().findFirst().orElse(null);
            if (!actor.equals(assignee)) {
                throw new DomainException(QarCodes.NOT_ASSIGNEE, "예외 승인 QA 사유는 이 라운드의 현재 QA 담당자만 입력합니다")
                        .with("reason", "QAR_NOT_ASSIGNEE");
            }
        } else {
            String role = sup.authorRole(review, round, actor, false);
            if (role != null) {
                throw new DomainException(QarCodes.SOD_VIOLATION,
                        "보고서 작성에 참여한 사람(" + role + ")은 예외 승인 PM 사유를 입력할 수 없습니다")
                        .with("reason", "QAR_SOD_VIOLATION").with("sodRule", role);
            }
        }
        Map<String, Object> w = qar.jdbc().queryForList("SELECT * FROM qar.waiver WHERE autocheck_result_id = ? FOR UPDATE",
                resultId).stream().findFirst().orElse(null);
        if (w == null) {
            UUID id = UuidV7.next();
            qar.jdbc().update("INSERT INTO qar.waiver(id, autocheck_result_id, " + side + "_id, " + side + "_reason, "
                    + side + "_at, status) VALUES (?, ?, ?, ?, now(), 'PENDING')", id, resultId, actor, reason.strip());
            w = qar.get("waiver", id);
        } else {
            if (w.get(side + "_id") != null) {
                throw new DomainException(QarCodes.WAIVER_CONFLICT, side.toUpperCase() + " 사유가 이미 입력되었습니다");
            }
            UUID other = QarJdbc.uuid(w, pm ? "qa_id" : "pm_id");
            if (actor.equals(other)) {
                throw new DomainException(QarCodes.WAIVER_CONFLICT, "PM · QA 사유는 서로 다른 사람이 입력해야 합니다");
            }
            qar.jdbc().update("UPDATE qar.waiver SET " + side + "_id = ?, " + side + "_reason = ?, " + side
                    + "_at = now(), status = 'GRANTED' WHERE id = ?", actor, reason.strip(), w.get("id"));
            w = qar.get("waiver", QarJdbc.uuid(w, "id"));
        }
        sup.audit().append("QAR", AuditLog.Action.APPROVE, "Waiver", QarJdbc.uuid(w, "id"), null,
                Map.of("resultId", resultId, "side", side, "status", QarJdbc.str(w, "status")));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", w.get("id"));
        v.put("autocheckResultId", resultId);
        v.put("ruleId", result.get("rule_id"));
        v.put("status", w.get("status"));
        v.put("pmId", w.get("pm_id"));
        v.put("qaId", w.get("qa_id"));
        return v;
    }
}
