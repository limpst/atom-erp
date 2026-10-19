package com.atomeng.erp.aip.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.atomeng.erp.aip.domain.AipCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 서비스 코드 → 워커 엔드포인트 호출 (트랜잭션 밖에서만 부른다). CRACK_DETECT 는 체인: 균열 점수(crack LoRA 로그우도) →
 * P(crack) ≥ minConfidence 이면 소견(JSON 스키마 강제) → chain 에 CRACK_MEASURE 가 있으면 측정(설계 A3.2 · B4.2).
 */
@Component
public class JobExecutor {

    /** output = 워커 응답(체인은 합친 객체), provenance = 모델 출처(있으면), adapter = 서빙 LoRA */
    public record Execution(JsonNode output, JsonNode provenance, String adapter, List<String> retrievedChunkIds,
                            long latencyMs) {}

    private final AipWorkerPort worker;
    private final AipProperties props;

    public JobExecutor(AipWorkerPort worker, AipProperties props) {
        this.worker = worker;
        this.props = props;
    }

    public boolean enabled() {
        return worker.enabled();
    }

    public Execution execute(AipServiceCode code, UUID jobId, JsonNode payload, ModelRegistryService.Route route,
                             Duration timeout) {
        if (!code.supported()) {
            throw new DomainException(AipCodes.UNSUPPORTED_SERVICE, "이번 단계 미지원 서비스: " + code);
        }
        return switch (code) {
            case CRACK_DETECT -> crackDetect(jobId, payload, route, timeout);
            case CRACK_MEASURE -> single(AipWorkerPort.CRACK_MEASURE, withJob(payload, jobId, null), timeout, null);
            case REPORT_DRAFT -> single(AipWorkerPort.REPORT_DRAFT, withJob(payload, jobId, route), timeout, "retrievedChunkIds");
            case REPORT_REVIEW -> single(AipWorkerPort.REPORT_REVIEW, withJob(payload, jobId, route), timeout, null);
            case KB_SEARCH -> single(AipWorkerPort.KB_SEARCH, payload, timeout, null);
            default -> throw new DomainException(AipCodes.UNSUPPORTED_SERVICE, "이번 단계 미지원 서비스: " + code);
        };
    }

    private static ObjectNode withJob(JsonNode payload, UUID jobId, ModelRegistryService.Route route) {
        ObjectNode o = payload != null && payload.isObject() ? ((ObjectNode) payload).deepCopy() : AipJdbc.MAPPER.createObjectNode();
        o.put("jobId", jobId.toString());
        if (route != null && route.modelName() != null && !o.hasNonNull("model")) {
            o.put("model", route.modelName());
        }
        return o;
    }

    private Execution single(String path, JsonNode body, Duration timeout, String chunkField) {
        AipWorkerPort.Response r = worker.post(path, body, timeout);
        List<String> chunks = new ArrayList<>();
        if (chunkField != null) {
            r.body().path(chunkField).forEach(n -> chunks.add(n.asText()));
        }
        JsonNode prov = r.body().path("provenance");
        return new Execution(r.body(), prov.isMissingNode() ? null : prov, prov.path("lora").asText(null),
                chunks.isEmpty() ? null : chunks, r.latencyMs());
    }

    private Execution crackDetect(UUID jobId, JsonNode p, ModelRegistryService.Route route, Duration timeout) {
        if (p == null || !p.hasNonNull("imageB64")) {
            throw new DomainException(AipCodes.INVALID, "CRACK_DETECT 는 params.imageB64 가 필요합니다(MinIO 입력 로더는 후속)");
        }
        long t0 = System.nanoTime();
        ObjectNode score = AipJdbc.MAPPER.createObjectNode();
        score.put("jobId", jobId.toString());
        score.put("imageB64", p.get("imageB64").asText());
        score.put("model", route != null && route.modelName() != null ? route.modelName() : "crack");
        if (p.hasNonNull("prompt")) {
            score.put("prompt", p.get("prompt").asText());
        }
        if (p.has("labels")) {
            score.set("labels", p.get("labels"));
        }
        AipWorkerPort.Response s = worker.post(AipWorkerPort.CRACK_SCORE, score, timeout);
        double pCrack = s.body().path("pCrack").asDouble(0);
        double min = p.path("minConfidence").asDouble(props.suggestion().minConfidence());
        ObjectNode out = AipJdbc.MAPPER.createObjectNode();
        out.set("score", s.body());
        out.put("detected", pCrack >= min);
        out.putNull("finding");
        out.putNull("measure");
        if (pCrack >= min) {
            if (p.path("finding").asBoolean(true)) {
                ObjectNode f = AipJdbc.MAPPER.createObjectNode();
                f.put("jobId", jobId.toString());
                f.put("imageB64", p.get("imageB64").asText());
                f.put("model", p.path("findingModel").asText("gemma-4-12b-it"));
                if (p.has("context")) {
                    f.set("context", p.get("context"));
                }
                out.set("finding", worker.post(AipWorkerPort.FINDINGS, f, timeout).body());
            }
            if (chainHas(p.path("chain"), "CRACK_MEASURE")) {
                ObjectNode m = AipJdbc.MAPPER.createObjectNode();
                m.put("jobId", jobId.toString());
                m.put("imageB64", p.get("imageB64").asText());
                for (String k : List.of("maskB64", "scale", "boundariesMm", "darkThreshold", "minComponentPx", "pruneSpurPx")) {
                    if (p.has(k)) {
                        m.set(k, p.get(k));
                    }
                }
                out.set("measure", worker.post(AipWorkerPort.CRACK_MEASURE, m, timeout).body());
            }
        }
        JsonNode prov = s.body().path("provenance");
        return new Execution(out, prov.isMissingNode() ? null : prov, prov.path("lora").asText(null), null,
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static boolean chainHas(JsonNode chain, String code) {
        if (chain instanceof ArrayNode a) {
            for (JsonNode n : a) {
                if (code.equals(n.asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
