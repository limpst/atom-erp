package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fcd.domain.ingest.FileParser;
import com.atomeng.erp.fcd.domain.ingest.PanTools;
import com.atomeng.erp.fcd.domain.ingest.PanTools.CardToken;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드 등록 · 토큰화 · 공용카드 대여 · 명세서 카드 식별 (BR-FCD-001/006, 12-IMPL Step 1).
 * PAN 은 요청 본문으로만 받아 즉시 HMAC 토큰으로 바꾸고 어디에도 남기지 않는다(응답 · 로그 · DB).
 * 키는 {@code atom.fcd.token-hmac.v1}(운영: Vault secret/fcd/token-hmac/v1) — 기본값은 프로토타입과 같은 개발용 키.
 */
@Service
public class CardService {

    public record RegisterCard(String cardCompany, char[] pan, String cardType, UUID holderEmployeeId, String dept,
                               String alias, long limitPerTxn, long limitDaily, long limitMonthly, LocalDate validThru) {}

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final byte[] key;
    private final int keyVersion;

    public CardService(JdbcTemplate jdbc, AuditLog audit,
                       @Value("${atom.fcd.token-hmac.v1:dev-only-not-a-secret-v1}") String key,
                       @Value("${atom.fcd.token-key-version:1}") int keyVersion) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.keyVersion = keyVersion;
    }

    public CardToken tokenize(char[] pan) {
        try {
            return PanTools.tokenize(pan, key, keyVersion);
        } catch (IllegalArgumentException e) {
            throw new DomainException(FcdErrors.INVALID_PAN, "카드번호(PAN) 형식 · 체크섬 오류");
        }
    }

    @Transactional
    public Map<String, Object> register(RegisterCard r, UUID actor) {
        if (!List.of("PERSONAL_ASSIGNED", "SHARED").contains(r.cardType())) {
            throw new DomainException(FcdErrors.INVALID, "cardType: PERSONAL_ASSIGNED | SHARED");
        }
        if (r.cardType().equals("SHARED") != (r.holderEmployeeId() == null)) {
            throw new DomainException(FcdErrors.INVALID, "개인카드는 소지자 필수, 공용카드는 소지자 없음");
        }
        CardToken t = tokenize(r.pan());
        UUID id = UuidV7.next();
        try {
            jdbc.update("""
                    INSERT INTO fcd.card(id, card_company, card_token, token_key_version, first6, last4, alias, card_type,
                                         holder_employee_id, dept, limit_per_txn, limit_daily, limit_monthly, valid_thru,
                                         created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, r.cardCompany(), t.token(), t.keyVersion(), t.first6(), t.last4(), r.alias(), r.cardType(),
                    r.holderEmployeeId(), r.dept(), r.limitPerTxn(), r.limitDaily(), r.limitMonthly(), r.validThru(),
                    actor, actor);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(FcdErrors.STATE, "이미 등록된 카드(토큰 중복)");
        }
        Map<String, Object> view = view(id);
        audit.append("FCD", AuditLog.Action.CREATE, "card", id, null, view);
        return view;
    }

    public Map<String, Object> view(UUID id) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT id, card_company, masked_no, alias, card_type, holder_employee_id, dept, limit_per_txn, limit_daily,
                       limit_monthly, valid_thru, status, closed_on, deactivation_flag, deactivation_reason
                  FROM fcd.card WHERE id = ?""", id));
        return m;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT id, card_company, masked_no, alias, card_type, holder_employee_id, dept, limit_per_txn, limit_daily,
                       limit_monthly, status, deactivation_flag, deactivation_reason FROM fcd.card ORDER BY created_at""");
    }

    @Transactional
    public UUID assign(UUID cardId, UUID employeeId, LocalDateTime start, LocalDateTime end, String purpose,
                       UUID projectId, UUID actor) {
        String type = jdbc.query("SELECT card_type FROM fcd.card WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                cardId);
        if (type == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "카드 없음: " + cardId);
        }
        if (!type.equals("SHARED")) {
            throw new DomainException(FcdErrors.INVALID, "공용카드만 대여할 수 있습니다");
        }
        UUID id = UuidV7.next();
        try {
            jdbc.update("""
                    INSERT INTO fcd.card_assignment(id, card_id, employee_id, period_start, period_end, purpose, project_id,
                                                    created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, cardId, employeeId, Timestamp.valueOf(start), Timestamp.valueOf(end), purpose, projectId, actor);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(FcdErrors.ASSIGNMENT_OVERLAP, "같은 공용카드의 대여 기간이 겹칩니다");
        }
        // 이미 들어온 거래의 실사용자 재결정
        jdbc.query("SELECT id FROM fcd.txn WHERE card_id = ? AND approved_at BETWEEN ? AND ?", rs -> {
            jdbc.update("""
                    UPDATE fcd.txn SET employee_id = ?, attribution = 'RESOLVED' WHERE id = ?""", employeeId,
                    rs.getObject(1, UUID.class));
        }, cardId, Timestamp.valueOf(start), Timestamp.valueOf(end));
        return id;
    }

    private static final Pattern MASKED = Pattern.compile("^\\s*(\\d{4})[- ]?(\\d{2})[*xX\\- ]+.*?(\\d{4})\\s*$");

    /** 명세서 카드 식별: 전체 PAN(드묾) → 토큰, 마스킹 앞6 · 뒤4(+카드사) → 유일하면 확정, 여럿이면 별칭 */
    public FileParser.CardResolver resolver() {
        return (ref, company, alias) -> {
            String s = ref == null ? "" : ref.strip();
            String digits = s.replaceAll("\\D", "");
            if (digits.length() >= 13 && digits.length() <= 19 && !s.contains("*") && PanTools.luhnValid(digits)) {
                String token = PanTools.tokenize(digits.toCharArray(), key, keyVersion).token();
                List<UUID> ids = jdbc.query("SELECT id FROM fcd.card WHERE card_token = ?",
                        (rs, i) -> rs.getObject(1, UUID.class), token);
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
                throw new FileParser.CardLookupException("E-CARD-UNKNOWN", "등록되지 않은 카드(토큰 불일치)");
            }
            Matcher m = MASKED.matcher(s);
            if (!m.matches()) {
                if (alias != null) {
                    List<UUID> a = jdbc.query("SELECT id FROM fcd.card WHERE alias = ?", (rs, i) -> rs.getObject(1, UUID.class),
                            alias);
                    if (a.size() == 1) {
                        return a.get(0);
                    }
                }
                throw new FileParser.CardLookupException("E-CARD-FORMAT", "카드번호 형식 인식 불가: "
                        + PanTools.maskPans(s));
            }
            String first6 = m.group(1) + m.group(2);
            String last4 = m.group(3);
            List<UUID> ids = company == null
                    ? jdbc.query("SELECT id FROM fcd.card WHERE first6 = ? AND last4 = ?", (rs, i) -> rs.getObject(1, UUID.class),
                    first6, last4)
                    : jdbc.query("SELECT id FROM fcd.card WHERE card_company = ? AND first6 = ? AND last4 = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), company, first6, last4);
            if (ids.size() == 1) {
                return ids.get(0);
            }
            if (ids.size() > 1) {
                if (alias != null) {
                    List<UUID> a = jdbc.query("SELECT id FROM fcd.card WHERE alias = ?", (rs, i) -> rs.getObject(1, UUID.class),
                            alias);
                    if (a.size() == 1) {
                        return a.get(0);
                    }
                }
                throw new FileParser.CardLookupException("E-CARD-AMBIGUOUS", "앞6·뒤4 동일 카드 " + ids.size()
                        + "장 — 별칭 컬럼 매핑 필요");
            }
            throw new FileParser.CardLookupException("E-CARD-UNKNOWN", "등록되지 않은 카드 " + first6 + "******" + last4);
        };
    }
}
