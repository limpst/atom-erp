package com.atomeng.erp.qar.adapter.out.ai;

import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;

/**
 * Atom AI Core = AIP 모듈 동기 Job(aip::api {@link AipJobApi}, 설계 11 v0.3 B6 — QAR AI 검토는 이벤트가 아니라 동기 호출).
 * REPORT_REVIEW Job 으로 요청하면 AIP 가 Job · 감사 · 레지스트리(모델 별칭)를 기록하고 Python 워커(/internal/v1/report/review)가
 * 로컬 vLLM 에 스키마 강제 생성을 요청한다. AIP 워커 미설정(기본)이면 DISABLED → AI 단계 스킵(QAR A8, 기존 동작과 같음).
 * 멱등 키 = 라운드 — 같은 라운드 재시도에서 이미 성공한 검토는 다시 추론하지 않는다.
 */
public class AipAtomAiCoreAdapter implements AtomAiCorePort {

    private final AipJobApi aip;
    private final Duration timeout;

    public AipAtomAiCoreAdapter(AipJobApi aip, Duration timeout) {
        this.aip = aip;
        this.timeout = timeout;
    }

    @Override
    public Response review(Request request) {
        ObjectNode body = QarJdbc.MAPPER.createObjectNode();
        body.put("roundId", request.roundId() == null ? null : request.roundId().toString());
        body.put("reportType", request.reportType());
        body.put("locale", request.locale() == null ? "ko" : request.locale());
        ArrayNode sections = body.putArray("sections");
        for (SectionIn s : request.sections()) {
            ObjectNode so = sections.addObject();
            so.put("key", s.key());
            ArrayNode blocks = so.putArray("blocks");
            for (BlockIn b : s.blocks()) {
                blocks.addObject().put("id", b.id().toString()).put("text", b.text());
            }
        }
        body.set("facts", request.facts());
        ArrayNode checks = body.putArray("checks");
        if (request.checks() != null) {
            request.checks().forEach(checks::add);
        }
        AipJobApi.SyncResult r;
        try {
            r = aip.runSync(new AipJobApi.SyncRequest(AipServiceCode.REPORT_REVIEW, "QAR", null,
                    request.roundId() == null ? null : "qar-review-" + request.roundId(), body, timeout));
        } catch (AipJobApi.AipTimeoutException e) {
            throw new AiTimeoutException("AIP 검토 응답 시간 초과", e);
        } catch (AipJobApi.AipUnavailableException e) {
            throw new AiUnavailableException(e.getMessage(), e);   // DISABLED 접두어 유지 → 스킵 사유 DISABLED
        }
        JsonNode out = r.output();
        String model = out.path("model").asText(r.model() == null ? "aip" : r.model());
        return new Response("aip:" + model + (r.modelVersion() == null ? "" : "@" + r.modelVersion()) + "#job=" + r.jobId(),
                VllmAtomAiCoreClient.findings(out.path("findings")));
    }
}
