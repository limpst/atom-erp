package com.atomeng.erp.fac.adapter.out.external;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공 API 설정 (설계 02 B8). 키는 환경변수로만 주입(커밋 금지, NFR-FAC-04). 키가 비면 ACL 은 NOT_CONFIGURED → 수기 모드.
 * ⚠️ 엔드포인트·파라미터는 공공데이터포털 명세서 최신본 확인 필요.
 */
@ConfigurationProperties("atom.fac.external")
public record FacExternalProperties(Juso juso, BuildingRegister buildingRegister) {

    public FacExternalProperties {
        juso = juso == null ? new Juso(null, null, null, null) : juso;
        buildingRegister = buildingRegister == null
                ? new BuildingRegister(null, null, null, null, null, null, null, null) : buildingRegister;
    }

    /** 행정안전부 도로명주소 검색 API */
    public record Juso(String baseUrl, String confmKey, Duration connectTimeout, Duration readTimeout) {
        public Juso {
            baseUrl = baseUrl == null ? "https://business.juso.go.kr/addrlink" : baseUrl;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        }
    }

    /** 공공데이터포털 건축HUB 건축물대장정보 서비스 */
    public record BuildingRegister(String baseUrl, String serviceKey, Duration connectTimeout, Duration readTimeout,
                                   Integer maxAttempts, Duration initialBackoff, Integer dailyQuota,
                                   Duration cacheTtl) {
        public BuildingRegister {
            baseUrl = baseUrl == null ? "https://apis.data.go.kr/1613000/BldRgstHubService" : baseUrl;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            initialBackoff = initialBackoff == null ? Duration.ofMillis(500) : initialBackoff;
            dailyQuota = dailyQuota == null ? 10_000 : dailyQuota;
            cacheTtl = cacheTtl == null ? Duration.ofHours(24) : cacheTtl;
        }
    }
}
