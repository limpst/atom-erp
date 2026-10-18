package com.atomeng.erp.qar.domain;

import java.util.Map;
import java.util.Optional;

/** 설계 A6 코멘트 상태와 답변 동작(ReplyAction)별 전이 */
public enum CommentStatus {
    OPEN, ANSWERED, RESOLUTION_REQUESTED, RESOLVED, WITHDRAWN;

    public enum ReplyAction { ANSWER, REQUEST_RESOLUTION, REOPEN, CONFIRM, WITHDRAW }

    private static final Map<String, CommentStatus> NEXT = Map.of(
            "OPEN:ANSWER", ANSWERED,
            "ANSWERED:ANSWER", ANSWERED,
            "ANSWERED:REQUEST_RESOLUTION", RESOLUTION_REQUESTED,
            "RESOLUTION_REQUESTED:CONFIRM", RESOLVED,
            "RESOLUTION_REQUESTED:REOPEN", OPEN,
            "OPEN:WITHDRAW", WITHDRAWN);

    public Optional<CommentStatus> next(ReplyAction a) {
        return Optional.ofNullable(NEXT.get(name() + ":" + a.name()));
    }

    public boolean closed() {
        return this == RESOLVED || this == WITHDRAWN;
    }

    /** BR-QAR-004: 해결 확인 · 재오픈 · 철회는 코멘트 작성자(QA)만, 답변 · 해결 요청은 보고서 작성 측만 */
    public static boolean commentAuthorOnly(ReplyAction a) {
        return a == ReplyAction.CONFIRM || a == ReplyAction.REOPEN || a == ReplyAction.WITHDRAW;
    }
}
