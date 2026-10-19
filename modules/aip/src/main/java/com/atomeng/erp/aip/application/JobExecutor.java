package com.atomeng.erp.aip.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.atomeng.erp.aip.application.port.JobImageLoader;
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
    private final JobImageLoader images;

    public JobExecutor(AipWorkerPort worker, AipProperties props, JobImageLoader images) {
        this.worker = worker;
        this.props = props;
        this.images = images;
    }

    public boolean enabled() {
        return worker.enabled();
    }

    /**
     * @param imageAttachmentId 이미지 서비스(CRACK_DETECT · CRACK_MEASURE)의 입력 첨부. 보안 검토 AIP-03: 워커에 보내는 이미지는
     *                          언제나 서버가 이 첨부의 저장 원본에서 읽는다(params.imageB64 는 무시 · 제거).
     */
    public Execution execute(AipServiceCode code, UUID jobId, JsonNode payload, ModelRegistryService.Route route,
                             Duration timeout, UUID imageAttachmentId) {
        if (!code.supported()) {
            throw new DomainException(AipCodes.UNSUPPORTED_SERVICE, "이번 단계 미지원 서비스: " + code);
        }
        return switch (code) {
            case CRACK_DETECT -> crackDetect(jobId, payload, route, timeout, serverImage(code, imageAttachmentId));
            case CRACK_MEASURE -> single(AipWorkerPort.CRACK_MEASURE,
                    measureBody(payload, jobId, serverImage(code, imageAttachmentId)), timeout, null);
            case REPORT_DRAFT -> single(AipWorkerPort.REPORT_DRAFT, withJob(payload, jobId, route), timeout, "retrievedChunkIds");
            case REPORT_REVIEW -> single(AipWorkerPort.REPORT_REVIEW, withJob(payload, jobId, route), timeout, null);
            case KB_SEARCH -> single(AipWorkerPort.KB_SEARCH, payload, timeout, null);
            default -> throw new DomainException(AipCodes.UNSUPPORTED_SERVICE, "이번 단계 미지원 서비스: " + code);
        };
    }

    /** 이미지 서비스는 입력 첨부가 필수 — 원본 바이트를 서버가 읽어 base64 로 만든다 */
    public static boolean imageService(AipServiceCode code) {
        return code == AipServiceCode.CRACK_DETECT || code == AipServiceCode.CRACK_MEASURE;
    }

    private String serverImage(AipServiceCode code, UUID attachmentId) {
        if (attachmentId == null) {
            throw new DomainException(AipCodes.INVALID, code + " 는 입력 첨부(inputs[0].attachmentId)가 필요합니다");
        }
        return java.util.Base64.getEncoder().encodeToString(images.load(attachmentId));
    }

    /** 측정 요청 — 워커 계약(MeasureRequest, extra=forbid)의 허용 필드만 옮기고 이미지는 서버 원본 */
    static final List<String> MEASURE_KEYS = List.of("maskB64", "scale", "boundariesMm", "darkThreshold",
            "minComponentPx", "pruneSpurPx");

    private static ObjectNode measureBody(JsonNode p, UUID jobId, String imageB64) {
        ObjectNode m = AipJdbc.MAPPER.createObjectNode();
        m.put("jobId", jobId.toString());
        m.put("imageB64", imageB64);
        for (String k : MEASURE_KEYS) {
            if (p != null && p.has(k)) {
                m.set(k, p.get(k));
            }
        }
        return m;
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

    private Execution crackDetect(UUID jobId, JsonNode params, ModelRegistryService.Route route, Duration timeout,
                                  String imageB64) {
        JsonNode p = params == null ? AipJdbc.MAPPER.createObjectNode() : params;
        long t0 = System.nanoTime();
        ObjectNode score = AipJdbc.MAPPER.createObjectNode();
        score.put("jobId", jobId.toString());
        score.put("imageB64", imageB64);
        score.put("model", route != null && route.modelName() != null ? route.modelName() : "crack");
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
                f.put("imageB64", imageB64);
                f.put("model", "gemma-4-12b-it");
                if (p.has("context")) {
                    f.set("context", p.get("context"));
                }
                out.set("finding", worker.post(AipWorkerPort.FINDINGS, f, timeout).body());
            }
            if (chainHas(p.path("chain"), "CRACK_MEASURE")) {
                out.set("measure", worker.post(AipWorkerPort.CRACK_MEASURE, measureBody(p, jobId, imageB64), timeout).body());
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
