package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.Role;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.identity.authz.ScopeResolver;
import com.atomeng.erp.support.IntegrationTestBase;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 보안 검토 CMN-05 — 실패하는 쪽으로 닫힌 권한 선언 전수 검사.
 * 어떤 역할에든 ALL 이 아닌 범위(PROJECT · CLIENT · SELF)로 부여된 권한을 쓰는 컨트롤러 메서드는
 * projectIdParam · scopeResolver · scopeCheckedInService 중 하나를 반드시 선언해야 한다.
 * (권한 범위는 실제 마이그레이션 시드를 읽은 PermissionRegistry 에서 가져온다.)
 */
class PermissionScopeDeclarationTest extends IntegrationTestBase {

    /**
     * 범위 판정 수단을 일부러 두지 않은 엔드포인트 — 자원(영업기회 · 장비)에 프로젝트가 없어 PROJECT 범위를 판정할 수 없다.
     * aspect 가 PROJECT 범위 호출자(PM)를 403 으로 거부하고 ALL 범위만 통과시킨다(실패하는 쪽으로 닫힘).
     * PM 에게 이 자원을 열어 줄지는 정책 결정 SAL-05 (권장: 읽기는 시드에서 ALL 로 명시) — 정책이 정해지면 시드를 바꾸고 여기서 뺀다.
     */
    static final java.util.Set<String> FAIL_CLOSED_PENDING_POLICY = java.util.Set.of(
            "SalController#opportunities", "SalController#opportunity", "SalController#move",
            "SalController#history", "SalController#addActivity", "SalController#activities",
            "EqpController#register", "EqpController#list", "EqpController#get", "EqpController#change",
            "EqpController#scan", "EqpController#validity");

    @Autowired
    PermissionRegistry registry;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;
    @Autowired
    ApplicationContext ctx;

    @Test
    @DisplayName("CMN-05: non-ALL 권한을 쓰는 모든 엔드포인트는 범위 판정 수단을 선언한다")
    void everyNonAllEndpointDeclaresScopeCheck() {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        int nonAll = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            Method m = e.getValue().getMethod();
            RequirePermission rp = AnnotatedElementUtils.findMergedAnnotation(m, RequirePermission.class);
            if (rp == null || m.getDeclaringClass().getPackageName().startsWith("com.atomeng.erp.support")) {
                continue;
            }
            checked++;
            var scopes = new TreeSet<PermissionScope>();
            for (Role r : Role.values()) {
                PermissionScope s = registry.permissionsOf(r).get(rp.value());
                if (s != null && s != PermissionScope.ALL) {
                    scopes.add(s);
                }
            }
            if (scopes.isEmpty()) {
                continue;
            }
            nonAll++;
            String where = m.getDeclaringClass().getSimpleName() + "#" + m.getName() + " " + rp.value() + " " + scopes;
            boolean declared = !rp.projectIdParam().isEmpty()
                    || rp.scopeResolver() != ScopeResolver.None.class
                    || rp.scopeCheckedInService();
            if (!declared && FAIL_CLOSED_PENDING_POLICY.contains(
                    m.getDeclaringClass().getSimpleName() + "#" + m.getName())) {
                continue;
            }
            if (!declared) {
                violations.add(where);
                continue;
            }
            List<String> params = Arrays.stream(m.getParameters()).map(java.lang.reflect.Parameter::getName).toList();
            if (!rp.projectIdParam().isEmpty() && !params.contains(rp.projectIdParam())) {
                violations.add(where + " — projectIdParam 없음: " + rp.projectIdParam());
            }
            if (rp.scopeResolver() != ScopeResolver.None.class) {
                if (!params.contains(rp.resourceParam())) {
                    violations.add(where + " — resourceParam 없음: " + rp.resourceParam());
                }
                if (ctx.getBeanNamesForType(rp.scopeResolver()).length != 1) {
                    violations.add(where + " — 해석기 bean 없음: " + rp.scopeResolver().getSimpleName());
                }
            }
        }
        violations.sort(null);
        violations.forEach(v -> System.out.println("[CMN-05] " + v));
        assertThat(checked).as("@RequirePermission 엔드포인트 수").isGreaterThan(200);
        assertThat(nonAll).isGreaterThan(50);
        assertThat(violations).as("범위 판정 수단이 없는 non-ALL 엔드포인트").isEmpty();
    }
}
