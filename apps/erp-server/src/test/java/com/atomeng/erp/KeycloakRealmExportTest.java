package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.identity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * deploy/keycloak realm export 가 코드(Role enum·JwtAuthConverter 클레임 규약)와 어긋나지 않는지 검증 (00-IMPL Step 4).
 */
class KeycloakRealmExportTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode realm(String file) throws Exception {
        Path root = Path.of(System.getProperty("atom.repo.root", "../.."));
        return OM.readTree(Files.readString(root.resolve("deploy/keycloak").resolve(file)));
    }

    private static Set<String> names(JsonNode arr, String field) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get(field).asText()));
        return Set.copyOf(out);
    }

    private static Set<String> claimNames(JsonNode realm) {
        List<String> out = new ArrayList<>();
        realm.get("clientScopes").forEach(s -> s.get("protocolMappers").forEach(m -> {
            if (!AUDIENCE_MAPPER.equals(m.get("protocolMapper").asText())) {   // audience 매퍼는 클레임 이름이 없다(aud)
                out.add(m.get("config").get("claim.name").asText());
            }
        }));
        return Set.copyOf(out);
    }

    static final String AUDIENCE_MAPPER = "oidc-audience-mapper";

    @Test
    @DisplayName("보안 검토 CMN-06: 두 realm 의 기본 scope 가 aud=atom-erp-api 를 넣고, 서버 기본 허용 audience 와 같다")
    void audienceMapper() throws Exception {
        String yml = Files.readString(Path.of(System.getProperty("atom.repo.root", "../.."))
                .resolve("apps/erp-server/src/main/resources/application.yml"));
        assertThat(yml).contains("audiences: ${ATOM_SECURITY_AUDIENCES:atom-erp-api}");
        for (String f : List.of("atom-internal-realm.json", "atom-portal-realm.json")) {
            JsonNode r = realm(f);
            Set<String> defaults = names(r.get("defaultDefaultClientScopes"));
            List<String> auds = new ArrayList<>();
            r.get("clientScopes").forEach(sc -> {
                if (defaults.contains(sc.get("name").asText())) {
                    sc.get("protocolMappers").forEach(m -> {
                        if (AUDIENCE_MAPPER.equals(m.get("protocolMapper").asText())) {
                            auds.add(m.get("config").get("included.custom.audience").asText());
                            assertThat(m.get("config").get("access.token.claim").asText()).isEqualTo("true");
                        }
                    });
                }
            });
            assertThat(auds).as(f).containsExactly("atom-erp-api");
        }
    }

    private static Set<String> names(JsonNode textArray) {
        List<String> out = new ArrayList<>();
        textArray.forEach(n -> out.add(n.asText()));
        return Set.copyOf(out);
    }

    @Test
    @DisplayName("atom-internal: 역할 = Role enum 의 INTERNAL 11종(EMPLOYEE·FIN·AUDITOR 포함)")
    void internalRolesMatchEnum() throws Exception {
        JsonNode r = realm("atom-internal-realm.json");
        Set<String> expected = Stream.of(Role.values()).filter(x -> x.realm() == Role.Realm.INTERNAL)
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(names(r.get("roles").get("realm"), "name")).isEqualTo(expected).contains("EMPLOYEE", "FIN", "AUDITOR");
    }

    @Test
    @DisplayName("atom-internal: 클라이언트 4종, 공개 클라이언트는 PKCE S256·password grant 비활성, 비밀값은 플레이스홀더")
    void internalClients() throws Exception {
        JsonNode r = realm("atom-internal-realm.json");
        assertThat(names(r.get("clients"), "clientId"))
                .containsExactlyInAnyOrder("erp-web", "field-app", "erp-server", "ai-service");
        r.get("clients").forEach(c -> {
            if (c.get("publicClient").asBoolean()) {
                assertThat(c.get("attributes").get("pkce.code.challenge.method").asText()).isEqualTo("S256");
                assertThat(c.get("directAccessGrantsEnabled").asBoolean()).isFalse();
            } else {
                assertThat(c.get("secret").asText()).startsWith("${"); // 실제 비밀값 커밋 금지
            }
        });
        assertThat(r.get("users")).isEmpty();
    }

    @Test
    @DisplayName("클레임 규약: internal = roles·eid, portal = roles·cid (JwtAuthConverter 와 일치)")
    void claimMappers() throws Exception {
        assertThat(claimNames(realm("atom-internal-realm.json"))).containsExactlyInAnyOrder("roles", "eid");
        assertThat(claimNames(realm("atom-portal-realm.json"))).containsExactlyInAnyOrder("roles", "cid");
    }

    @Test
    @DisplayName("atom-portal: 역할 CUSTOMER 1개, 클라이언트 portal-web")
    void portal() throws Exception {
        JsonNode r = realm("atom-portal-realm.json");
        assertThat(names(r.get("roles").get("realm"), "name")).containsExactly("CUSTOMER");
        assertThat(names(r.get("clients"), "clientId")).containsExactly("portal-web");
    }

    @Test
    @DisplayName("설계 8.1: MFA 필수 역할(EXEC·QA·ADMIN·SYSADMIN)이 realm 설명·조건부 OTP 흐름과 일치")
    void mfa() throws Exception {
        JsonNode r = realm("atom-internal-realm.json");
        Set<String> mfaInJson = new java.util.HashSet<>();
        r.get("roles").get("realm").forEach(n -> {
            if (n.get("description").asText().contains("MFA 필수")) {
                mfaInJson.add(n.get("name").asText());
            }
        });
        assertThat(mfaInJson).isEqualTo(Stream.of(Role.values()).filter(Role::mfaRequired).map(Enum::name)
                .collect(Collectors.toSet()));
        assertThat(r.get("browserFlow").asText()).isEqualTo("atom-browser-conditional-otp");
    }
}
