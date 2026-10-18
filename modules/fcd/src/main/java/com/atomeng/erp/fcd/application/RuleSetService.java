package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fcd.domain.rules.RuleSet;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 룰셋 버전 관리 (BR-FCD-009, 12-IMPL Step 5). 저장 형식은 Python 프로토타입과 <b>같은 YAML</b> 원문(fcd.rule_set.yaml).
 * <ul>
 *   <li>기동 시 클래스패스 {@code fcd/rulesets/*.yaml} 을 시드(없을 때만). {@code _draft*} 는 항상 DRAFT</li>
 *   <li>API 로 올린 YAML 은 작성자 = 요청자, 상태 DRAFT — 다른 사람(AUDITOR · EXEC)이 승인해야 ACTIVE(4-eyes, DB CHECK 이중화)</li>
 *   <li>거래일 기준 ACTIVE 룰셋 선택(같은 시행일이면 상위 버전) — 거래 시점 규칙으로 평가</li>
 * </ul>
 */
@Service
public class RuleSetService {

    private static final Logger log = LoggerFactory.getLogger(RuleSetService.class);

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final AtomicReference<RuleSetRegistry> cache = new AtomicReference<>();

    public RuleSetService(JdbcTemplate jdbc, AuditLog audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedFromClasspath() {
        tx.executeWithoutResult(s -> seed());
    }

    void seed() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:fcd/rulesets/*.yaml");
            for (Resource f : files) {
                String yaml;
                try (InputStream in = f.getInputStream()) {
                    yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                RuleSet rs = RuleSet.parse(yaml, "fcd");
                boolean draft = f.getFilename() != null && f.getFilename().startsWith("_draft");
                Integer n = jdbc.queryForObject("SELECT count(*) FROM fcd.rule_set WHERE name = ? AND version = ?",
                        Integer.class, rs.name(), rs.version());
                if (n != null && n > 0) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO fcd.rule_set(id, name, version, effective_from, effective_to, status, authored_by,
                                                 approved_by, approved_at, yaml, sha256, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, ?, ?, 'SEED')""",
                        UuidV7.next(), rs.name(), rs.version(), Date.valueOf(rs.effectiveFrom()),
                        rs.effectiveTo() == null ? null : Date.valueOf(rs.effectiveTo()), draft ? "DRAFT" : rs.status(),
                        rs.authoredBy(), draft ? null : rs.approvedBy(), draft ? null : rs.approvedBy(), yaml, rs.sha256());
                log.info("FCD 룰셋 시드: {} {} ({})", rs.name(), rs.version(), draft ? "DRAFT" : rs.status());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cache.set(null);
    }

    public RuleSetRegistry registry() {
        RuleSetRegistry r = cache.get();
        if (r == null) {
            RuleSetRegistry fresh = new RuleSetRegistry();
            jdbc.query("SELECT yaml, status, authored_by, approved_by FROM fcd.rule_set ORDER BY created_at", rs -> {
                fresh.add(RuleSet.parse(rs.getString("yaml"), "fcd").governed(rs.getString("status"),
                        rs.getString("authored_by"), rs.getString("approved_by")));
            });
            cache.compareAndSet(null, fresh);
            r = fresh;
        }
        return r;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT version, name, effective_from, effective_to, status, authored_by, approved_by, sha256, source
                  FROM fcd.rule_set ORDER BY effective_from, created_at""");
    }

    /** 초안 등록 — YAML 검증(legal_basis · 가중치 · 표현식 화이트리스트) 후 DRAFT */
    @Transactional
    public Map<String, Object> createDraft(String yaml, UUID author) {
        RuleSet rs;
        try {
            rs = RuleSet.parse(yaml, "fcd");
        } catch (RuntimeException e) {
            throw new DomainException(FcdErrors.RULESET_INVALID, "룰셋 YAML 오류: " + e.getMessage());
        }
        try {
            jdbc.update("""
                    INSERT INTO fcd.rule_set(id, name, version, effective_from, effective_to, status, authored_by, yaml,
                                             sha256, source) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, 'API')""",
                    UuidV7.next(), rs.name(), rs.version(), Date.valueOf(rs.effectiveFrom()),
                    rs.effectiveTo() == null ? null : Date.valueOf(rs.effectiveTo()), author.toString(), yaml, rs.sha256());
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(FcdErrors.STATE, "같은 버전이 이미 있습니다: " + rs.version());
        }
        cache.set(null);
        audit.append("FCD", AuditLog.Action.CREATE, "rule_set", UUID.nameUUIDFromBytes(rs.version().getBytes(
                StandardCharsets.UTF_8)), null, Map.of("version", rs.version(), "sha256", rs.sha256()));
        return Map.of("version", rs.version(), "status", "DRAFT", "rules", rs.rules().size());
    }

    /** 4-eyes 승인 — 작성자 본인 금지(TC-FCD-024: API 403 + DB CHECK) */
    @Transactional
    public Map<String, Object> approve(String version, UUID approver) {
        Map<String, Object> row = jdbc.query("SELECT status, authored_by FROM fcd.rule_set WHERE version = ? FOR UPDATE",
                rs -> rs.next() ? Map.<String, Object>of("status", rs.getString(1), "author", rs.getString(2)) : null,
                version);
        if (row == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "룰셋 없음: " + version);
        }
        if (row.get("author").equals(approver.toString())) {
            throw new DomainException(FcdErrors.FOUR_EYES, "4-eyes: 작성자 본인은 승인할 수 없습니다");
        }
        if (!List.of("DRAFT", "PENDING_APPROVAL").contains((String) row.get("status"))) {
            throw new DomainException(FcdErrors.STATE, "승인할 수 없는 상태: " + row.get("status"));
        }
        jdbc.update("UPDATE fcd.rule_set SET status = 'ACTIVE', approved_by = ?, approved_at = now() WHERE version = ?",
                approver.toString(), version);
        cache.set(null);
        audit.append("FCD", AuditLog.Action.APPROVE, "rule_set", UUID.nameUUIDFromBytes(version.getBytes(
                StandardCharsets.UTF_8)), row, Map.of("status", "ACTIVE", "approvedBy", approver.toString()));
        return Map.of("version", version, "status", "ACTIVE");
    }
}
