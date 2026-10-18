package com.atomeng.common.audit;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.pii.PiiMasking;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 감사 로그 기록·검증 (설계 7.2 audit_log, NFR-CORE-07, 00-IMPL Step 6).
 *
 * <p>해시 체인: 행 추가는 DB 함수 core.audit_append() 가 어드바이저리 락으로 직렬화하고
 * row_hash = sha256(prev_hash | 시각 | actor | … | after) 를 계산한다. UPDATE/DELETE 는 트리거로 차단.
 * {@link #verifyChain()} 은 전체 체인을 재계산해 변조된 첫 행을 찾는다.
 *
 * <p>호출자 트랜잭션에 참여한다(동기 기록). 승인·생성처럼 법적 의미가 큰 행위는 업무와 같은 트랜잭션에서
 * 기록해 "처리됐는데 감사 기록 없음"을 원천 차단한다(Step 6 함정·팁). 비동기·파일 폴백은 후속 Step.
 */
public class AuditLog {

    public enum Action { CREATE, UPDATE, DELETE, VIEW_PII, EXPORT, APPROVE, LOGIN }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActor currentActor;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.objectMapper = PiiMasking.maskingMapper(objectMapper); // @Pii 속성은 감사 로그에 원문 저장 금지
        this.currentActor = currentActor;
    }

    /** @return audit_log.id */
    public long append(String module, Action action, String targetType, UUID targetId, Object before, Object after) {
        if (module == null || !module.matches("^[A-Z]{3,4}$")) {
            throw new IllegalArgumentException("module 은 대문자 3~4자 코드(예: CORE, SAL, FLD): " + module);
        }
        UUID actor = currentActor.current().map(CurrentActor.Actor::actorId).orElse(null);
        Long id = jdbc.queryForObject("SELECT core.audit_append(?::uuid, NULL::inet, ?::text, ?::text, ?::text, ?::uuid, ?::jsonb, ?::jsonb, ?::text)",
                Long.class, actor, module, action.name(), targetType, targetId, json(before), json(after),
                MDC.get("traceId"));
        return id == null ? -1 : id;
    }

    /** @return 변조·단절이 발견된 첫 행 id. 정상이면 empty. */
    public Optional<Long> verifyChain() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT core.audit_verify_chain()", Long.class));
    }

    private String json(Object o) {
        if (o == null) {
            return null;
        }
        return objectMapper.valueToTree(o).toString();
    }
}
