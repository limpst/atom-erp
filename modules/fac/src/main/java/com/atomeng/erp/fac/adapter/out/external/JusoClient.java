package com.atomeng.erp.fac.adapter.out.external;

import com.atomeng.erp.fac.application.port.RegisterPorts.AddressPort;
import com.atomeng.erp.fac.application.port.RegisterPorts.ExternalUnavailable;
import com.atomeng.erp.fac.domain.register.RegisterModels.NormalizedAddress;
import com.atomeng.erp.fac.domain.register.RegisterModels.Pnu;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.web.client.RestClient;

/**
 * 도로명주소 검색 API ACL (FR-FAC-001). ⚠️ 응답 필드(admCd·mtYn·lnbrMnnm·lnbrSlno)는 명세 최신본 확인 필요.
 * 좌표(지오코딩)는 별도 좌표제공 API 라 미구현 — PostGIS 프로필 이후.
 */
public class JusoClient implements AddressPort {

    private final RestClient http;
    private final FacExternalProperties.Juso props;

    public JusoClient(RestClient http, FacExternalProperties.Juso props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public Optional<NormalizedAddress> normalize(String keyword) {
        if (props.confmKey() == null || props.confmKey().isBlank()) {
            throw new ExternalUnavailable("NOT_CONFIGURED", null);
        }
        JsonNode body = Retry.call("juso", 2, java.time.Duration.ofMillis(100), () -> http.get()
                .uri(b -> b.path("/addrLinkApi.do")
                        .queryParam("confmKey", props.confmKey())
                        .queryParam("keyword", keyword)
                        .queryParam("currentPage", 1).queryParam("countPerPage", 5)
                        .queryParam("resultType", "json").build())
                .retrieve().body(JsonNode.class));
        JsonNode results = body == null ? null : body.path("results");
        if (results == null || !"0".equals(results.path("common").path("errorCode").asText())) {
            throw new ExternalUnavailable("API_ERROR", null);
        }
        JsonNode list = results.path("juso");
        if (!list.isArray() || list.isEmpty()) {
            return Optional.empty();
        }
        JsonNode j = list.get(0);
        Pnu pnu = Pnu.of(j.path("admCd").asText(), "1".equals(j.path("mtYn").asText()),
                j.path("lnbrMnnm").asText(), j.path("lnbrSlno").asText());
        return Optional.of(new NormalizedAddress(j.path("roadAddr").asText(null), j.path("jibunAddr").asText(null),
                j.path("admCd").asText(), pnu, j.path("bdNm").asText(null)));
    }
}
