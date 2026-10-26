package com.atomeng.erp.qar.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 웹 "내 할 일" — 나에게 배정된 열린 QAR 검토 단계(QA 검토 · 동료 검토 · 최종 승인), 읽기 전용.
 * 실패하는 쪽으로 닫힘: 배정 대상 = 호출자(직원 ID 없으면 빈 목록) · 끝나지 않은 배정 · 진행 중 검토만, 그리고 행마다
 * 단건 조회와 같은 범위 검사(qar:review:read — PROJECT 범위면 투입 프로젝트)를 통과한 것만 준다.
 */
@Service
public class ReviewInboxService {

    private final JdbcTemplate jdbc;
    private final QarAccess access;

    public ReviewInboxService(JdbcTemplate jdbc, QarAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public List<Map<String, Object>> myAssignments() {
        UUID me = AtomAuthentication.currentPrincipal().map(AtomPrincipal::employeeId).orElse(null);
        if (me == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id AS "assignmentId", a.step_code AS "stepCode", a.due_at AS "dueAt", a.created_at AS "assignedAt",
                       r.id AS "reviewId", r.report_id AS "reportId", r.project_id AS "projectId",
                       r.report_type AS "reportType", r.status, rr.round_no AS "roundNo", rr.rev_no AS "revNo"
                  FROM qar.review_assignment a
                  JOIN qar.review_round rr ON rr.id = a.round_id
                  JOIN qar.review r ON r.id = rr.review_id
                 WHERE a.assignee_id = ? AND a.completed_at IS NULL AND rr.closed_at IS NULL
                   AND r.deleted_at IS NULL AND r.status NOT IN ('APPROVED', 'REJECTED')
                 ORDER BY a.due_at NULLS LAST, a.created_at""", me)) {
            try {
                access.requireProject("qar:review:read", (UUID) r.get("projectId"));
                out.add(r);
            } catch (DomainException e) {
                // 범위 밖 — 보여 주지 않는다
            }
        }
        return out;
    }
}
