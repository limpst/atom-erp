package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.RptJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 섹션 · 블록 (FR-RPT-011, BR-RPT-001). 블록 id 는 anchors.json · QAR 코멘트의 기준이라 편집해도 유지된다.
 * 스냅샷 파생 블록(표 · 그림 · 바인딩 문장)은 잠금 — 수정 · 삭제 시 422 RPT-422-002. 편집은 COMPOSING 에서만,
 * 섹션 단위 낙관적 잠금(If-Match "v{n}"). 모든 생성 · 수정 · 삭제는 rpt.block_edit(append-only)에 남는다.
 */
@Service
public class SectionService {

    /** 새 블록(자동 생성용) */
    public record NewBlock(String kind, String text, Map<String, Object> data, boolean locked) {

        public static NewBlock locked(String kind, String text, Map<String, Object> data) {
            return new NewBlock(kind, text, data, true);
        }

        public static NewBlock editable(String kind, String text) {
            return new NewBlock(kind, text, null, false);
        }

        public static NewBlock table(List<String> columns, List<List<String>> rows) {
            StringBuilder sb = new StringBuilder(String.join(" | ", columns));
            rows.forEach(r -> sb.append('\n').append(String.join(" | ", r)));
            return new NewBlock("TABLE", sb.toString(), Map.of("columns", columns, "rows", rows), true);
        }

        public static NewBlock image(String caption, String objectKey) {
            return new NewBlock("IMAGE", caption, Map.of("objectKey", objectKey), true);
        }
    }

    /** 편집 요청 블록: blockId 가 있으면 기존 블록, 없으면 새 블록 */
    public record EditBlock(UUID blockId, String kind, String text) {}

    private final RevisionSupport sup;
    private final RptJdbc rpt;

    public SectionService(RevisionSupport sup) {
        this.sup = sup;
        this.rpt = sup.jdbc();
    }

    /** compose: AUTO 섹션 재생성, 사람이 고친 섹션(source=HUMAN)은 유지 */
    void regenerate(UUID revId, List<String[]> order, Map<String, List<NewBlock>> content) {
        UUID by = sup.actorId();
        Map<String, Map<String, Object>> existing = new HashMap<>();
        rpt.jdbc().queryForList("SELECT id, section_key, source FROM rpt.section WHERE revision_id = ?", revId)
                .forEach(r -> existing.put(RptJdbc.str(r, "section_key"), r));
        for (int i = 0; i < order.size(); i++) {
            String key = order.get(i)[0];
            Map<String, Object> ex = existing.get(key);
            if (ex != null && "HUMAN".equals(RptJdbc.str(ex, "source"))) {
                continue;
            }
            UUID sid;
            if (ex == null) {
                sid = UuidV7.next();
                rpt.jdbc().update("""
                        INSERT INTO rpt.section(id, revision_id, section_key, order_no, title, source, created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, 'AUTO', ?, ?)""", sid, revId, key, i + 1, order.get(i)[1], by, by);
            } else {
                sid = RptJdbc.uuid(ex, "id");
                for (Map<String, Object> b : rpt.jdbc().queryForList(
                        "SELECT id, text_hash FROM rpt.block WHERE section_id = ?", sid)) {
                    logEdit(revId, key, RptJdbc.uuid(b, "id"), "DELETE", "AUTO", RptJdbc.str(b, "text_hash"));
                }
                rpt.jdbc().update("DELETE FROM rpt.block WHERE section_id = ?", sid);
                rpt.jdbc().update("UPDATE rpt.section SET version = version + 1, updated_at = now(), updated_by = ? "
                        + "WHERE id = ?", by, sid);
            }
            int n = 0;
            for (NewBlock b : content.getOrDefault(key, List.of())) {
                insertBlock(revId, sid, key, ++n, b.kind(), b.text(), b.data(), b.locked(), "AUTO");
            }
        }
    }

    private UUID insertBlock(UUID revId, UUID sid, String key, int orderNo, String kind, String text,
                             Map<String, Object> data, boolean locked, String source) {
        UUID id = UuidV7.next();
        String hash = RptJson.textHash(text);
        rpt.jdbc().update("""
                INSERT INTO rpt.block(id, revision_id, section_id, section_key, order_no, kind, text, data, locked, text_hash,
                                      source, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""", id, revId, sid, key, orderNo, kind,
                text == null ? "" : text, data == null ? null : RptJdbc.json(data), locked, hash, source, sup.actorId(),
                sup.actorId());
        logEdit(revId, key, id, "CREATE", source, hash);
        return id;
    }

