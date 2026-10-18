package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 리비전 공통 — 잠금 조회 · 프로젝트 범위 확인 · 상태 전이(A6, 보고서 상태 동기화) · 본문 정규 해시(렌더 최신성).
 */
@Component
public class RevisionSupport {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 리비전 + 보고서 행 */
    public record Ctx(Map<String, Object> rev, Map<String, Object> report) {
        public UUID revId() {
            return RptJdbc.uuid(rev, "id");
        }

        public UUID reportId() {
            return RptJdbc.uuid(report, "id");
        }

        public UUID projectId() {
            return RptJdbc.uuid(report, "project_id");
        }

        public int revNo() {
            return RptJdbc.integer(rev, "rev_no");
        }

        public ReportStatus status() {
            return ReportStatus.valueOf(RptJdbc.str(rev, "status"));
        }

        public UUID snapshotId() {
            return RptJdbc.uuid(rev, "snapshot_id");
        }
    }

    private final RptJdbc rpt;
    private final RptAccess access;
    private final CurrentActor actor;
    private final Clock clock;

    public RevisionSupport(RptJdbc rpt, RptAccess access, CurrentActor actor, Clock clock) {
        this.rpt = rpt;
        this.access = access;
        this.actor = actor;
        this.clock = clock;
    }

    public RptJdbc jdbc() {
        return rpt;
    }

    public UUID actorId() {
        return actor.actorIdOrSystem();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    public Clock clock() {
        return clock;
    }

    /** 리비전 잠금(FOR UPDATE) + 프로젝트 범위 확인 */
    public Ctx lock(UUID revId, String permission) {
        Map<String, Object> rev = rpt.lock("report_revision", revId);
        Map<String, Object> report = rpt.lock("report", RptJdbc.uuid(rev, "report_id"));
        access.requireProject(permission, RptJdbc.uuid(report, "project_id"));
        return new Ctx(rev, report);
    }

    public Ctx read(UUID revId, String permission) {
        Map<String, Object> rev = rpt.get("report_revision", revId);
        Map<String, Object> report = rpt.get("report", RptJdbc.uuid(rev, "report_id"));
        access.requireProject(permission, RptJdbc.uuid(report, "project_id"));
        return new Ctx(rev, report);
    }

    public void requireStatus(Ctx c, ReportStatus... allowed) {
        Set<ReportStatus> s = EnumSet.noneOf(ReportStatus.class);
        s.addAll(List.of(allowed));
        if (!s.contains(c.status())) {
            throw new DomainException(RptCodes.STATE, "현재 상태(%s)에서는 할 수 없습니다 — 허용: %s".formatted(c.status(), s));
        }
    }

    /** A6 전이 검사 후 리비전 상태 갱신 + 보고서 상태 동기화(현재 리비전일 때) */
    public void transition(Ctx c, ReportStatus target) {
        c.status().transitionTo(target);
        rpt.jdbc().update("UPDATE rpt.report_revision SET status = ?, version = version + 1, updated_at = now(), "
                + "updated_by = ? WHERE id = ?", target.name(), actorId(), c.revId());
        syncReport(c.reportId());
    }

    public void touch(UUID revId) {
        rpt.jdbc().update("UPDATE rpt.report_revision SET version = version + 1, updated_at = now(), updated_by = ? "
                + "WHERE id = ?", actorId(), revId);
    }

    public void syncReport(UUID reportId) {
        rpt.jdbc().update("""
                UPDATE rpt.report r SET status = v.status, version = r.version + 1, updated_at = now(), updated_by = ?
                  FROM rpt.report_revision v
                 WHERE r.id = ? AND v.report_id = r.id AND v.rev_no = r.current_rev""", actorId(), reportId);
    }

    /**
     * 본문 정규 해시 — 섹션 · 블록(종류 · 텍스트 · 데이터) · 등급(산정 · 확정 · 점수). 렌더 시 기록하고, 검토 요청 · 발행 때
     * 최신 렌더와 같아야 한다(편집 후 재렌더 강제).
     */
    public String contentHash(UUID revId) {
        List<Map<String, Object>> blocks = rpt.jdbc().queryForList("""
                SELECT s.section_key, s.title, s.order_no AS s_order, b.order_no, b.kind, b.text, b.data::text AS data
                  FROM rpt.section s LEFT JOIN rpt.block b ON b.section_id = s.id
                 WHERE s.revision_id = ? ORDER BY s.order_no, b.order_no""", revId);
        List<Object> parts = new ArrayList<>();
        for (Map<String, Object> b : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("s", b.get("section_key"));
            m.put("t", b.get("title"));
            m.put("k", b.get("kind"));
            m.put("x", b.get("text"));
            m.put("d", b.get("data") == null ? null : RptJdbc.tree(b.get("data")));
            parts.add(m);
        }
        List<Map<String, Object>> g = rpt.jdbc().queryForList(
                "SELECT computed_grade, final_grade, weighted_score, result_sha256 FROM rpt.grade_result WHERE revision_id = ?",
                revId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("blocks", parts);
        root.put("grade", g.isEmpty() ? null : g.get(0));
        return RptJson.sha256(root);
    }

    public void storeContentHash(UUID revId) {
        rpt.jdbc().update("UPDATE rpt.report_revision SET content_hash = ? WHERE id = ?", contentHash(revId), revId);
    }
}
