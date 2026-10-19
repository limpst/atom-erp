package com.atomeng.erp.aip.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipJobApi.AipTimeoutException;
import com.atomeng.erp.aip.api.AipJobApi.AipUnavailableException;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 비동기 Job 1건 처리(11-IMPL Step 2 워커 루프의 ERP 측 구현): 디큐(트랜잭션) → 워커 호출(트랜잭션 밖) → 완료/실패(트랜잭션).
 * 워커가 비활성이면 아무것도 하지 않는다(Job 은 QUEUED 대기 — NFR-AIP-03). 협조적 취소: 완료 전에 cancel_requested 면 CANCELLED.
 * 가용 VRAM 은 vLLM 상주분을 뺀 비-vLLM 예산(16GB × (1 − 0.72) ≈ 4.5GB, 11-IMPL Step 2 함정)으로 본다.
 */
@Service
public class AsyncJobProcessor {

    static final int NON_VLLM_BUDGET_MB = 4500;

    private final JobService jobs;
    private final JobExecutor executor;
    private final ModelRegistryService registry;
    private final AipProperties props;
    private final String workerId = "erp-async:" + ManagementFactory.getRuntimeMXBean().getName();

    public AsyncJobProcessor(JobService jobs, JobExecutor executor, ModelRegistryService registry, AipProperties props) {
        this.jobs = jobs;
        this.executor = executor;
        this.registry = registry;
        this.props = props;
    }

    public String workerId() {
        return workerId;
    }

    /** @return 처리한 Job id(없으면 empty) */
    public Optional<UUID> runNext() {
        return runNext(workerId);
    }

    public Optional<UUID> runNext(String worker) {
        if (!executor.enabled()) {
            return Optional.empty();
        }
        List<String> services = Arrays.stream(AipServiceCode.values()).filter(AipServiceCode::supported)
                .map(Enum::name).toList();
        Duration timeout = props.worker().defaultTimeout();
        long lease = Math.max(props.scheduler().lease().toSeconds(), timeout.toSeconds() + 30);
        Optional<Map<String, Object>> next = jobs.dequeue(worker, services, NON_VLLM_BUDGET_MB, lease);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> j = next.get();
        UUID id = AipJdbc.uuid(j, "id");
        AipServiceCode code = AipServiceCode.valueOf(AipJdbc.str(j, "service_code"));
        JsonNode params = AipJdbc.tree(j.get("params"));
        JsonNode in = AipJdbc.tree(j.get("input_refs"));
        UUID subject = in.isArray() && !in.isEmpty() && in.get(0).hasNonNull("attachmentId")
                ? UUID.fromString(in.get(0).get("attachmentId").asText()) : AipJdbc.uuid(j, "project_id");
        try {
            JobExecutor.Execution exec = executor.execute(code, id, params, registry.resolve(code.name(), subject), timeout);
            if (jobs.cancelIfRequested(id, worker)) {
                return Optional.of(id);
            }
            jobs.complete(id, worker, exec);
        } catch (AipTimeoutException e) {
            jobs.fail(id, worker, "TIMEOUT", e.getMessage(), true);
        } catch (AipUnavailableException e) {
            jobs.fail(id, worker, "WORKER_UNAVAILABLE", e.getMessage(), true);
        } catch (DomainException e) {
            jobs.fail(id, worker, e.code(), e.getMessage(), false);
        } catch (RuntimeException e) {
            jobs.fail(id, worker, "WORKER_ERROR", e.toString(), true);
        }
        return Optional.of(id);
    }
}
