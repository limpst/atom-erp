package com.atomeng.erp.core.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** 프로젝트 상태 (core.project.status CHECK 와 동일) */
public enum ProjectStatus {
    PLANNED, IN_PROGRESS, FIELD_DONE, REPORTING, IN_REVIEW, DELIVERED, CLOSED, CANCELLED;

    public Set<ProjectStatus> next() {
        return switch (this) {
            case PLANNED -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(FIELD_DONE, CANCELLED);
            case FIELD_DONE -> EnumSet.of(REPORTING);
            case REPORTING -> EnumSet.of(IN_REVIEW);
            case IN_REVIEW -> EnumSet.of(REPORTING, DELIVERED);
            case DELIVERED -> EnumSet.of(CLOSED);
            case CLOSED, CANCELLED -> EnumSet.noneOf(ProjectStatus.class);
        };
    }

    public boolean canTransitionTo(ProjectStatus target) {
        return next().contains(target);
    }
}
