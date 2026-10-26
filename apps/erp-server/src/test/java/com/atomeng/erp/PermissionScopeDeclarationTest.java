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

    // SAL-05 결정(V0042): PM 의 sal:opportunity:read · eqp:equipment:read 는 ALL, 수정 · 등록 권한은 삭제 →
    // 예전 "판정 수단 없이 실패하는 쪽으로 닫힌" 12개 엔드포인트(FAIL_CLOSED_PENDING_POLICY)는 더 이상 없다. 예외 목록 없음.

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
