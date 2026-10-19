package com.atomeng.erp.aip.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.domain.JobStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * aip::api 동기 Job 구현 (설계 v0.3 B6 — RPT 초안 · QAR 검토). Job 행(mode SYNC) 생성 → RUNNING → 워커 호출(트랜잭션 밖,
 * 요청자 마감) → SUCCEEDED(감사 · aip.inference.completed) 또는 FAILED/DEAD(aip.job.failed). 재시도는 요청자가 같은 멱등 키로.
 */
@Service
public class SyncJobRunner implements AipJobApi {

    static final String SYNC_WORKER = "erp-sync";

    private final JobService jobs;
    private final JobExecutor executor;
    private final ModelRegistryService registry;
    private final AipProperties props;

    public SyncJobRunner(JobService jobs, JobExecutor executor, ModelRegistryService registry, AipProperties props) {
        this.jobs = jobs;
        this.executor = executor;
        this.registry = registry;
        this.props = props;
    }

    @Override
    public boolean enabled() {
        return executor.enabled();
    }

    @Override
    public SyncResult runSync(SyncRequest req) {
        if (!executor.enabled()) {
            throw new AipDisabledException("DISABLED: atom.aip.worker.base-url 미설정");
        }
        AipServiceCode code = req.serviceCode();
        if (code == null || !code.supported()) {
            throw new AipUnavailableException("UNSUPPORTED: 이번 단계 미지원 서비스 " + code, null);
        }
        Duration timeout = req.timeout() == null ? props.worker().defaultTimeout() : req.timeout();
        JobService.Created created;
        try {
            created = jobs.create(new JobService.CreateCommand(code, null, req.projectId(), List.of(), req.payload(),
                    req.idempotencyKey(), timeout.plusSeconds(30), null, req.requesterModule(), "SYNC"), false);
        } catch (DomainException e) {
            throw new AipUnavailableException("AIP Job 생성 거부 " + e.code() + ": " + e.getMessage(), e);
        }
        Map<String, Object> job = created.job();
        UUID id = AipJdbc.uuid(job, "id");
        String status = AipJdbc.str(job, "status");
        if (created.replayed() && JobStatus.SUCCEEDED.name().equals(status)) {
            return result(job, 0L, true);
        }
        if (JobStatus.RUNNING.name().equals(status)) {
            throw new AipUnavailableException("같은 멱등 키의 Job 이 이미 실행 중: " + id, null);
        }
        if (JobStatus.valueOf(status).terminal()) {
            throw new AipUnavailableException("재시도 불가 상태(" + status + "): " + id, null);
        }
        String worker = SYNC_WORKER + ":" + id.toString().substring(0, 8);
        jobs.startSync(id, worker, timeout.toSeconds() + 30);
        // 생성 때와 같은 subject(프로젝트)로 해석 → 같은 별칭 · 모델(해시 버킷 고정)
        ModelRegistryService.Route route = registry.resolve(code.name(), req.projectId());
        JobExecutor.Execution exec;
        try {
            exec = executor.execute(code, id, req.payload(), route, timeout);
        } catch (AipTimeoutException e) {
            jobs.fail(id, worker, "TIMEOUT", e.getMessage(), false);
            throw e;
        } catch (AipUnavailableException e) {
            jobs.fail(id, worker, "WORKER_UNAVAILABLE", e.getMessage(), false);
            throw e;
        } catch (RuntimeException e) {
            jobs.fail(id, worker, "WORKER_ERROR", e.toString(), false);
            throw new AipUnavailableException("AIP 실행 오류: " + e.getMessage(), e);
        }
        if (!jobs.complete(id, worker, exec)) {
            throw new AipUnavailableException("AIP Job 결과 저장 실패(lease 만료): " + id, null);
        }
        return result(jobs.get(id), exec.latencyMs(), false);
    }

    private SyncResult result(Map<String, Object> job, long latencyMs, boolean replayed) {
        Map<String, Object> mv = job.get("model_version_id") == null ? Map.of()
                : jobs.modelRow(AipJdbc.uuid(job, "model_version_id"));
        return new SyncResult(AipJdbc.uuid(job, "id"), AipJdbc.str(job, "status"), AipJdbc.str(mv, "name"),
                AipJdbc.str(mv, "version"), AipJdbc.tree(job.get("result_ref")), latencyMs, replayed);
    }
}
