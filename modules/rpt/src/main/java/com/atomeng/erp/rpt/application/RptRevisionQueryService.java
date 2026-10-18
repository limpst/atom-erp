package com.atomeng.erp.rpt.application;

import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptRevisionQuery;
import com.atomeng.erp.rpt.domain.RptJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * rpt::api {@link RptRevisionQuery} 구현 — QAR 이 쓰는 공개 조회(권한 검사는 호출 모듈의 웹 경계 책임).
 */
@Service
public class RptRevisionQueryService implements RptRevisionQuery {

    private final RptJdbc rpt;

    public RptRevisionQueryService(RptJdbc rpt) {
        this.rpt = rpt;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnchorSet> anchors(UUID revisionId) {
        List<Map<String, Object>> rows = rpt.jdbc().queryForList(
                "SELECT anchors::text AS anchors, anchors_sha256 FROM rpt.revision_anchor WHERE revision_id = ?", revisionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        JsonNode n = RptJdbc.tree(rows.get(0).get("anchors"));
        List<Anchor> anchors = new ArrayList<>();
        for (JsonNode a : n.path("anchors")) {
            List<Rect> rects = new ArrayList<>();
            for (JsonNode r : a.path("rects")) {
                rects.add(new Rect(r.path("page").asInt(), r.path("x").decimalValue(), r.path("y").decimalValue(),
                        r.path("w").decimalValue(), r.path("h").decimalValue()));
            }
            anchors.add(new Anchor(UUID.fromString(a.path("blockId").asText()), a.path("sectionKey").asText(),
                    a.path("kind").asText(), a.path("page").asInt(), List.copyOf(rects), a.path("textHash").asText()));
        }
        return Optional.of(new AnchorSet(revisionId, n.path("source").asText(), n.path("unit").asText(),
                n.path("pageCount").asInt(), RptJdbc.str(rows.get(0), "anchors_sha256"), List.copyOf(anchors)));
    }

    private static final class Acc {
        final TreeSet<String> sources = new TreeSet<>();
        int count;
        Instant first;
        Instant last;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SectionEditors> sectionEditors(UUID revisionId) {
        Map<String, Map<UUID, Acc>> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : rpt.jdbc().queryForList("""
                SELECT e.section_key, e.editor_id, e.source, e.edited_at
                  FROM rpt.block_edit e LEFT JOIN rpt.section s ON s.revision_id = e.revision_id AND s.section_key = e.section_key
                 WHERE e.revision_id = ? ORDER BY coalesce(s.order_no, 999), e.section_key, e.id""", revisionId)) {
            Instant at = RptJdbc.instant(r.get("edited_at"));
            Acc a = acc.computeIfAbsent(RptJdbc.str(r, "section_key"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(RptJdbc.uuid(r, "editor_id"), k -> new Acc());
            a.sources.add(RptJdbc.str(r, "source"));
            a.count++;
            if (a.first == null) {
                a.first = at;
            }
            a.last = at;
        }
        List<SectionEditors> out = new ArrayList<>();
        acc.forEach((key, editors) -> {
            List<Editor> list = new ArrayList<>();
            editors.forEach((id, a) -> list.add(new Editor(id, List.copyOf(a.sources), a.count, a.first, a.last)));
            out.add(new SectionEditors(key, List.copyOf(list)));
        });
        return out;
    }

    /** REST 응답용(같은 내용) */
    public JsonNode anchorsJson(UUID revisionId) {
        return anchors(revisionId).map(RptJson::tree).orElse(null);
    }
}
