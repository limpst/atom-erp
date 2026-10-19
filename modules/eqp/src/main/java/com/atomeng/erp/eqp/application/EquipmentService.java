package com.atomeng.erp.eqp.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import com.atomeng.erp.eqp.domain.QrToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장비 대장 (FR-EQP-001·002, BR-EQP-008, 04-IMPL Step 3·5).
 * 코드 채번 EQ-{유형}-{4자리}(카운터 테이블 ON CONFLICT), QR 서명키 32바이트(응답 · 감사 로그에 노출 금지),
 * 교정 비대상 유형은 등록 즉시 AVAILABLE, 폐기는 상태 DISPOSED(행 삭제 금지 트리거).
 */
@Service
public class EquipmentService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final String QR_URL_BASE = "https://erp.atom-eng.local/q/";

    public record Register(String typeCode, String serialNo, String model, String manufacturer, String ownership,
                           UUID parentId, Map<String, Object> spec, String nfcUid, String homeLocation,
                           LocalDate acquiredOn, BigDecimal acquisitionCost, Integer usefulLifeMonths,
                           BigDecimal salvageValue, String depreciationMethod, BigDecimal dailyRate) {}

    public record Change(String model, String manufacturer, String homeLocation, BigDecimal dailyRate, String nfcUid,
                         Map<String, Object> spec) {}

    private final EqpJdbc eqp;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final CalibrationValidityService validity;
    private final ObjectMapper om;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EquipmentService(EqpJdbc eqp, OutboxPublisher outbox, AuditLog audit, CurrentActor actor,
                            CalibrationValidityService validity, ObjectMapper om, Clock clock) {
        this.eqp = eqp;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.validity = validity;
        this.om = om;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    @Transactional
    public EquipmentRow register(Register r) {
        UUID by = actor.actorIdOrSystem();
        var types = eqp.jdbc().queryForList("SELECT id, calibration_required FROM eqp.equipment_type WHERE code = ?",
                r.typeCode());
        if (types.isEmpty()) {
            throw new DomainException(EqpErrors.INVALID, "알 수 없는 장비 유형: " + r.typeCode());
        }
        UUID typeId = (UUID) types.get(0).get("id");
        boolean calRequired = (Boolean) types.get(0).get("calibration_required");
        if (r.parentId() != null) {
            eqp.get(r.parentId());
        }
        Integer dup = eqp.jdbc().queryForObject("SELECT count(*) FROM eqp.equipment WHERE type_id = ? AND serial_no = ?",
                Integer.class, typeId, r.serialNo());
        if (dup != null && dup > 0) {
            throw new DomainException(EqpErrors.SERIAL_DUPLICATE, "같은 유형에 이미 등록된 시리얼: " + r.serialNo());
        }
        Integer seq = eqp.jdbc().queryForObject("""
                INSERT INTO eqp.code_sequence(type_code, last_seq) VALUES (?, 1)
                ON CONFLICT (type_code) DO UPDATE SET last_seq = eqp.code_sequence.last_seq + 1
                RETURNING last_seq""", Integer.class, r.typeCode());
        String code = "EQ-%s-%04d".formatted(r.typeCode(), seq);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        UUID id = UuidV7.next();
        try {
            eqp.jdbc().update("""
                    INSERT INTO eqp.equipment(id, code, type_id, parent_id, model, manufacturer, serial_no, ownership, spec,
                                              nfc_uid, qr_secret, home_location, acquired_on, acquisition_cost,
                                              useful_life_months, salvage_value, depreciation_method, daily_rate,
                                              created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, code, typeId, r.parentId(), r.model(), r.manufacturer(), r.serialNo(),
                    r.ownership() == null ? "OWNED" : r.ownership(), json(r.spec()), r.nfcUid(), secret,
                    r.homeLocation(), EqpJdbc.d(r.acquiredOn()), r.acquisitionCost(), r.usefulLifeMonths(),
                    r.salvageValue() == null ? BigDecimal.ZERO : r.salvageValue(),
                    r.depreciationMethod() == null ? "SL" : r.depreciationMethod(), r.dailyRate(), by, by);
        } catch (DuplicateKeyException e) {
            throw new DomainException(EqpErrors.SERIAL_DUPLICATE, "중복 등록(시리얼 · NFC UID): " + r.serialNo());
        }
        eqp.history(id, null, EquipmentStatus.REGISTERED.name(), "REGISTER", null, by);
        EquipmentRow row = eqp.get(id);
        if (!calRequired) {   // 교정 비대상(코어채취기 · 드론 등) — 최초 교정 없이 사용 가능
            eqp.transition(row, EquipmentStatus.AVAILABLE, "REGISTER_NO_CAL", null, by);
            row = eqp.get(id);
        }
        outbox.publish(new EqpEvents.EquipmentRegistered(id, code, r.typeCode(), r.acquisitionCost()));
        audit.append("EQP", AuditLog.Action.CREATE, "equipment", id, null, view(row));
        return row;
    }

    public EquipmentRow get(UUID id) {
        return eqp.get(id);
    }

    /** 상세 카드(설계 A7.2): 대장 + 오늘 유효성 + 최근 교정 5건 + 최근 반출 10건 */
    @Transactional(readOnly = true)
    public Map<String, Object> card(UUID id) {
        EquipmentRow e = eqp.get(id);
        Map<String, Object> m = view(e);
        m.put("validityToday", validity.validate(id, today(), null));
        m.put("calibrations", eqp.jdbc().queryForList("""
                SELECT id, calibrated_on, next_due_on, next_due_estimated, result, cert_no, lab_name, lab_accreditation_no
                  FROM eqp.calibration WHERE equipment_id = ? ORDER BY calibrated_on DESC, created_at DESC LIMIT 5""", id));
        m.put("checkouts", eqp.jdbc().queryForList("""
                SELECT id, project_id, out_by, out_at, in_at, usage_days, sync_conflict FROM eqp.checkout
                 WHERE equipment_id = ? ORDER BY out_at DESC LIMIT 10""", id));
        m.put("children", eqp.children(id).stream().map(c -> Map.of("id", c.id(), "code", c.code())).toList());
        return m;
    }

    public List<EquipmentRow> list(String status, String type, Integer calDueWithinDays, int page, int size) {
        return eqp.list(status, type, calDueWithinDays, today(), size, (long) page * size); // int 곱 오버플로 → 음수 OFFSET 500 방지(SEC)
    }

    public long count(String status, String type, Integer calDueWithinDays) {
        return eqp.count(status, type, calDueWithinDays, today());
    }

    @Transactional
    public EquipmentRow change(UUID id, long expectedVersion, Change c) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow before = eqp.lock(id);
        IfMatch.check(expectedVersion, before.version());
        if (before.status() == EquipmentStatus.DISPOSED) {
            throw new DomainException(EqpErrors.STATE, "폐기 장비는 수정할 수 없습니다");
        }
        try {
            eqp.jdbc().update("""
                    UPDATE eqp.equipment SET model = coalesce(?, model), manufacturer = coalesce(?, manufacturer),
                           home_location = coalesce(?, home_location), daily_rate = coalesce(?, daily_rate),
                           nfc_uid = coalesce(?, nfc_uid), spec = coalesce(?::jsonb, spec),
                           version = version + 1, updated_at = now(), updated_by = ?
                     WHERE id = ?""", c.model(), c.manufacturer(), c.homeLocation(), c.dailyRate(), c.nfcUid(),
                    c.spec() == null ? null : json(c.spec()), by, id);
        } catch (DuplicateKeyException e) {
            throw new DomainException(EqpErrors.INVALID, "이미 다른 장비에 연결된 NFC UID: " + c.nfcUid());
        }
        EquipmentRow after = eqp.get(id);
        audit.append("EQP", AuditLog.Action.UPDATE, "equipment", id, view(before), view(after));
        return after;
    }

    /** BR-EQP-008: 삭제 대신 DISPOSED (전이표: AVAILABLE · IN_REPAIR · LOST → DISPOSED) */
    @Transactional
    public EquipmentRow dispose(UUID id, long expectedVersion, LocalDate disposedOn, BigDecimal amount, String reason) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow before = eqp.lock(id);
        IfMatch.check(expectedVersion, before.version());
        eqp.transition(before, EquipmentStatus.DISPOSED, "DISPOSE", null, by);
        eqp.jdbc().update("UPDATE eqp.equipment SET disposed_on = ?, disposal_amount = ?, nfc_uid = NULL WHERE id = ?",
                EqpJdbc.d(disposedOn == null ? today() : disposedOn), amount, id);
        EquipmentRow after = eqp.get(id);
        Map<String, Object> a = view(after);
        a.put("reason", reason);
        audit.append("EQP", AuditLog.Action.DELETE, "equipment", id, view(before), a);
        return after;
    }

    /**
     * 라벨 발급(FR-EQP-002, 04-IMPL Step 5). rotate=true 면 qr_secret 을 새로 만들어 이전 라벨을 모두 무효화한다.
     * 반환: token · qrUrl · zpl (50×30mm, 203dpi). secret 은 반환하지 않는다.
     */
    @Transactional
    public Map<String, Object> issueLabel(UUID id, boolean rotate) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow e = eqp.lock(id);
        if (rotate) {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            eqp.jdbc().update("UPDATE eqp.equipment SET qr_secret = ? WHERE id = ?", secret, id);
        }
        var now = clock.instant();
        eqp.jdbc().update("UPDATE eqp.equipment SET qr_issued_at = ?, updated_at = now(), updated_by = ? WHERE id = ?",
                Timestamp.from(now), by, id);
        String token = QrToken.issue(e.code(), now.getEpochSecond(), eqp.qrSecret(id));
        String url = QR_URL_BASE + token;
        String zpl = """
                ^XA^CI28
                ^PW400^LL240
                ^FO15,15^BQN,2,4^FDLA,%s^FS
                ^FO190,25^A0N,28,28^FD%s^FS
                ^FO190,65^A0N,22,22^FD%s^FS
                ^FO190,100^A0N,20,20^FDS/N %s^FS
                ^FO190,135^A0N,20,20^FDATOM ENG^FS
                ^XZ""".formatted(url, zplSafe(e.code()), zplSafe(e.typeName()), zplSafe(e.serialNo()));
        audit.append("EQP", AuditLog.Action.UPDATE, "equipment-label", id, null,
                Map.of("code", e.code(), "rotated", rotate, "issuedAt", now.toString()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipmentId", id);
        m.put("code", e.code());
        m.put("token", token);
        m.put("qrUrl", url);
        m.put("zpl", zpl);
        m.put("rotated", rotate);
        return m;
    }

    /**
     * ZPL 필드 데이터 정화 (보안 검토 2026-10-19): 사용자가 입력한 시리얼 · 유형명에 ZPL 명령 접두(^ · ~)나 줄바꿈이 있으면
     * 라벨 프린터 명령이 주입된다(예: "^FS^XA…"). 명령 문자를 제거하고 줄바꿈은 공백으로 바꾼다.
     */
    static String zplSafe(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("^", "").replace("~", "").replace('\r', ' ').replace('\n', ' ');
    }

    /** QR 토큰 검증 → 장비. 형식 오류 400, 서명 불일치(위조 · 회전 전 라벨) 403 */
    public EquipmentRow verifyQr(String token) {
        QrToken.Parsed p;
        try {
            p = QrToken.parse(token);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(EqpErrors.QR_FORMAT, "QR 토큰 형식 오류");
        }
        EquipmentRow e = eqp.findByCode(p.code())
                .orElseThrow(() -> new DomainException(EqpErrors.QR_SIGNATURE, "등록되지 않은 장비 코드(위조 의심)"));
        if (!QrToken.verify(p, eqp.qrSecret(e.id()))) {
            throw new DomainException(EqpErrors.QR_SIGNATURE, "QR 서명 불일치: " + p.code());
        }
        return e;
    }

    /** GET /scan/{token}: 장비 요약 + 오늘 유효성 + 가능 액션 */
    @Transactional(readOnly = true)
    public Map<String, Object> scan(String token) {
        EquipmentRow e = verifyQr(token);
        ValidityResult v = validity.validate(e.id(), today(), null);
        var active = eqp.jdbc().queryForList("""
                SELECT id, project_id, out_by, out_at FROM eqp.checkout
                 WHERE equipment_id = ? AND in_at IS NULL AND NOT sync_conflict""", e.id());
        List<String> actions = new ArrayList<>();
        if (!active.isEmpty()) {
            actions.add("RETURN");
        } else if (v.valid() && !e.status().isUnusable()) {
            actions.add("CHECKOUT");
        }
        actions.add("REPORT_FAULT");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipmentId", e.id());
        m.put("code", e.code());
        m.put("typeCode", e.typeCode());
        m.put("typeName", e.typeName());
        m.put("status", e.status());
        m.put("validity", v);
        m.put("activeCheckout", active.isEmpty() ? null : active.get(0));
        m.put("actions", actions);
        return m;
    }

    public Map<String, Object> view(EquipmentRow e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("code", e.code());
        m.put("typeCode", e.typeCode());
        m.put("typeName", e.typeName());
        m.put("parentId", e.parentId());
        m.put("model", e.model());
        m.put("manufacturer", e.manufacturer());
        m.put("serialNo", e.serialNo());
        m.put("ownership", e.ownership());
        m.put("status", e.status());
        m.put("spec", readMap(e.spec()));
        m.put("nfcUid", e.nfcUid());
        m.put("homeLocation", e.homeLocation());
        m.put("acquiredOn", e.acquiredOn());
        m.put("acquisitionCost", e.acquisitionCost());
        m.put("usefulLifeMonths", e.usefulLifeMonths());
        m.put("dailyRate", e.dailyRate());
        m.put("disposedOn", e.disposedOn());
        m.put("version", e.version());
        return m;
    }

    private String json(Map<String, Object> m) {
        try {
            return om.writeValueAsString(m == null ? Map.of() : m);
        } catch (JsonProcessingException e) {
            throw new DomainException(EqpErrors.INVALID, "spec JSON 오류");
        }
    }

    private Object readMap(String json) {
        try {
            return json == null ? null : om.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
