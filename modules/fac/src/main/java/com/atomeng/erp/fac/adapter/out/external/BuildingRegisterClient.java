package com.atomeng.erp.fac.adapter.out.external;

import com.atomeng.erp.fac.application.port.RegisterPorts.BuildingRegisterPort;
import com.atomeng.erp.fac.application.port.RegisterPorts.ExternalUnavailable;
import com.atomeng.erp.fac.domain.register.RegisterModels.Pnu;
import com.atomeng.erp.fac.domain.register.RegisterModels.RegisterLookupResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 건축HUB 건축물대장 ACL (FR-FAC-002, 02-IMPL Step 3). 총괄표제부·표제부·층별개요 3개 오퍼레이션.
 * <ul>
 *   <li>재시도: 타임아웃·5xx 최대 maxAttempts 회(지수 백오프) → 실패 시 예외 대신 unavailable → 수기 모드(TC-FAC-002)</li>
 *   <li>캐시: PNU 별 성공 결과 24h (운영은 Redis {@code fac:reg:{pnu}} — 지금은 프로세스 메모리)</li>
 *   <li>일일 쿼터: 조회 1회 = 호출 3건, 한도 초과 시 QUOTA_EXCEEDED (운영은 Redis 카운터)</li>
 * </ul>
 * ⚠️ 오퍼레이션명·파라미터(sigunguCd·bjdongCd·platGbCd·bun·ji)는 공공데이터포털 명세 최신본 확인 필요.
 */
public class BuildingRegisterClient implements BuildingRegisterPort {

    private static final Logger log = LoggerFactory.getLogger(BuildingRegisterClient.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private record Cached(RegisterLookupResult result, Instant expiresAt) {}

    private final RestClient http;
    private final FacExternalProperties.BuildingRegister props;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> quotaDay = new AtomicReference<>();
    private final AtomicInteger quotaUsed = new AtomicInteger();

    public BuildingRegisterClient(RestClient http, FacExternalProperties.BuildingRegister props, Clock clock) {
        this.http = http;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public RegisterLookupResult lookup(Pnu pnu) {
        if (props.serviceKey() == null || props.serviceKey().isBlank()) {
            return RegisterLookupResult.unavailable(pnu, "NOT_CONFIGURED");
        }
        Cached c = cache.get(pnu.value());
        if (c != null && c.expiresAt().isAfter(clock.instant())) {
            return c.result();
        }
        try {
            guardQuota(3);
            JsonNode recap = call("getBrRecapTitleInfo", pnu);   // 총괄표제부
            JsonNode titles = call("getBrTitleInfo", pnu);       // 표제부
            JsonNode floors = call("getBrFlrOulnInfo", pnu);     // 층별개요
            List<JsonNode> recapItems = RegisterTranslator.items(recap);
            String siteName = recapItems.isEmpty() ? null : recapItems.get(0).path("bldNm").asText(null);
            var result = new RegisterLookupResult(true, pnu, siteName,
                    RegisterTranslator.items(titles).stream().map(RegisterTranslator::toCandidate).toList(),
                    RegisterTranslator.items(floors).size(), null);
            cache.put(pnu.value(), new Cached(result, clock.instant().plus(props.cacheTtl())));
            return result;
        } catch (ExternalUnavailable e) {
            log.warn("건축물대장 조회 불가 pnu={} reason={} → 수기 모드", pnu.value(), e.reason());
            return RegisterLookupResult.unavailable(pnu, e.reason());
        }
    }

    private JsonNode call(String operation, Pnu pnu) {
        JsonNode root = Retry.call("building-register/" + operation, props.maxAttempts(), props.initialBackoff(),
                () -> http.get().uri(b -> b.path("/" + operation)
                                .queryParam("serviceKey", props.serviceKey())
                                .queryParam("sigunguCd", pnu.sigunguCd())
                                .queryParam("bjdongCd", pnu.bjdongCd())
                                .queryParam("platGbCd", pnu.platGbCd())
                                .queryParam("bun", pnu.bun())
                                .queryParam("ji", pnu.ji())
                                .queryParam("numOfRows", 100)
                                .queryParam("pageNo", 1)
                                .queryParam("_type", "json").build())
                        .retrieve().body(JsonNode.class));
        String code = RegisterTranslator.resultCode(root);
        if (!"00".equals(code)) {
            throw new ExternalUnavailable("22".equals(code) ? "QUOTA_EXCEEDED" : "API_ERROR_" + code, null);
        }
        return root;
    }

    private void guardQuota(int calls) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (!today.equals(quotaDay.get())) {
            quotaDay.set(today);
            quotaUsed.set(0);
        }
        if (quotaUsed.addAndGet(calls) > props.dailyQuota()) {
            throw new ExternalUnavailable("QUOTA_EXCEEDED", null);
        }
    }
}
