package com.atomeng.common.audit;

import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.ResponseEntity;

/**
 * {@link Audited} 처리. 권한 검사(@Order 10)보다 바깥에서 정상 반환 시에만 기록한다.
 * VIEW_PII 처럼 법적 의미가 있는 조회는 <b>동기 기록 · 실패 시 응답 실패</b>(fail-closed) — 조회됐는데 기록 없음 방지.
 * 애플리케이션 로그에는 대상 유형·ID 만 남기고 데이터 본문은 남기지 않는다(TC-CORE-010 "로그에 원문 없음").
 */
@Aspect
@Order(20)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final SpelExpressionParser SPEL = new SpelExpressionParser();

    private final AuditLog auditLog;

    public AuditAspect(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @AfterReturning(pointcut = "@annotation(audited)", returning = "result")
    public void after(JoinPoint jp, Audited audited, Object result) {
        Object body = result instanceof ResponseEntity<?> re ? re.getBody() : result;
        UUID targetId = evalTargetId(jp, audited.targetIdExpr(), body);
        long id = auditLog.append(audited.module(), audited.action(), audited.targetType(), targetId, null,
                audited.recordResult() ? body : null);
        log.info("audit {} {} {} target={} auditId={}", audited.module(), audited.action(), audited.targetType(),
                targetId, id);
    }

    private static UUID evalTargetId(JoinPoint jp, String expr, Object body) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        var ctx = new StandardEvaluationContext();
        var sig = (MethodSignature) jp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = jp.getArgs();
        for (int i = 0; i < names.length; i++) {
            ctx.setVariable(names[i], args[i]);
        }
        ctx.setVariable("result", body);
        Object v = SPEL.parseExpression(expr).getValue(ctx);
        if (v == null) {
            return null;
        }
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }
}
