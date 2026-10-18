package com.atomeng.erp.qar.domain;

import static com.atomeng.erp.qar.domain.Command.AI_DONE;
import static com.atomeng.erp.qar.domain.Command.AI_START;
import static com.atomeng.erp.qar.domain.Command.AI_TIMEOUT;
import static com.atomeng.erp.qar.domain.Command.APPROVE;
import static com.atomeng.erp.qar.domain.Command.PASS;
import static com.atomeng.erp.qar.domain.Command.REJECT;
import static com.atomeng.erp.qar.domain.Command.REOPEN_FOR_CORRECTION;
import static com.atomeng.erp.qar.domain.Command.RESUBMIT_PREP;
import static com.atomeng.erp.qar.domain.Command.SUBMIT;
import static com.atomeng.erp.qar.domain.ReviewStatus.AI_REVIEWING;
import static com.atomeng.erp.qar.domain.ReviewStatus.APPROVED;
import static com.atomeng.erp.qar.domain.ReviewStatus.PENDING_APPROVAL;
import static com.atomeng.erp.qar.domain.ReviewStatus.QA_REVIEWING;
import static com.atomeng.erp.qar.domain.ReviewStatus.REJECTED;
import static com.atomeng.erp.qar.domain.ReviewStatus.SELF_CHECK;
import static com.atomeng.erp.qar.domain.ReviewStatus.SUBMITTED;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 설계 B7.1 명시적 전이 테이블 — (현재 상태, 명령) → (다음 상태, 가드, 효과). Spring StateMachine 대신 순수 표(07-IMPL Step 2 팁).
 * 허용 전이 10건(7 상태 × 9 명령 = 63 조합 중), 나머지는 QAR_STATE. 가드 · 효과는 이름(enum)으로 선언하고
 * 실행은 WorkflowEngine 이 한다(가드는 첫 실패에서 멈추지 않고 전부 모아 반환).
 */
public final class TransitionTable {

    public enum Guard {
        /** 최신 AUTO-CHECK 결과 BLOCKING 0 (BR-QAR-001, waiver 불가) */
        NO_BLOCKING,
        /** 최신 실행이 현재 RPT 본문 · 오늘 유효 규칙 세트로 돌았는지(제출 시점 규칙 고정 BR-QAR-007) */
        AUTOCHECK_FRESH,
        /** RPT 리비전이 검토 요청(IN_REVIEW, 편집 잠금 BR-QAR-003) 상태 + 최신 렌더 PDF */
        REVISION_LOCKED,
        CHECKLIST_SELF_COMPLETE,
        CHECKLIST_QA_COMPLETE,
        /** 미해결 CRITICAL · MAJOR 코멘트 0 */
        NO_OPEN_SEVERE,
        /** 실행자 = 이 라운드 QA 담당자 */
        ACTOR_IS_QA_ASSIGNEE,
        /** 승인 단계: 실행자 ≠ 이 라운드 QA 담당자 (BR-QAR-002 QA 검토자 ≠ 최종 승인자) */
        SOD_APPROVER,
        /** 책임기술자 자격 유효(포트, TC-QAR-011) */
        QUALIFICATION_VALID,
        /** 제출 후 RPT 본문이 바뀌지 않았는지(고정 해시) */
        REVISION_UNCHANGED,
        HAS_REJECT_SUMMARY,
        /** 반려 후 RPT 본문이 수정(재렌더)되었는지 — 설계 newRevisionExists 의 블록 편집 모델 해석 */
        REVISION_CHANGED,
        /** 정정본(새 Rev) 존재 */
        CORRECTION_REVISION_EXISTS
    }

    public enum Effect {
        FREEZE_ROUND, PUBLISH_SUBMITTED, AUTO_START_AI, REQUEST_AI, MARK_AI_DONE, MARK_AI_SKIPPED, ASSIGN_QA,
        COMPLETE_QA_ASSIGNMENT, CLOSE_ROUND_REJECTED, PUBLISH_REJECTED, CLOSE_ROUND_APPROVED, PUBLISH_APPROVED,
        NEW_ROUND, CARRY_OVER_OPEN_COMMENTS, ESCALATE_IF_ROUND_LIMIT, NOTIFY_AUTHOR
    }

