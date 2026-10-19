package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.rpt.api.RptRevisionQuery;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * AI 소견 초안 (설계 06 Step 12 · 11 v0.3 B6/B7.6 — RPT → aip::api REPORT_DRAFT 동기 Job). 리비전 스냅샷 결함을 숫자 집계(facts)로
 * 만들어 보내고, AIP 워커가 근거 검색 → JSON 스키마 강제 생성 → 인용 · 수치 검증기를 통과한 초안만 돌려준다.
 * 결과는 <b>제안</b>이며 섹션 본문에 자동 반영하지 않는다(기술자 편집 필수, BR-AIP-001 취지). 워커 미설정 · 장애여도 보고서 작성은 그대로.
 */
@Service
public class AiDraftService {

    private static final Map<String, String> KO = Map.of("CRACK", "균열", "SPALL", "박리", "EFFLORESCENCE", "백태",
            "REBAR_EXPOSURE", "철근노출", "LEAK", "누수", "SCALING", "박락");
    private static final Duration TIMEOUT = Duration.ofMinutes(3);   // NFR P2 p95 3분

    private final RptRevisionQueryService query;
    private final RptAccess access;
    private final AipJobApi aip;
    private final ObjectMapper mapper;

    public AiDraftService(RptRevisionQueryService query, RptAccess access, AipJobApi aip, ObjectMapper mapper) {
        this.query = query;
        this.access = access;
        this.aip = aip;
        this.mapper = mapper;
    }

    /** 결함 집계(수치만) — 키 = "{유형}.count" · "crack.max_width_mm" · "{유형}.area_m2" · "defect.count" */
    static Map<String, BigDecimal> facts(RptRevisionQuery.RevisionContent c) {
        Map<String, BigDecimal> f = new LinkedHashMap<>();
        BigDecimal maxW = null;
        int total = 0;
        for (RptRevisionQuery.Defect d : c.defects()) {
            total++;
            String t = d.defectType() == null ? "unknown" : d.defectType().toLowerCase(java.util.Locale.ROOT);
            f.merge(t + ".count", BigDecimal.ONE, BigDecimal::add);
            if (d.areaM2() != null) {
                f.merge(t + ".area_m2", d.areaM2(), BigDecimal::add);
            }
            if ("CRACK".equals(d.defectType()) && d.widthMm() != null && (maxW == null || d.widthMm().compareTo(maxW) > 0)) {
                maxW = d.widthMm();
            }
        }
        if (maxW != null) {
            f.put("crack.max_width_mm", maxW);
        }
        f.put("defect.count", BigDecimal.valueOf(total));
        return f;
    }

    private static String shortHash(String h) {
        return h == null ? "none" : h.substring(0, Math.min(16, h.length()));
    }

    public Map<String, Object> draft(UUID revisionId) {
        RptRevisionQuery.RevisionContent c = query.content(revisionId)
                .orElseThrow(() -> new DomainException(RptCodes.NOT_FOUND, "리비전 없음: " + revisionId));
        access.requireProject("rpt:report:update", c.revision().projectId());
        Map<String, BigDecimal> facts = facts(c);
        Set<String> words = new LinkedHashSet<>();
        c.defects().forEach(d -> words.add(KO.getOrDefault(d.defectType(), "결함")));
        c.defects().forEach(d -> {
            if (d.memberType() != null) {
                words.add(d.memberType());
            }
        });
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode fj = payload.putObject("facts");
        facts.forEach((k, v) -> fj.put(k, v));
        payload.put("query", String.join(" ", words) + " 소견");
        AipJobApi.SyncResult r;
        try {
            r = aip.runSync(new AipJobApi.SyncRequest(AipServiceCode.REPORT_DRAFT, "RPT", c.revision().projectId(),
                    "rpt-draft-" + revisionId + "-" + shortHash(c.revision().contentHash()), payload, TIMEOUT));
        } catch (AipJobApi.AipTimeoutException e) {
            throw new DomainException(RptCodes.AI_DRAFT_TIMEOUT, "AI 소견 초안 응답 시간 초과");
        } catch (AipJobApi.AipUnavailableException e) {
            throw new DomainException(RptCodes.AI_DRAFT_UNAVAILABLE, "AI 소견 초안 사용 불가: " + e.getMessage());
        }
        JsonNode out = r.output();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("revisionId", revisionId);
        v.put("jobId", r.jobId());
        v.put("status", out.path("status").asText("FAILED"));
        v.put("draft", out.path("draft").isMissingNode() || out.path("draft").isNull() ? null : out.get("draft"));
        v.put("errors", out.path("errors"));
        v.put("retrievedChunkIds", out.path("retrievedChunkIds"));
        v.put("facts", facts);
        v.put("model", r.model() == null ? null : r.model() + "@" + r.modelVersion());
        v.put("replayed", r.replayed());
        v.put("suggestionOnly", true);
        return v;
    }
}
