package com.atomeng.erp.aip.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipEvents;
import com.atomeng.erp.aip.domain.AipCodes;
import com.atomeng.erp.aip.domain.AipRules;
import com.atomeng.erp.aip.domain.ModelStage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모델 · 프롬프트 레지스트리 (설계 B3 ① · A6.3 · B7.11). 서비스별 활성 배포(production · canary · shadow 별칭)를 해석하고,
 * 모델 단계 전이(승격)를 표 + 승인 요건(QA → SHADOW, EXEC → PRODUCTION)으로 통제한다. MLflow 동기화는 후속.
 */
@Service
public class ModelRegistryService {

    /** 라우팅 결과: alias = production · canary, shadowModel = 결과 미노출 비교용(있으면) */
    public record Route(String serviceCode, String alias, UUID modelVersionId, String modelName, String modelVersion,
                        UUID promptVersionId, String promptKey, String promptVersion, String shadowModel) {}

    private final AipJdbc db;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;

    public ModelRegistryService(AipJdbc db, OutboxPublisher outbox, CurrentActor actor) {
        this.db = db;
        this.outbox = outbox;
        this.actor = actor;
    }

    private Map<String, Object> active(String serviceCode, String alias) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                SELECT d.*, m.name AS model_name, m.version AS model_version, p.prompt_key, p.version AS prompt_version
                  FROM aip.deployment d JOIN aip.model_version m ON m.id = d.model_version_id
                  LEFT JOIN aip.prompt_version p ON p.id = d.prompt_version_id
                 WHERE d.service_code = ? AND d.alias = ? AND d.active_from <= now()
                   AND (d.active_to IS NULL OR d.active_to > now())""", serviceCode, alias);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 같은 subject(사진 · 라운드)는 항상 같은 경로 — 해시 버킷 < canary 비율이면 canary */
    public Route resolve(String serviceCode, UUID subjectId) {
        Map<String, Object> prod = active(serviceCode, "production");
        Map<String, Object> canary = active(serviceCode, "canary");
        Map<String, Object> shadow = active(serviceCode, "shadow");
        Map<String, Object> pick = prod;
        String alias = "production";
        if (canary != null && subjectId != null
                && "canary".equals(AipRules.route(subjectId, AipJdbc.integer(canary, "traffic_pct")))) {
            pick = canary;
            alias = "canary";
        }
        if (pick == null) {
            return new Route(serviceCode, null, null, null, null, null, null, null, null);
        }
        return new Route(serviceCode, alias, AipJdbc.uuid(pick, "model_version_id"), AipJdbc.str(pick, "model_name"),
                AipJdbc.str(pick, "model_version"), AipJdbc.uuid(pick, "prompt_version_id"),
                AipJdbc.str(pick, "prompt_key"), AipJdbc.str(pick, "prompt_version"),
                shadow == null ? null : AipJdbc.str(shadow, "model_name"));
    }

    public List<Map<String, Object>> models() {
        return db.jdbc().queryForList("""
                SELECT m.id, m.name, m.version, m.base_model, m.stage, m.artifact_uri, m.approved_by, m.exec_approved_by,
                       (SELECT json_agg(json_build_object('serviceCode', d.service_code, 'alias', d.alias,
                               'trafficPct', d.traffic_pct)) FROM aip.deployment d
                         WHERE d.model_version_id = m.id AND d.active_to IS NULL) AS deployments
                  FROM aip.model_version m ORDER BY m.name, m.created_at""");
    }

    /** 승인 기록 — QA(품질) 또는 EXEC(최종) */
    @Transactional
    public Map<String, Object> approve(UUID modelId, String approverRole) {
        db.lock("model_version", modelId);
        String col = switch (approverRole) {
            case "QA" -> "approved_by";
            case "EXEC" -> "exec_approved_by";
            default -> throw new DomainException(AipCodes.PROMOTION_DENIED, "승인 역할은 QA · EXEC 만: " + approverRole);
        };
        db.jdbc().update("UPDATE aip.model_version SET " + col + " = ? WHERE id = ?", actor.actorIdOrSystem(), modelId);
        return db.get("model_version", modelId);
    }

    /**
     * 단계 전이(A6.3). SHADOW · CANARY · PRODUCTION 진입이면 해당 서비스 별칭의 기존 배포를 닫고 새 배포를 연다.
     * PRODUCTION 진입 시 기존 production 모델은 ARCHIVED.
     */
    @Transactional
    public Map<String, Object> promote(UUID modelId, String target, String serviceCode, Integer trafficPct) {
        Map<String, Object> m = db.lock("model_version", modelId);
        ModelStage from = ModelStage.valueOf(AipJdbc.str(m, "stage"));
        ModelStage to;
        try {
            to = ModelStage.valueOf(target);
        } catch (IllegalArgumentException e) {
            throw new DomainException(AipCodes.INVALID, "알 수 없는 단계: " + target);
        }
        if (!from.canMove(to)) {
            throw new DomainException(AipCodes.PROMOTION_DENIED, "허용되지 않는 단계 전이: " + from + " → " + to)
                    .with("from", from.name()).with("to", to.name());
        }
        if ("QA".equals(to.requiredApprover()) && m.get("approved_by") == null) {
            throw new DomainException(AipCodes.PROMOTION_DENIED, "SHADOW 진입은 QA 품질 승인이 필요합니다");
        }
        if ("EXEC".equals(to.requiredApprover()) && m.get("exec_approved_by") == null) {
            throw new DomainException(AipCodes.PROMOTION_DENIED, "PRODUCTION 진입은 EXEC 최종 승인이 필요합니다");
        }
        db.jdbc().update("UPDATE aip.model_version SET stage = ?, stage_changed_at = now() WHERE id = ?", to.name(),
                modelId);
        String alias = to.alias();
        int pct = 0;
        if (alias != null) {
            if (serviceCode == null || serviceCode.isBlank()) {
                throw new DomainException(AipCodes.INVALID, "배포 별칭 전환에는 serviceCode 가 필요합니다");
            }
            pct = switch (alias) {
                case "production" -> 100;
                case "canary" -> trafficPct == null ? 10 : trafficPct;
                default -> 0;
            };
            if ("production".equals(alias)) {
                db.jdbc().update("""
                        UPDATE aip.model_version SET stage = 'ARCHIVED', stage_changed_at = now()
                         WHERE id IN (SELECT model_version_id FROM aip.deployment
                                       WHERE service_code = ? AND alias = 'production' AND active_to IS NULL)
                           AND id <> ? AND stage = 'PRODUCTION'""", serviceCode, modelId);
                db.jdbc().update("UPDATE aip.deployment SET active_to = now() WHERE service_code = ? AND alias = 'canary' "
                        + "AND active_to IS NULL AND model_version_id = ?", serviceCode, modelId);
            }
            db.jdbc().update("UPDATE aip.deployment SET active_to = now() WHERE service_code = ? AND alias = ? "
                    + "AND active_to IS NULL", serviceCode, alias);
            db.jdbc().update("""
                    INSERT INTO aip.deployment(service_code, alias, model_version_id, prompt_version_id, traffic_pct,
                                               active_from, auto_rollback_rule)
                    VALUES (?, ?, ?, (SELECT prompt_version_id FROM aip.deployment WHERE service_code = ?
                                       AND alias = 'production' ORDER BY active_from DESC LIMIT 1), ?, now(),
                            '{"rejectRateDeltaPct":5,"minSamples":200}'::jsonb)""",
                    serviceCode, alias, modelId, serviceCode, pct);
        }
        outbox.publish(new AipEvents.ModelPromoted(modelId, AipJdbc.str(m, "name") + "@" + AipJdbc.str(m, "version"),
                alias == null ? to.name().toLowerCase(java.util.Locale.ROOT) : alias, pct));
        Map<String, Object> out = new LinkedHashMap<>(db.get("model_version", modelId));
        out.put("alias", alias);
        out.put("trafficPct", pct);
        return out;
    }
}
