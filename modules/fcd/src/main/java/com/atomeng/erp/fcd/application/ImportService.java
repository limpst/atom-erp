package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fcd.adapter.out.persistence.LedgerStore;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine.Decision;
import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import com.atomeng.erp.fcd.domain.ingest.FileParser;
import com.atomeng.erp.fcd.domain.ingest.FileParser.ParseResult;
import com.atomeng.erp.fcd.domain.ingest.FileParser.RowError;
import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.MappingProfile;
import com.atomeng.erp.fcd.domain.ingest.RowSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파일 가져오기 배치 수명주기 (설계 A6.3 · B5.2, 12-IMPL Step 2·3, BR-FCD-004/005).
 * 업로드 → 파싱 · 검증 · 드라이런 중복 판정(VALIDATED, 미리보기 버킷) → 확정(커밋 시점 재판정 · 원장 쓰기 · 채점) /
 * 폐기 / 롤백(영향 미리보기 · 사유 필수). 같은 판정 코드({@link DedupEngine#decide})가 미리보기와 커밋에 쓰인다.
 */
@Service
public class ImportService {

    public static final long MAX_BYTES = 50L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final LedgerStore ledger;
    private final CardService cards;
    private final ScoringService scoring;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final ObjectMapper om;

    public ImportService(JdbcTemplate jdbc, LedgerStore ledger, CardService cards, ScoringService scoring,
                         OutboxPublisher outbox, AuditLog audit, ObjectMapper om) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.cards = cards;
        this.scoring = scoring;
        this.outbox = outbox;
        this.audit = audit;
        this.om = om;
    }

    // ------------------------------------------------------------------ 프로파일

    private static List<String> arr(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    public List<MappingProfile> profiles() {
        return jdbc.query("""
                SELECT profile_code, card_company, file_type, kind, name, header_signature, column_map::text AS cm,
                       date_format, cancel_mode, cancel_values, partial_cancel_values, footer_stop_pattern, active
                  FROM fcd.import_profile ORDER BY profile_code""", (rs, i) -> {
            Map<String, String> cm;
            try {
                cm = om.readValue(rs.getString("cm"), new TypeReference<LinkedHashMap<String, String>>() {});
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return new MappingProfile(rs.getString("profile_code"), rs.getString("card_company"), rs.getString("file_type"),
                    rs.getString("kind"), rs.getString("name"), arr(rs.getArray("header_signature")), cm,
                    rs.getString("date_format"), rs.getString("cancel_mode"), arr(rs.getArray("cancel_values")),
                    arr(rs.getArray("partial_cancel_values")), rs.getString("footer_stop_pattern"), rs.getBoolean("active"));
        });
    }

    private UUID profileId(String code) {
        return code == null ? null : jdbc.query("SELECT id FROM fcd.import_profile WHERE profile_code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, code);
    }

    // ------------------------------------------------------------------ 업로드 · 검증

    @Transactional
    public Map<String, Object> upload(byte[] data, String filename, String profileCode, UUID actor) {
        if (data.length > MAX_BYTES) {
            throw new DomainException(FcdErrors.FILE_TOO_LARGE, "파일 크기 50MB 초과");
        }
        String sha = FileParser.sha256(data);
        Integer dup = jdbc.queryForObject("""
                SELECT count(*) FROM fcd.import_batch WHERE file_sha256 = ?
                   AND status NOT IN ('DISCARDED','FAILED','ROLLED_BACK')""", Integer.class, sha);
        if (dup != null && dup > 0) {
            throw new DomainException(FcdErrors.DUPLICATE_FILE, "BR-FCD-004: 동일 파일(SHA-256) 재업로드 차단");
        }
        List<MappingProfile> profs = profiles();
        MappingProfile forced = profileCode == null ? null : profs.stream()
                .filter(p -> p.profileCode().equals(profileCode)).findFirst()
                .orElseThrow(() -> new DomainException(FcdErrors.NOT_FOUND, "프로파일 없음: " + profileCode));
        UUID batchId = UuidV7.next();
        ParseResult res;
        try {
            res = FileParser.parse(data, cards.resolver(), profs, forced);
        } catch (RowSource.FormatException e) {
            jdbc.update("""
                    INSERT INTO fcd.import_batch(id, channel, filename, file_sha256, status, warnings, uploaded_by)
                    VALUES (?, 'CSV', ?, ?, 'FAILED', ?::jsonb, ?)""", batchId, filename, sha,
                    ledger.toJson(List.of(e.code() + ": " + e.getMessage())), actor);
            return batch(batchId);
        }
        String status = res.needsMapping() ? "NEEDS_MAPPING" : "VALIDATED";
        Map<String, Integer> counts = new TreeMap<>();
        jdbc.update("""
                INSERT INTO fcd.import_batch(id, channel, profile_id, filename, file_sha256, status, file_kind, detected_encoding,
                                             detected_delimiter, detected_header_row, header_rows, profile_score, rows_total,
                                             rows_error, warnings, detected_headers, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""",
                batchId, res.channel(), res.profile() == null ? null : profileId(res.profile().profileCode()), filename, sha,
                status, res.fileKind(), res.encoding(), res.delimiter(), res.headerRow(), res.headerRows(),
                Math.round(res.profileScore() * 1000) / 1000.0, res.rowsTotal(), res.rowsError(),
                ledger.toJson(res.warnings()), ledger.toJson(res.detectedHeaders()), actor);
        for (RowError e : res.errors()) {
            jdbc.update("""
                    INSERT INTO fcd.import_row_error(batch_id, row_no, column_name, code, raw_value, message)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    batchId, e.rowNo(), e.column(), e.code(), e.rawValue(), e.message());
        }
        for (CanonicalRow r : res.rows()) {
            String bucket = DedupEngine.decide(r, ledger).bucket();     // 드라이런(쓰기 없음)
            counts.merge(bucket, 1, Integer::sum);
            jdbc.update("INSERT INTO fcd.import_staging(batch_id, row_no, canonical, preview) VALUES (?, ?, ?::jsonb, ?)",
                    batchId, r.rowNo(), ledger.toJson(r), bucket);
        }
        counts.put("ERROR", res.rowsError());
        jdbc.update("UPDATE fcd.import_batch SET counts = ?::jsonb WHERE id = ?", ledger.toJson(counts), batchId);
        audit.append("FCD", AuditLog.Action.CREATE, "import_batch", batchId, null,
                Map.of("file", String.valueOf(filename), "sha256", sha, "status", status, "counts", counts));
        return batch(batchId);
    }

    /** 웹 목록: 최근 가져오기 배치(읽기 전용, 원문 · 파일 내용 없음) */
    public List<Map<String, Object>> batches(int limit) {
        return jdbc.queryForList("""
                SELECT b.id, b.channel, b.filename, b.status, b.file_kind, b.detected_encoding, b.rows_total, b.rows_error,
                       b.counts::text AS counts, p.profile_code, b.uploaded_at, b.committed_at, b.rolled_back_at
                  FROM fcd.import_batch b LEFT JOIN fcd.import_profile p ON p.id = b.profile_id
                 ORDER BY b.uploaded_at DESC LIMIT ?""", limit);
    }

    public Map<String, Object> batch(UUID id) {
        List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT b.id, b.channel, b.filename, b.file_sha256, b.status, b.file_kind, b.detected_encoding,
                       b.detected_delimiter, b.detected_header_row, b.header_rows, b.profile_score, b.rows_total, b.rows_error,
                       b.counts::text AS counts, b.warnings::text AS warnings, p.profile_code, b.committed_at,
                       b.rolled_back_at, b.rollback_reason
                  FROM fcd.import_batch b LEFT JOIN fcd.import_profile p ON p.id = b.profile_id WHERE b.id = ?""", id);
        if (l.isEmpty()) {
            throw new DomainException(FcdErrors.NOT_FOUND, "배치 없음: " + id);
        }
        Map<String, Object> m = new LinkedHashMap<>(l.get(0));
        // FCD-12 최근 롤백 요청(4-eyes 진행 상태)
        m.put("rollbackRequest", jdbc.queryForList("""
                SELECT id, status, reason, requested_by, requested_at, decided_by, decided_at
                  FROM fcd.import_rollback_request WHERE batch_id = ? ORDER BY requested_at DESC, id DESC LIMIT 1""", id)
                .stream().findFirst().orElse(null));
        try {
            m.put("counts", om.readValue((String) m.get("counts"), Map.class));
            m.put("warnings", om.readValue((String) m.get("warnings"), List.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    public List<Map<String, Object>> preview(UUID id, String bucket) {
        return jdbc.queryForList("""
                SELECT row_no, preview AS bucket, canonical->>'approvedAt' AS approved_at, canonical->>'amountKrw' AS amount_krw,
                       canonical->>'merchantName' AS merchant_name, canonical->>'kind' AS kind
                  FROM fcd.import_staging WHERE batch_id = ? AND (? IS NULL OR preview = ?) ORDER BY row_no""",
                id, bucket, bucket);
    }

    public byte[] errorsCsv(UUID id) {
        List<RowError> errs = jdbc.query("""
                SELECT row_no, column_name, code, raw_value, message FROM fcd.import_row_error WHERE batch_id = ?
                 ORDER BY row_no, code""", (rs, i) -> new RowError(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), id);
        return FileParser.errorsCsv(errs);
    }

    // ------------------------------------------------------------------ 확정 · 폐기

    private String lockStatus(UUID id) {
        String s = jdbc.query("SELECT status FROM fcd.import_batch WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (s == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "배치 없음: " + id);
        }
        return s;
    }

    @Transactional
    public Map<String, Object> commit(UUID id, UUID actor) {
        String status = lockStatus(id);
        if (!"VALIDATED".equals(status)) {
            throw new DomainException(FcdErrors.STATE, "VALIDATED 배치만 확정할 수 있습니다(현재 " + status + ")");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('fcd-import-commit'))::text", String.class);
        List<CanonicalRow> rows = jdbc.query("SELECT canonical::text FROM fcd.import_staging WHERE batch_id = ? ORDER BY row_no",
                (rs, i) -> ledger.canonical(rs.getString(1)), id);
        Map<String, Integer> counts = new TreeMap<>();
        Set<UUID> touched = new LinkedHashSet<>();
        LedgerStore.MerchantResolver merchants = ledger.merchantResolver();
        for (CanonicalRow r : rows) {
            Decision d = DedupEngine.decide(r, ledger);   // 커밋 시점 재판정
            UUID t = apply(r, d, id, merchants);
            counts.merge(d.bucket(), 1, Integer::sum);
            if (t != null) {
                touched.add(t);
            }
        }
        Integer err = jdbc.queryForObject("SELECT rows_error FROM fcd.import_batch WHERE id = ?", Integer.class, id);
        counts.put("ERROR", err == null ? 0 : err);
        jdbc.update("""
                UPDATE fcd.import_batch SET status = 'COMMITTED', counts = ?::jsonb, committed_at = now(), committed_by = ?
                 WHERE id = ?""", ledger.toJson(counts), actor, id);
        audit.append("FCD", AuditLog.Action.UPDATE, "import_batch", id, Map.of("status", status),
                Map.of("status", "COMMITTED", "counts", counts));
        String channel = jdbc.queryForObject("SELECT channel FROM fcd.import_batch WHERE id = ?", String.class, id);
        outbox.publish(new FcdEvents.ImportCommitted(id, channel, counts, touched.size()));
        scoring.score(touched, "IMPORT");
        return batch(id);
    }

    /** 결정 적용 (Python Ledger.apply) — 반환: 영향받은 거래 */
    UUID apply(CanonicalRow r, Decision d, UUID batchId, LedgerStore.MerchantResolver merchants) {
        switch (d.bucket()) {
            case "DUPLICATE" -> {
                return null;
            }
            case "NEW", "REVIEW" -> {
                var m = merchants.resolve(r.merchantName(), r.bizRegNo(), r.categoryHint());
                UUID emp = ledger.cardHolder(r.cardId(), r.approvedAt()).orElse(null);
                UUID t = ledger.insertTxn(r, m.getKey(), m.getValue(), batchId, emp);
                UUID s = ledger.attachSource(t, r, batchId, "NEW", d.bucket().equals("REVIEW") ? d.score() : null, null);
                ledger.recompute(t);
                if (d.bucket().equals("NEW")) {
                    ledger.retryOrphans(t);
                } else {
                    jdbc.update("""
                            INSERT INTO fcd.dedup_candidate(id, source_id, txn_id, new_txn_id, score, features)
                            VALUES (?, ?, ?, ?, ?, ?::jsonb)""", UuidV7.next(), s, d.txnId(), t, d.score(),
                            ledger.toJson(d.features()));
                }
                return t;
            }
            case "MERGED" -> {
                ledger.attachSource(d.txnId(), r, batchId, d.method(), d.score(), null);
                ledger.recompute(d.txnId());
                return d.txnId();
            }
            case "CANCEL", "CANCEL_DUP" -> {
                ledger.attachSource(d.txnId(), r, batchId, "CANCEL", null,
                        d.bucket().equals("CANCEL") ? DedupEngine.cancelKey(r) : null);
                ledger.recompute(d.txnId());
                return d.txnId();
            }
            case "ORPHAN_CANCEL" -> {
                ledger.attachSource(null, r, batchId, "ORPHAN", null, null);
                return null;
            }
            default -> throw new IllegalStateException(d.bucket());
        }
    }

    @Transactional
    public Map<String, Object> discard(UUID id, UUID actor) {
        String status = lockStatus(id);
        if (!List.of("VALIDATED", "NEEDS_MAPPING").contains(status)) {
            throw new DomainException(FcdErrors.STATE, "확정 전 배치만 폐기할 수 있습니다");
        }
        jdbc.update("UPDATE fcd.import_batch SET status = 'DISCARDED' WHERE id = ?", id);
        audit.append("FCD", AuditLog.Action.UPDATE, "import_batch", id, Map.of("status", status),
                Map.of("status", "DISCARDED", "by", actor.toString()));
        return batch(id);
    }

    // ------------------------------------------------------------------ 롤백 (BR-FCD-005)

    record PlanItem(UUID sourceId, UUID txnId, String action) {}

    List<PlanItem> plan(UUID batchId) {
        List<PlanItem> out = new ArrayList<>();
        jdbc.query("""
                SELECT s.id, s.txn_id, s.kind, t.created_batch_id,
                       EXISTS (SELECT 1 FROM fcd.expense_line l WHERE l.txn_id = t.id
                                AND l.sync_status IN ('POSTED','SYNCED','REVERSE_PENDING')) AS posted,
                       EXISTS (SELECT 1 FROM fcd.case_txn ct JOIN fcd.fcd_case c ON c.id = ct.case_id
                                WHERE ct.txn_id = t.id AND c.status IN ('RESOLVED_LEGIT','RESOLVED_MISUSE','RECOVERY','CLOSED'))
                         AS resolved,
                       EXISTS (SELECT 1 FROM fcd.case_txn ct JOIN fcd.fcd_case c ON c.id = ct.case_id
                                WHERE ct.txn_id = t.id AND c.status IN ('OPEN','EXPLANATION_REQUESTED','OVERDUE','SUBMITTED',
                                                                        'SUPPLEMENT_REQUESTED','ESCALATED')) AS open_case,
                       EXISTS (SELECT 1 FROM fcd.txn_source o WHERE o.txn_id = t.id AND o.id <> s.id AND NOT o.detached
                                AND o.batch_id IS DISTINCT FROM s.batch_id) AS others
                  FROM fcd.txn_source s LEFT JOIN fcd.txn t ON t.id = s.txn_id
                 WHERE s.batch_id = ? AND NOT s.detached ORDER BY s.seq""", rs -> {
            UUID sid = rs.getObject("id", UUID.class);
            UUID tid = rs.getObject("txn_id", UUID.class);
            String action;
            if (tid == null) {
                action = "DROP_ORPHAN";
            } else if (batchId.equals(rs.getObject("created_batch_id", UUID.class)) && !"CANCEL".equals(rs.getString("kind"))) {
                if (rs.getBoolean("posted")) {
                    action = "KEEP_POSTED";
                } else if (rs.getBoolean("resolved")) {
                    action = "KEEP_RESOLVED_CASE";
                } else if (rs.getBoolean("open_case")) {
                    action = "KEEP_OPEN_CASE";      // FCD-12 조사 중 케이스 거래는 지우지 않는다
                } else if (rs.getBoolean("others")) {
                    action = "DETACH_SOURCE";
                } else {
                    action = "DELETE_TXN";
                }
            } else {
                // FCD-12: 조사 중 케이스 거래는 원천도 떼지 않는다(금액 · 상태가 조사 도중 바뀌지 않게)
                action = rs.getBoolean("posted") ? "KEEP_POSTED"
                        : rs.getBoolean("open_case") ? "KEEP_OPEN_CASE" : "DETACH_SOURCE";
            }
            out.add(new PlanItem(sid, tid, action));
        }, batchId);
        return out;
    }

    public Map<String, Integer> rollbackImpact(UUID id) {
        Map<String, Integer> m = new TreeMap<>();
        plan(id).forEach(p -> m.merge(p.action(), 1, Integer::sum));
        return m;
    }

    /**
     * FCD-12 롤백 4-eyes ① 요청(FIN · ADMIN, 사유 필수) — 바로 실행하지 않고 대기 요청만 남긴다. 영향 미리보기를 함께 돌려준다.
     */
    @Transactional
    public Map<String, Object> requestRollback(UUID id, String reason, UUID actor) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(FcdErrors.INVALID, "롤백 사유 필수");
        }
        String status = lockStatus(id);
        if (!List.of("COMMITTED", "PARTIALLY_ROLLED_BACK").contains(status)) {
            throw new DomainException(FcdErrors.STATE, "커밋된 배치만 롤백 가능");
        }
        if (pendingRollback(id) != null) {
            throw new DomainException(FcdErrors.ROLLBACK_REQUEST_STATE, "이미 승인 대기 중인 롤백 요청이 있습니다");
        }
        UUID req = UuidV7.next();
        jdbc.update("""
                INSERT INTO fcd.import_rollback_request(id, batch_id, reason, status, requested_by)
                VALUES (?, ?, ?, 'PENDING', ?)""", req, id, reason, actor);
        Map<String, Integer> impact = rollbackImpact(id);
        audit.append("FCD", AuditLog.Action.UPDATE, "import_batch", id, Map.of("status", status),
                Map.of("rollbackRequested", req.toString(), "reason", reason, "impact", impact));
        Map<String, Object> out = new LinkedHashMap<>(batch(id));
        out.put("rollbackImpact", impact);
        return out;
    }

    private Map<String, Object> pendingRollback(UUID batchId) {
        List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT id, reason, requested_by FROM fcd.import_rollback_request
                 WHERE batch_id = ? AND status = 'PENDING' FOR UPDATE""", batchId);
        return l.isEmpty() ? null : l.get(0);
    }

    private Map<String, Object> lockPendingForDecision(UUID id, UUID approver) {
        lockStatus(id);
        Map<String, Object> req = pendingRollback(id);
        if (req == null) {
            throw new DomainException(FcdErrors.ROLLBACK_REQUEST_STATE, "승인 대기 중인 롤백 요청이 없습니다");
        }
        if (approver.equals(req.get("requested_by"))) {
            throw new DomainException(FcdErrors.FOUR_EYES, "4-eyes: 롤백 요청자 본인은 승인 · 반려할 수 없습니다");
        }
        return req;
    }

    /** FCD-12 롤백 4-eyes ② 승인(AUDITOR, 요청자 ≠ 승인자) — 이 시점의 계획으로 실행한다 */
    @Transactional
    public Map<String, Object> approveRollback(UUID id, UUID approver) {
        Map<String, Object> req = lockPendingForDecision(id, approver);
        jdbc.update("UPDATE fcd.import_rollback_request SET status = 'APPROVED', decided_by = ?, decided_at = now() "
                + "WHERE id = ?", approver, req.get("id"));
        return executeRollback(id, (String) req.get("reason"), (UUID) req.get("requested_by"), approver);
    }

    /** FCD-12 롤백 요청 반려(AUDITOR, 요청자 ≠ 반려자) — 배치는 그대로 */
    @Transactional
    public Map<String, Object> rejectRollback(UUID id, UUID approver, String note) {
        Map<String, Object> req = lockPendingForDecision(id, approver);
        jdbc.update("UPDATE fcd.import_rollback_request SET status = 'REJECTED', decided_by = ?, decided_at = now(), "
                + "decision_note = ? WHERE id = ?", approver, note, req.get("id"));
        audit.append("FCD", AuditLog.Action.UPDATE, "import_batch", id, null,
                Map.of("rollbackRejected", req.get("id").toString(), "by", approver.toString()));
        return batch(id);
    }

    private Map<String, Object> executeRollback(UUID id, String reason, UUID requestedBy, UUID approver) {
        String status = lockStatus(id);
        if (!List.of("COMMITTED", "PARTIALLY_ROLLED_BACK").contains(status)) {
            throw new DomainException(FcdErrors.STATE, "커밋된 배치만 롤백 가능");
        }
        List<PlanItem> plan = plan(id);
        List<UUID> removed = new ArrayList<>();
        List<UUID> kept = new ArrayList<>();
        Set<UUID> recompute = new LinkedHashSet<>();
        for (PlanItem p : plan) {
            switch (p.action()) {
                case "DELETE_TXN" -> {
                    jdbc.update("UPDATE fcd.txn SET removed_at = now(), removed_reason = ? WHERE id = ?",
                            "배치 롤백: " + reason, p.txnId());
                    jdbc.update("UPDATE fcd.txn_source SET detached = true WHERE txn_id = ?", p.txnId());
                    removed.add(p.txnId());
                }
                case "DETACH_SOURCE", "DROP_ORPHAN" -> {
                    jdbc.update("UPDATE fcd.txn_source SET detached = true WHERE id = ?", p.sourceId());
                    if (p.txnId() != null) {
                        recompute.add(p.txnId());
                    }
                }
                default -> kept.add(p.txnId());   // KEEP_POSTED · KEEP_RESOLVED_CASE · KEEP_OPEN_CASE
            }
        }
        recompute.removeAll(removed);
        recompute.forEach(ledger::recompute);
        Map<String, Integer> counts = rollbackImpactOf(plan);
        String next = kept.isEmpty() ? "ROLLED_BACK" : "PARTIALLY_ROLLED_BACK";
        jdbc.update("""
                UPDATE fcd.import_batch SET status = ?, rolled_back_at = now(), rolled_back_by = ?, rollback_reason = ?
                 WHERE id = ?""", next, approver, reason, id);
        audit.append("FCD", AuditLog.Action.UPDATE, "import_batch", id, Map.of("status", status),
                Map.of("status", next, "reason", reason, "counts", counts, "requestedBy", requestedBy.toString(),
                        "approvedBy", approver.toString()));
        outbox.publish(new FcdEvents.ImportRolledBack(id, removed, kept, reason));
        Map<String, Object> out = new LinkedHashMap<>(batch(id));
        out.put("rollback", counts);
        return out;
    }

    private static Map<String, Integer> rollbackImpactOf(List<PlanItem> plan) {
        Map<String, Integer> m = new TreeMap<>();
        plan.forEach(p -> m.merge(p.action(), 1, Integer::sum));
        return m;
    }

    // ------------------------------------------------------------------ 중복 후보 확인 큐

    public List<Map<String, Object>> candidates() {
        return jdbc.queryForList("""
                SELECT id, source_id, txn_id, new_txn_id, score, features::text AS features, decision, created_at
                  FROM fcd.dedup_candidate WHERE decision IS NULL ORDER BY created_at""");
    }

    @Transactional
    public Map<String, Object> decideCandidate(UUID candId, String decision, UUID actor) {
        if (!List.of("MERGE", "SEPARATE").contains(decision)) {
            throw new DomainException(FcdErrors.INVALID, "decision: MERGE | SEPARATE");
        }
        Map<String, Object> c = jdbc.query("SELECT source_id, txn_id, new_txn_id, decision FROM fcd.dedup_candidate WHERE id = ? "
                + "FOR UPDATE", rs -> rs.next() ? Map.<String, Object>of("source", rs.getObject(1, UUID.class),
                "target", rs.getObject(2, UUID.class), "dup", rs.getObject(3, UUID.class),
                "decision", String.valueOf(rs.getString(4))) : null, candId);
        if (c == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "후보 없음: " + candId);
        }
        if (!"null".equals(c.get("decision"))) {
            throw new DomainException(FcdErrors.STATE, "이미 결정된 후보");
        }
        UUID target = (UUID) c.get("target");
        UUID dup = (UUID) c.get("dup");
        // FCD-11 직무 분리: 결정자는 두 거래 어느 쪽의 사용자 · 카드 소지자도 아니어야 한다(MERGE · SEPARATE 모두)
        Integer own = jdbc.queryForObject("""
                SELECT count(*) FROM fcd.txn t LEFT JOIN fcd.card k ON k.id = t.card_id
                 WHERE t.id IN (?, ?) AND (t.employee_id = ? OR k.holder_employee_id = ?)""", Integer.class,
                target, dup, actor, actor);
        if (own != null && own > 0) {
            throw new DomainException(FcdErrors.DEDUP_SELF, "FCD-11: 본인 카드 거래의 중복 여부는 다른 담당자가 결정해야 합니다");
        }
        if (decision.equals("MERGE")) {
            // FCD-11: 미결 케이스 · 정산 라인이 걸린 거래는 병합(제거) 금지 — 조사 · 정산 대상이 사라지지 않게
            List<String> blockers = jdbc.queryForList("""
                    SELECT 'case ' || c.case_no FROM fcd.case_txn ct JOIN fcd.fcd_case c ON c.id = ct.case_id
                     WHERE ct.txn_id IN (?, ?) AND c.status IN ('OPEN','EXPLANATION_REQUESTED','OVERDUE','SUBMITTED',
                                                                 'SUPPLEMENT_REQUESTED','ESCALATED')
                    UNION ALL
                    SELECT 'expense_line ' || l.id FROM fcd.expense_line l WHERE l.txn_id IN (?, ?)""", String.class,
                    target, dup, target, dup);
            if (!blockers.isEmpty()) {
                throw new DomainException(FcdErrors.MERGE_BLOCKED,
                        "FCD-11: 미결 케이스 또는 정산 라인이 있는 거래는 병합할 수 없습니다(별개 유지 또는 케이스 판정 후) " + blockers);
            }
            jdbc.update("UPDATE fcd.txn SET removed_at = now(), removed_reason = '중복 확인 큐 병합' WHERE id = ?", dup);
            jdbc.update("UPDATE fcd.txn_source SET txn_id = ?, match_method = 'MANUAL' WHERE id = ?", target,
                    c.get("source"));
            ledger.recompute(target);
            scoring.score(List.of(target), "RESCORE");
        }
        jdbc.update("UPDATE fcd.dedup_candidate SET decision = ?, decided_by = ?, decided_at = now() WHERE id = ?",
                decision, actor, candId);
        audit.append("FCD", AuditLog.Action.UPDATE, "dedup_candidate", candId, null, Map.of("decision", decision));
        return Map.of("id", candId, "decision", decision);
    }

    static boolean isDuplicateKey(RuntimeException e) {
        return e instanceof DuplicateKeyException;
    }
}
