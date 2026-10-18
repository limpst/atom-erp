package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.Role;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.erp.support.IntegrationTestBase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * 00-IMPL Step 4 권한 매트릭스 테스트 — 설계 00 v0.3 §8.2 표(12역할 × 11모듈 = 132셀) 전수.
 *
 * <p>① 셀 단위(132건): 표 원문 셀을 이 클래스의 독립 해석기로 풀어 기대 권한·범위 집합을 만들고,
 * DB(core.role_permission, 생성기 tools/gen_role_permission_seed.py 로 만든 V1_2 시드)를 읽은
 * PermissionRegistry 결과와 <b>정확히 일치</b>하는지 검사한다.
 * ② HTTP 단위: @RequirePermission 이 붙은 프로브 엔드포인트로 대표 시나리오(행 수준 권한 포함)를 검증한다.
 */
class PermissionMatrixTest extends IntegrationTestBase {

    static final List<String> MODULES =
            List.of("SAL", "FAC", "HRQ", "EQP", "FLD", "RPT", "QAR", "CST", "PTL", "DEM", "AIP");

    @Autowired
    PermissionRegistry registry;

    // ---------------------------------------------------------------- ① 셀 단위 132건

    static Stream<Arguments> cells() throws Exception {
        List<Arguments> out = new ArrayList<>();
        try (var in = PermissionMatrixTest.class.getResourceAsStream("/permission-matrix-8.2.csv");
             var r = new BufferedReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            List<String> lines = r.lines().filter(l -> !l.isBlank()).toList();
            List<String> header = List.of(lines.get(0).split(","));
            assertThat(header.subList(1, header.size())).isEqualTo(MODULES);
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split(",", -1);
                for (int i = 1; i < c.length; i++) {
                    out.add(Arguments.of(c[0], header.get(i), c[i]));
                }
            }
        }
        return out.stream();
    }

    @Test
    @DisplayName("Step4: 매트릭스는 12역할(EMPLOYEE·FIN·AUDITOR 포함) × 11모듈 = 132셀")
    void matrixCoversAllRolesAndModules() throws Exception {
        var all = cells().toList();
        assertThat(all).hasSize(132);
        assertThat(all.stream().map(a -> (String) a.get()[0]).collect(Collectors.toSet()))
                .isEqualTo(Stream.of(Role.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @ParameterizedTest(name = "[{index}] {0} × {1} = \"{2}\"")
    @MethodSource("cells")
    @DisplayName("Step4 §8.2 셀: DB 권한 = 표 해석 결과")
    void cell(String role, String module, String cellText) {
        Map<String, PermissionScope> expected = Interpreter.grants(role, module, cellText);
        String prefix = module.toLowerCase() + ":";
        Map<String, PermissionScope> actual = registry.permissionsOf(Role.valueOf(role)).entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(actual).as("%s × %s (%s)", role, module, cellText).isEqualTo(expected);
    }

    /** 표 셀 해석기 (생성기와 독립 구현, 같은 규칙 — 클래스 문서 참조) */
    static final class Interpreter {
        static final Map<String, String> DEFAULT = Map.ofEntries(
                Map.entry("SAL", "opportunity"), Map.entry("FAC", "facility"), Map.entry("HRQ", "engineer"),
                Map.entry("EQP", "equipment"), Map.entry("FLD", "defect"), Map.entry("RPT", "report"),
                Map.entry("QAR", "review"), Map.entry("CST", "budget"), Map.entry("PTL", "share"),
                Map.entry("DEM", "supervision-log"), Map.entry("AIP", "inference-job"));
        static final Map<String, String> QUALIFIER = new HashMap<>();

        static {
            QUALIFIER.put("가용인력", "availability");
            QUALIFIER.put("대여", "checkout");
            QUALIFIER.put("계약", "contract");
            QUALIFIER.put("의견", "comment");
            QUALIFIER.put("시간", "timesheet");
            QUALIFIER.put("경비", "expense");
            QUALIFIER.put("견적", "estimate");
            QUALIFIER.put("라벨 과제", "label-task");
            QUALIFIER.put("청구", "invoice");
            QUALIFIER.put("감사로그", "audit-log");
            // 본인·공유분 = 기본 리소스
        }

        static final Set<String> SELF = Set.of("본인", "시간", "경비", "라벨 과제");
        static final Pattern TOKEN = Pattern.compile("([CRUDA]+)(?:\\(([^)]*)\\))?(◐)?");

        static Map<String, PermissionScope> grants(String role, String module, String cell) {
            String mod = module.toLowerCase();
            Map<String, PermissionScope> m = new HashMap<>();
            switch (cell.trim()) {
                case "-" -> {
                    return m;
                }
                case "설정" -> {
                    m.put(mod + ":setting:update", PermissionScope.ALL);
                    return m;
                }
                case "템플릿" -> {
                    m.put("rpt:template:update", PermissionScope.ALL);
                    return m;
                }
                case "체크리스트" -> {
                    m.put("qar:checklist:update", PermissionScope.ALL);
                    return m;
                }
                case "모델 배포" -> {
                    m.put("aip:model:deploy", PermissionScope.ALL);
                    return m;
                }
                default -> { }
            }
            boolean scoped = cell.contains("◐");
            Matcher t = TOKEN.matcher(cell);
            while (t.find()) {
                String qual = t.group(2);
                String res = qual == null ? DEFAULT.get(module) : QUALIFIER.getOrDefault(qual, DEFAULT.get(module));
                PermissionScope scope = scoped
                        ? ("CUSTOMER".equals(role) ? PermissionScope.CLIENT
                            : qual != null && SELF.contains(qual) ? PermissionScope.SELF : PermissionScope.PROJECT)
                        : ("본인".equals(qual) ? PermissionScope.SELF : PermissionScope.ALL);
                for (char ch : t.group(1).toCharArray()) {
                    String action = switch (ch) {
                        case 'C' -> "create";
                        case 'R' -> "read";
                        case 'U' -> "update";
                        case 'D' -> "delete";
                        default -> "approve";
                    };
                    m.put(mod + ":" + res + ":" + action, scope);
                }
            }
            assertThat(m).as("해석 불가 셀 %s/%s: %s", role, module, cell).isNotEmpty();
            return m;
        }
    }

    // ---------------------------------------------------------------- ② HTTP 프로브 (00-IMPL permission-matrix.csv 예시 포함)

    UUID engineer;
    UUID projectA;
    UUID projectB;

    @BeforeEach
    void projects() throws Exception {
        UUID sales = actor();
        UUID client = createClient(sales, "합성발주처-matrix-" + UUID.randomUUID());
        projectA = project(client, "합성 A");
        projectB = project(client, "합성 B");
        engineer = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성기술자', DATE '2025-01-01', ?, ?)", engineer,
                "M-" + engineer.toString().substring(0, 8), engineer, engineer);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, 'PART', ?, ?)", projectA, engineer, LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(10));
    }

    private UUID project(UUID client, String name) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"" + name
                + "\",\"serviceType\":\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), actor(), "PM")).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource({
            "ENG,      POST, /api/v1/test/fld/projects/{A}/defects,   200",
            "ENG,      POST, /api/v1/test/fld/projects/{B}/defects,   403",
            "PM,       POST, /api/v1/test/fld/projects/{B}/defects,   403",
            "QA,       POST, /api/v1/test/qar/reviews/R1:approve,     200",
            "FIN,      POST, /api/v1/test/qar/reviews/R1:approve,     403",
            "SALES,    GET,  /api/v1/test/cst/projects/{A}/budget,    403",
            "EXEC,     GET,  /api/v1/test/cst/projects/{A}/budget,    200",
            "AUDITOR,  GET,  /api/v1/test/cst/projects/{B}/budget,    200",
            "CUSTOMER, GET,  /api/v1/test/hrq/engineers,              403",
            "SALES,    GET,  /api/v1/test/hrq/availability,           200",
            "ENG,      GET,  /api/v1/test/hrq/availability,           403",
            "EMPLOYEE, POST, /api/v1/test/cst/expenses,               200",
            "ENG,      POST, /api/v1/test/cst/expenses,               403",
            "AUDITOR,  GET,  /api/v1/test/aip/audit-logs,             200",
            "SYSADMIN, GET,  /api/v1/test/aip/audit-logs,             403",
            "SYSADMIN, POST, /api/v1/test/aip/models/M1:deploy,       200",
            "PM,       POST, /api/v1/test/aip/models/M1:deploy,       403",
            "LABELER,  POST, /api/v1/test/aip/label-tasks,            200",
            "AUDITOR,  POST, /api/v1/test/aip/label-tasks,            403",
    })
    @DisplayName("Step4 HTTP: @RequirePermission + 행 수준 권한")
    void http(String role, String method, String path, int expected) throws Exception {
        UUID actor = "ENG".equals(role) ? engineer : actor();
        String url = path.replace("{A}", projectA.toString()).replace("{B}", projectB.toString());
        mvc.perform(as(request(HttpMethod.valueOf(method), url), actor, role)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(expected));
    }
}
