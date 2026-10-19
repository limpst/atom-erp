package com.atomeng.erp.fld.application;

import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.api.FldSurveyQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link FldSurveyQuery} 구현 — 프로젝션(defect_current) · 사진 읽기 전용 조회. 위치 노드 정보는 fac::api 로 채운다. */
@Service
@Transactional(readOnly = true)
public class FldQueryService implements FldSurveyQuery {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FldJdbc fld;
    private final FacQueryApi fac;

    public FldQueryService(FldJdbc fld, FacQueryApi fac) {
        this.fld = fld;
        this.fac = fac;
    }

    private RoundSummary summary(Map<String, Object> r) {
        UUID id = (UUID) r.get("id");
        return new RoundSummary(id, (UUID) r.get("project_id"), (UUID) r.get("building_id"),
                ((Number) r.get("round_no")).intValue(), (String) r.get("survey_type"), (String) r.get("status"),
                r.get("completed_at") == null ? null : ((Timestamp) r.get("completed_at")).toInstant(), fld.maxSeq(id));
    }

    @Override
    public Optional<RoundSummary> round(UUID roundId) {
        return fld.jdbc().queryForList("SELECT * FROM fld.survey_round WHERE id = ?", roundId).stream().findFirst()
                .map(this::summary);
    }

    @Override
    public Optional<RoundSummary> latestRound(UUID projectId, UUID buildingId) {
        return fld.jdbc().queryForList("""
                SELECT * FROM fld.survey_round WHERE project_id = ? AND (?::uuid IS NULL OR building_id = ?)
                 ORDER BY (status = 'COMPLETED') DESC, round_no DESC LIMIT 1""", projectId, buildingId, buildingId)
                .stream().findFirst().map(this::summary);
    }

    @Override
    public SurveyExport export(UUID roundId) {
        RoundSummary round = round(roundId).orElseThrow(() -> new IllegalArgumentException("조사 회차 없음: " + roundId));
        Map<UUID, Optional<FacQueryApi.LocationNode>> nodes = new HashMap<>();
        List<DefectExport> out = new ArrayList<>();
        for (Map<String, Object> d : fld.jdbc().queryForList("""
                SELECT id, code, location_node_id, type_code, subtype_code, status, attrs::text AS attrs,
                       measure_meta::text AS meta
                  FROM fld.defect_current WHERE round_id = ? AND status <> 'REMOVED' ORDER BY code""", roundId)) {
            JsonNode attrs = fld.tree((String) d.get("attrs"));
            JsonNode width = fld.tree((String) d.get("meta")).path("width_mm");
            UUID nodeId = (UUID) d.get("location_node_id");
            Optional<FacQueryApi.LocationNode> node = nodeId == null ? Optional.empty()
                    : nodes.computeIfAbsent(nodeId, fac::locationNode);
            UUID equipment = width.hasNonNull("equipmentId") && !width.get("equipmentId").asText().equals("null")
                    ? UUID.fromString(width.get("equipmentId").asText()) : null;
            LocalDate measuredOn = null;
            if (width.hasNonNull("measuredAt")) {
                measuredOn = OffsetDateTime.parse(width.get("measuredAt").asText()).atZoneSameInstant(SEOUL).toLocalDate();
            }
            out.add(new DefectExport((UUID) d.get("id"), (String) d.get("code"), round.buildingId(), nodeId,
                    node.map(FacQueryApi.LocationNode::floorCode).orElse(null),
                    node.map(FacQueryApi.LocationNode::memberType).orElse(null),
                    node.map(FacQueryApi.LocationNode::code).orElse(null), (String) d.get("type_code"),
                    (String) d.get("subtype_code"), (String) d.get("status"), dec(attrs, "width_mm"),
                    width.path("method").asText(null), equipment, measuredOn, dec(attrs, "length_m"),
                    dec(attrs, "area_m2"), attrs.path("memo").asText(null)));
        }
        return new SurveyExport(round, out, round.lastSeq());
    }

    private static BigDecimal dec(JsonNode attrs, String f) {
        return attrs.path(f).isNumber() ? attrs.get(f).decimalValue() : null;
    }

    @Override
    public List<PhotoExport> verifiedPhotos(UUID roundId) {
        return fld.jdbc().query("""
                SELECT p.id, p.defect_id, d.code, p.taken_at, p.server_sha256 FROM fld.photo p
                  LEFT JOIN fld.defect_current d ON d.id = p.defect_id
                 WHERE p.round_id = ? AND p.upload_state = 'VERIFIED' AND NOT p.invalidated
                   AND (d.id IS NULL OR d.status <> 'REMOVED') ORDER BY d.code, p.taken_at, p.id""",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp("taken_at");
                    return new PhotoExport(rs.getObject("id", UUID.class), rs.getObject("defect_id", UUID.class),
                            rs.getString("code"), t == null ? null : t.toInstant().atZone(SEOUL).toLocalDate(),
                            rs.getString("server_sha256"));
                }, roundId);
    }
}
