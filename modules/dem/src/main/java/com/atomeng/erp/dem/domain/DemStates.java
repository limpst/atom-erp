package com.atomeng.erp.dem.domain;

import com.atomeng.common.core.error.DomainException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** DEM 상태 전이 표 (설계 10 A6.1 · A6.2 · A6.3) — 순수 도메인. 표에 없는 이동은 409 DEM-409-004(DEM_INVALID_TRANSITION). */
public final class DemStates {

    public static final String INVALID_TRANSITION = "DEM-409-004";

    private DemStates() {}

    /** A6.1 DEM 프로젝트(해체) */
    public enum ProjectStatus {
        DRAFT, PLANNING, REVIEWING, PERMIT_PENDING, IN_PROGRESS, SUSPENDED, COMPLETING, CLOSED;

        private static final Map<ProjectStatus, Set<ProjectStatus>> ALLOWED = Map.of(
                DRAFT, EnumSet.of(PLANNING),
                PLANNING, EnumSet.of(REVIEWING),
                REVIEWING, EnumSet.of(PLANNING, PERMIT_PENDING),
                PERMIT_PENDING, EnumSet.of(PLANNING, IN_PROGRESS),
                IN_PROGRESS, EnumSet.of(SUSPENDED, COMPLETING),
                SUSPENDED, EnumSet.of(IN_PROGRESS),
                COMPLETING, EnumSet.of(CLOSED));

        public boolean canMoveTo(ProjectStatus next) {
            return ALLOWED.getOrDefault(this, Set.of()).contains(next);
        }

        public void assertCanMoveTo(ProjectStatus next) {
            if (!canMoveTo(next)) {
                throw invalid("DEM 프로젝트", this, next);
            }
        }
    }

    /** A6.2 도서 리비전 */
    public enum RevisionStatus {
        WIP, INTERNAL_REVIEW, APPROVED, ISSUED, SUPERSEDED;

        private static final Map<RevisionStatus, Set<RevisionStatus>> ALLOWED = Map.of(
                WIP, EnumSet.of(INTERNAL_REVIEW),
                INTERNAL_REVIEW, EnumSet.of(WIP, APPROVED),
                APPROVED, EnumSet.of(ISSUED),
                ISSUED, EnumSet.of(SUPERSEDED));

        public boolean canMoveTo(RevisionStatus next) {
            return ALLOWED.getOrDefault(this, Set.of()).contains(next);
        }

        public void assertCanMoveTo(RevisionStatus next) {
            if (!canMoveTo(next)) {
                throw invalid("도서 리비전", this, next);
            }
        }

        /** BR-DEM-008 발행(ISSUED) 이후는 불변 */
        public boolean published() {
            return this == ISSUED || this == SUPERSEDED;
        }
    }

    /** A6.3 검토의견 */
    public enum CommentStatus {
        OPEN, RESPONDED, ACCEPTED, REJECTED, CLOSED;

        private static final Map<CommentStatus, Set<CommentStatus>> ALLOWED = Map.of(
                OPEN, EnumSet.of(RESPONDED, CLOSED),        // 설계자 회신 · 철회(사유 필수)
                RESPONDED, EnumSet.of(ACCEPTED, REJECTED),  // 검토자 수용 · 재지적
                REJECTED, EnumSet.of(RESPONDED),            // 재회신
                ACCEPTED, EnumSet.of(CLOSED));

        /** BR-DEM-009(v0.2): OPEN · RESPONDED · REJECTED 는 미결 — REJECTED 는 설계자 재회신 대기 */
        public static final Set<CommentStatus> UNRESOLVED = EnumSet.of(OPEN, RESPONDED, REJECTED);

        public boolean unresolved() {
            return UNRESOLVED.contains(this);
        }

        public boolean canMoveTo(CommentStatus next) {
            return ALLOWED.getOrDefault(this, Set.of()).contains(next);
        }

        public void assertCanMoveTo(CommentStatus next) {
            if (!canMoveTo(next)) {
                throw invalid("검토의견", this, next);
            }
        }
    }

    private static DomainException invalid(String what, Enum<?> from, Enum<?> to) {
        return new DomainException(INVALID_TRANSITION, "%s 상태 전이 불가: %s → %s".formatted(what, from, to))
                .with("reason", "DEM_INVALID_TRANSITION").with("from", from.name()).with("to", to.name());
    }
}