    private void logEdit(UUID revId, String key, UUID blockId, String action, String source, String hash) {
        rpt.jdbc().update("""
                INSERT INTO rpt.block_edit(revision_id, section_key, block_id, action, source, editor_id, text_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", revId, key, blockId, action, source, sup.actorId(), hash);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID revId) {
        sup.read(revId, "rpt:report:read");
        return rpt.jdbc().queryForList("SELECT section_key FROM rpt.section WHERE revision_id = ? ORDER BY order_no", revId)
                .stream().map(r -> view(revId, RptJdbc.str(r, "section_key"))).toList();
    }

    Map<String, Object> view(UUID revId, String key) {
        Map<String, Object> s = section(revId, key);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sectionKey", key);
        v.put("title", s.get("title"));
        v.put("orderNo", s.get("order_no"));
        v.put("source", s.get("source"));
        v.put("version", RptJdbc.version(s));
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Map<String, Object> b : rpt.jdbc().queryForList("""
                SELECT id, kind, text, data::text AS data, locked, text_hash, source FROM rpt.block
                 WHERE section_id = ? ORDER BY order_no""", s.get("id"))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("blockId", b.get("id"));
            x.put("kind", b.get("kind"));
            x.put("text", b.get("text"));
            x.put("data", RptJdbc.tree(b.get("data")));
            x.put("locked", b.get("locked"));
            x.put("textHash", RptJdbc.str(b, "text_hash"));
            x.put("source", b.get("source"));
            blocks.add(x);
        }
        v.put("blocks", blocks);
        return v;
    }

    private Map<String, Object> section(UUID revId, String key) {
        List<Map<String, Object>> rows = rpt.jdbc().queryForList(
                "SELECT * FROM rpt.section WHERE revision_id = ? AND section_key = ?", revId, key);
        if (rows.isEmpty()) {
            throw new DomainException(RptCodes.NOT_FOUND, "섹션 없음: " + key);
        }
        return rows.get(0);
    }

    /**
     * 섹션 저장: 요청 = 섹션의 새 블록 목록 전체. 잠금 블록은 같은 id · 같은 텍스트로 모두 있어야 하고(BR-RPT-001),
     * 잠금 해제 블록은 수정 · 추가 · 삭제 가능(HEADING · PARAGRAPH 만).
     */
    @Transactional
    public Map<String, Object> update(UUID revId, String key, long expectedVersion, List<EditBlock> blocks) {
        Ctx c = sup.lock(revId, "rpt:report:update");
        sup.requireStatus(c, ReportStatus.COMPOSING);
        Map<String, Object> s = rpt.jdbc().queryForList(
                "SELECT * FROM rpt.section WHERE revision_id = ? AND section_key = ? FOR UPDATE", revId, key)
                .stream().findFirst().orElseThrow(() -> new DomainException(RptCodes.NOT_FOUND, "섹션 없음: " + key));
        IfMatch.check(expectedVersion, RptJdbc.version(s));
        UUID sid = RptJdbc.uuid(s, "id");
        Map<UUID, Map<String, Object>> current = new LinkedHashMap<>();
        rpt.jdbc().queryForList("SELECT * FROM rpt.block WHERE section_id = ? ORDER BY order_no", sid)
                .forEach(b -> current.put(RptJdbc.uuid(b, "id"), b));
        Set<UUID> kept = new HashSet<>();
        for (EditBlock b : blocks) {
            if (b.blockId() == null) {
                continue;
            }
            Map<String, Object> cur = current.get(b.blockId());
            if (cur == null) {
                throw new DomainException(RptCodes.INVALID, "이 섹션의 블록이 아님: " + b.blockId());
            }
            if (!kept.add(b.blockId())) {
                throw new DomainException(RptCodes.INVALID, "블록 중복: " + b.blockId());
            }
            if (Boolean.TRUE.equals(cur.get("locked")) && !RptJdbc.str(cur, "text").equals(b.text() == null ? ""
                    : b.text().strip())) {
                throw new DomainException(RptCodes.LOCKED_BLOCK,
                        "스냅샷 파생(잠금) 블록은 고칠 수 없습니다 — 수치는 원천(FLD) 수정 후 재동결: " + b.blockId());
            }
        }
        for (var e : current.entrySet()) {
            if (!kept.contains(e.getKey()) && Boolean.TRUE.equals(e.getValue().get("locked"))) {
                throw new DomainException(RptCodes.LOCKED_BLOCK, "스냅샷 파생(잠금) 블록은 지울 수 없습니다: " + e.getKey());
            }
        }
        // 순서 재배치: 임시로 음수 순번 → 최종 순번 (UNIQUE(section_id, order_no) DEFERRABLE)
        int n = 0;
        for (EditBlock b : blocks) {
            n++;
            if (b.blockId() == null) {
                requireEditableKind(b.kind());
                insertBlock(revId, sid, key, n, b.kind(), b.text() == null ? "" : b.text().strip(), null, false, "HUMAN");
                continue;
            }
            Map<String, Object> cur = current.get(b.blockId());
            String text = b.text() == null ? "" : b.text().strip();
            boolean changed = !RptJdbc.str(cur, "text").equals(text);
            if (changed) {
                requireEditableKind(RptJdbc.str(cur, "kind"));
                String hash = RptJson.textHash(text);
                rpt.jdbc().update("""
                        UPDATE rpt.block SET text = ?, text_hash = ?, source = 'HUMAN', order_no = ?, updated_at = now(),
                               updated_by = ? WHERE id = ?""", text, hash, n, sup.actorId(), b.blockId());
                logEdit(revId, key, b.blockId(), "UPDATE", "HUMAN", hash);
            } else if (RptJdbc.integer(cur, "order_no") != n) {
                rpt.jdbc().update("UPDATE rpt.block SET order_no = ? WHERE id = ?", n, b.blockId());
            }
        }
        for (var e : current.entrySet()) {
            if (!kept.contains(e.getKey())) {
                rpt.jdbc().update("DELETE FROM rpt.block WHERE id = ?", e.getKey());
                logEdit(revId, key, e.getKey(), "DELETE", "HUMAN", RptJdbc.str(e.getValue(), "text_hash"));
            }
        }
        rpt.jdbc().update("UPDATE rpt.section SET source = 'HUMAN', version = version + 1, updated_at = now(), "
                + "updated_by = ? WHERE id = ?", sup.actorId(), sid);
        sup.touch(revId);
        sup.storeContentHash(revId);
        return view(revId, key);
    }

    private static void requireEditableKind(String kind) {
        if (!"PARAGRAPH".equals(kind) && !"HEADING".equals(kind)) {
            throw new DomainException(RptCodes.LOCKED_BLOCK, "직접 작성할 수 있는 블록은 HEADING · PARAGRAPH 뿐입니다: " + kind);
        }
    }
}