    public record Transition(ReviewStatus from, Command cmd, ReviewStatus to, List<Guard> guards, List<Effect> effects) {}

    private static final Map<ReviewStatus, Map<Command, Transition>> TABLE = new EnumMap<>(ReviewStatus.class);

    static {
        put(SELF_CHECK, SUBMIT, SUBMITTED,
                List.of(Guard.REVISION_LOCKED, Guard.AUTOCHECK_FRESH, Guard.NO_BLOCKING, Guard.CHECKLIST_SELF_COMPLETE),
                List.of(Effect.FREEZE_ROUND, Effect.PUBLISH_SUBMITTED, Effect.AUTO_START_AI));
        // v0.2: AI 검토는 제출 후 AI_START 명령으로 시작(수동 실행도 같은 명령, BR-QAR-010)
        put(SUBMITTED, AI_START, AI_REVIEWING, List.of(), List.of(Effect.REQUEST_AI));
        put(AI_REVIEWING, AI_DONE, QA_REVIEWING, List.of(), List.of(Effect.MARK_AI_DONE, Effect.ASSIGN_QA));
        put(AI_REVIEWING, AI_TIMEOUT, QA_REVIEWING, List.of(), List.of(Effect.MARK_AI_SKIPPED, Effect.ASSIGN_QA));
        put(QA_REVIEWING, PASS, PENDING_APPROVAL,
                List.of(Guard.ACTOR_IS_QA_ASSIGNEE, Guard.CHECKLIST_QA_COMPLETE, Guard.NO_OPEN_SEVERE),
                List.of(Effect.COMPLETE_QA_ASSIGNMENT));
        put(QA_REVIEWING, REJECT, REJECTED, List.of(Guard.ACTOR_IS_QA_ASSIGNEE, Guard.HAS_REJECT_SUMMARY),
                List.of(Effect.COMPLETE_QA_ASSIGNMENT, Effect.CLOSE_ROUND_REJECTED, Effect.PUBLISH_REJECTED,
                        Effect.NOTIFY_AUTHOR));
        put(PENDING_APPROVAL, APPROVE, APPROVED,
                List.of(Guard.NO_BLOCKING, Guard.NO_OPEN_SEVERE, Guard.SOD_APPROVER, Guard.QUALIFICATION_VALID,
                        Guard.REVISION_UNCHANGED),
                List.of(Effect.CLOSE_ROUND_APPROVED, Effect.PUBLISH_APPROVED));
        put(PENDING_APPROVAL, REJECT, REJECTED, List.of(Guard.SOD_APPROVER, Guard.HAS_REJECT_SUMMARY),
                List.of(Effect.CLOSE_ROUND_REJECTED, Effect.PUBLISH_REJECTED, Effect.NOTIFY_AUTHOR));
        put(REJECTED, RESUBMIT_PREP, SELF_CHECK, List.of(Guard.REVISION_CHANGED),
                List.of(Effect.NEW_ROUND, Effect.CARRY_OVER_OPEN_COMMENTS, Effect.ESCALATE_IF_ROUND_LIMIT));
        // v0.2: 발행 후 정정본(RPT Rev.N) 재검토 (BR-QAR-010)
        put(APPROVED, REOPEN_FOR_CORRECTION, SELF_CHECK, List.of(Guard.CORRECTION_REVISION_EXISTS),
                List.of(Effect.NEW_ROUND, Effect.NOTIFY_AUTHOR));
    }

    private TransitionTable() {}

    private static void put(ReviewStatus from, Command cmd, ReviewStatus to, List<Guard> g, List<Effect> e) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(Command.class)).put(cmd, new Transition(from, cmd, to, g, e));
    }

    public static Optional<Transition> find(ReviewStatus from, Command cmd) {
        return Optional.ofNullable(TABLE.getOrDefault(from, Map.of()).get(cmd));
    }

    public static List<Transition> all() {
        return TABLE.values().stream().flatMap(m -> m.values().stream()).toList();
    }
}
