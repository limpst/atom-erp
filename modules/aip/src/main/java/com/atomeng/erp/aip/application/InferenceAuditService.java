package com.atomeng.erp.aip.application;

import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추론 감사 (BR-AIP-009 · FR-AIP-020, TC-AIP-017). aip.inference_audit 는 월 파티션(PK (job_id, created_at)) · append-only 이고,
 * aip.job FK 를 두지 않는 대신 성공 Job 에 감사 행이 없으면 누락 탐지 배치가 aip.audit_gap_alert 에 남기고 경고 로그를 낸다.
 * 파티션은 pg_partman 대신 aip.ensure_audit_partition() 으로 현재월 + 3개월을 미리 만든다(36개월 보존 DETACH 는 후속).
 */
@Service
public class InferenceAuditService {

    private static final Logger log = LoggerFactory.getLogger(InferenceAuditService.class);

    /** 감사 1행 — model · adapter · prompt 버전, 입력/출력 해시, params(이미지 본문 제외), provenance, 지연 */
    public record Entry(UUID jobId, String serviceCode, String modelName, String modelVersion, String adapter,
                        String promptKey, String promptVersion, String inputSha256, String outputSha256, JsonNode params,
                        JsonNode provenance, List<String> retrievedChunkIds, Integer latencyMs) {}

    private final AipJdbc db;

    public InferenceAuditService(AipJdbc db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry e) {
        recordAt(e, null);
    }

    /** createdAt 지정(시험 · 이관용) — null 이면 DB now() */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAt(Entry e, Instant createdAt) {
        Timestamp at = createdAt == null ? null : Timestamp.from(createdAt);
        db.jdbc().queryForObject("SELECT aip.ensure_audit_partition(coalesce(?::timestamptz, now())::date)", Boolean.class,
                at);
        db.jdbc().update("""
                INSERT INTO aip.inference_audit(job_id, created_at, service_code, model_name, model_version, adapter,
                       prompt_key, prompt_version, input_sha256, output_sha256, params, provenance, retrieved_chunk_ids,
                       latency_ms)
                VALUES (?, coalesce(?::timestamptz, now()), ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::text[], ?)""",
                e.jobId(), at, e.serviceCode(), e.modelName(), e.modelVersion(), e.adapter(), e.promptKey(),
                e.promptVersion(), e.inputSha256(), e.outputSha256(), e.params() == null ? null : e.params().toString(),
                e.provenance() == null || e.provenance().isNull() ? null : e.provenance().toString(),
                e.retrievedChunkIds() == null ? null : "{" + String.join(",", e.retrievedChunkIds()) + "}",
                e.latencyMs());
    }

    /** 현재월 + months 개월 파티션 보장 — 새로 만든 수 */
    @Transactional
    public int ensurePartitions(int monthsAhead) {
        Integer n = db.jdbc().queryForObject("""
                SELECT count(*) FILTER (WHERE created) FROM (
                  SELECT aip.ensure_audit_partition((now() + make_interval(months => m))::date) AS created
                    FROM generate_series(0, ?) AS m) t""", Integer.class, monthsAhead);
        return n == null ? 0 : n;
    }

    /** 누락 탐지: grace 이전에 끝난 성공 Job 중 감사 행이 없는 것 → 경보 기록(멱등) · 새로 찾은 jobId 목록 */
    @Transactional
    public List<UUID> detectGaps(Duration grace) {
        List<UUID> found = db.jdbc().queryForList("""
                INSERT INTO aip.audit_gap_alert(job_id)
                SELECT j.id FROM aip.job j
                 WHERE j.status = 'SUCCEEDED' AND j.finished_at < now() - make_interval(secs => ?)
                   AND NOT EXISTS (SELECT 1 FROM aip.inference_audit a WHERE a.job_id = j.id)
                ON CONFLICT (job_id) DO NOTHING
                RETURNING job_id""", UUID.class, (double) grace.toSeconds());
        if (!found.isEmpty()) {
            log.warn("AIP 감사 누락 탐지 {}건(BR-AIP-009): {}", found.size(), found);
        }
        return found;
    }

    public List<Map<String, Object>> query(UUID jobId, UUID projectId, Instant from, Instant to) {
        return db.jdbc().queryForList("""
                SELECT a.job_id, a.created_at, a.service_code, a.model_name, a.model_version, a.adapter, a.prompt_key,
                       a.prompt_version, a.input_sha256, a.output_sha256, a.params, a.provenance, a.latency_ms,
                       a.tableoid::regclass::text AS partition_name, j.project_id, j.requested_by
                  FROM aip.inference_audit a JOIN aip.job j ON j.id = a.job_id
                 WHERE (?::uuid IS NULL OR a.job_id = ?) AND (?::uuid IS NULL OR j.project_id = ?)
                   AND a.created_at >= coalesce(?::timestamptz, '-infinity') AND a.created_at < coalesce(?::timestamptz, 'infinity')
                 ORDER BY a.created_at DESC LIMIT 500""", jobId, jobId, projectId, projectId,
                from == null ? null : Timestamp.from(from), to == null ? null : Timestamp.from(to));
    }
}
