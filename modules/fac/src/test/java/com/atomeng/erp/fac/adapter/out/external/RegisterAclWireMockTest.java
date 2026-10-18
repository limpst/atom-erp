package com.atomeng.erp.fac.adapter.out.external;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.fac.domain.register.RegisterModels.Pnu;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 02-IMPL Step 3 ACL — WireMock 대역(합성 응답). ⚠️ 응답 스키마는 공공데이터포털·도로명주소 명세 최신본 확인 필요.
 */
class RegisterAclWireMockTest {

    static WireMockServer wm;
    static final AtomicInteger BUN = new AtomicInteger(1000);

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    static BuildingRegisterClient client(int maxAttempts, int quota, String key) {
        var props = new FacExternalProperties.BuildingRegister(wm.baseUrl() + "/hub", key, Duration.ofSeconds(1),
                Duration.ofMillis(250), maxAttempts, Duration.ofMillis(10), quota, Duration.ofHours(24));
        return new BuildingRegisterClient(FacExternalConfig.restClient(props.baseUrl(), props.connectTimeout(),
                props.readTimeout()), props, Clock.systemUTC());
    }

    static Pnu pnu() {
        return new Pnu("1156011000" + "1" + "%04d".formatted(BUN.incrementAndGet()) + "0000");
    }

    static String hub(String items, int total) {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"items":%s,"numOfRows":100,"pageNo":1,"totalCount":%d}}}""".formatted(items, total);
    }

    static final String TITLE_A = """
            {"mgmBldrgstPk":"11560-900001","bldNm":"합성타워","dongNm":"A동","mainPurpsCd":"14000",
             "mainPurpsCdNm":"업무시설","strctCd":"21","strctCdNm":"철근콘크리트구조","totArea":"31250.55",
             "grndFlrCnt":"20","ugrndFlrCnt":"4","useAprDay":"19980315"}""";
    static final String TITLE_B = """
            {"mgmBldrgstPk":"11560-900002","bldNm":"합성타워","dongNm":"B동","mainPurpsCd":"14000",
             "mainPurpsCdNm":"업무시설","strctCd":"22","strctCdNm":"철골철근콘크리트구조","totArea":"8000",
             "grndFlrCnt":"8","ugrndFlrCnt":"2","useAprDay":""}""";

    void stubAll(Pnu p, String titlesItems, int total) {
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).withQueryParam("bun", equalTo(p.bun()))
                .willReturn(okJson(hub("{\"item\":{\"bldNm\":\"합성타워 단지\"}}", 1))));
        wm.stubFor(get(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", equalTo(p.bun()))
                .willReturn(okJson(hub(titlesItems, total))));
        wm.stubFor(get(urlPathEqualTo("/hub/getBrFlrOulnInfo")).withQueryParam("bun", equalTo(p.bun()))
                .willReturn(okJson(hub("{\"item\":[{\"flrNo\":\"1\"},{\"flrNo\":\"2\"}]}", 2))));
    }

    @Test
    @DisplayName("Step3: 결과 1건이면 item 이 객체 — 표제부 번역(용도·구조·연면적·층수·사용승인일·스냅샷)")
    void singleItemObject() {
        var p = pnu();
        stubAll(p, "{\"item\":" + TITLE_A + "}", 1);
        var r = client(3, 1000, "k").lookup(p);
        assertThat(r.available()).isTrue();
        assertThat(r.siteName()).isEqualTo("합성타워 단지");
        assertThat(r.floorOutlineCount()).isEqualTo(2);
        assertThat(r.titles()).singleElement().satisfies(t -> {
            assertThat(t.dongName()).isEqualTo("A동");
            assertThat(t.structureNm()).isEqualTo("철근콘크리트구조");
            assertThat(t.grossFloorArea()).isEqualByComparingTo(new BigDecimal("31250.55"));
            assertThat(t.floorsAbove()).isEqualTo((short) 20);
            assertThat(t.floorsBelow()).isEqualTo((short) 4);
            assertThat(t.approvalDate()).isEqualTo(LocalDate.of(1998, 3, 15));
            assertThat(t.snapshotJson()).contains("mgmBldrgstPk");
        });
        // PNU → 쿼리 파라미터 분해 (⚠️ 명세 확인 필요)
        wm.verify(getRequestedFor(urlPathEqualTo("/hub/getBrTitleInfo"))
                .withQueryParam("sigunguCd", equalTo("11560")).withQueryParam("bjdongCd", equalTo("11000"))
                .withQueryParam("platGbCd", equalTo("0")).withQueryParam("ji", equalTo("0000"))
                .withQueryParam("_type", equalTo("json")));
    }

    @Test
    @DisplayName("Step3: 배열 2건(사용승인일 공란 → null), 0건(items 빈 문자열)")
    void arrayAndEmpty() {
        var p = pnu();
        stubAll(p, "{\"item\":[" + TITLE_A + "," + TITLE_B + "]}", 2);
        var r = client(3, 1000, "k").lookup(p);
        assertThat(r.titles()).hasSize(2);
        assertThat(r.titles().get(1).approvalDate()).isNull();
        assertThat(r.pick(null, "B동")).get().extracting("mgmBldrgstPk").isEqualTo("11560-900002");
        assertThat(r.pick(null, null)).isEmpty(); // 다동은 선택 필요

        var empty = pnu();
        stubAll(empty, "\"\"", 0);
        assertThat(client(3, 1000, "k").lookup(empty).titles()).isEmpty();
    }

    @Test
    @DisplayName("TC-FAC-002(ACL): 타임아웃 → 재시도 3회 후 unavailable(TIMEOUT) — 예외 없이 수기 모드로")
    void timeoutRetriesThenUnavailable() {
        var p = pnu();
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo"))
                .willReturn(okJson(hub("\"\"", 0)).withFixedDelay(800)));
        var r = client(3, 1000, "k").lookup(p);
        assertThat(r.available()).isFalse();
        assertThat(r.failureReason()).isEqualTo("TIMEOUT");
        wm.verify(3, getRequestedFor(urlPathEqualTo("/hub/getBrRecapTitleInfo")));
    }

    @Test
    @DisplayName("Step3: 5xx 2회 후 성공 → 재시도로 회복")
    void serverErrorThenRecover() {
        var p = pnu();
        stubAll(p, "{\"item\":" + TITLE_A + "}", 1);
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).inScenario("flaky")
                .whenScenarioStateIs("second").willReturn(aResponse().withStatus(502)).willSetStateTo("ok"));
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).inScenario("flaky")
                .whenScenarioStateIs("ok").willReturn(okJson(hub("\"\"", 0))));
        var r = client(3, 1000, "k").lookup(p);
        assertThat(r.available()).isTrue();
        wm.verify(3, getRequestedFor(urlPathEqualTo("/hub/getBrRecapTitleInfo")));
    }

    @Test
    @DisplayName("Step3: 같은 PNU 2회 조회 → 외부 호출 1회(24h 캐시)")
    void cacheHit() {
        var p = pnu();
        stubAll(p, "{\"item\":" + TITLE_A + "}", 1);
        var c = client(3, 1000, "k");
        c.lookup(p);
        c.lookup(p);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/hub/getBrTitleInfo")));
    }

    @Test
    @DisplayName("Step3: 일일 쿼터 초과 → QUOTA_EXCEEDED, 결과코드 오류 → API_ERROR_xx, 키 미설정 → NOT_CONFIGURED")
    void quotaErrorsAndNotConfigured() {
        var c = client(3, 3, "k"); // 조회 1회 = 3호출
        var p1 = pnu();
        stubAll(p1, "{\"item\":" + TITLE_A + "}", 1);
        assertThat(c.lookup(p1).available()).isTrue();
        assertThat(c.lookup(pnu()).failureReason()).isEqualTo("QUOTA_EXCEEDED");

        var p2 = pnu();
        wm.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).withQueryParam("bun", equalTo(p2.bun()))
                .willReturn(okJson("{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"KEY ERROR\"}}}")));
        assertThat(client(3, 1000, "k").lookup(p2).failureReason()).isEqualTo("API_ERROR_30");
        assertThat(client(3, 1000, " ").lookup(pnu()).failureReason()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("Step3: 도로명주소 → PNU 조립(법정동 10 + 필지구분 1/2(산) + 본번 4 + 부번 4)")
    void jusoPnu() {
        wm.stubFor(get(urlPathEqualTo("/juso/addrLinkApi.do")).withQueryParam("keyword", equalTo("합성로 7"))
                .willReturn(okJson("""
                        {"results":{"common":{"errorCode":"0","totalCount":"1"},"juso":[{"roadAddr":"서울 합성구 합성로 7",
                         "jibunAddr":"서울 합성구 합성동 산 12-3","admCd":"1156011000","mtYn":"1","lnbrMnnm":"12",
                         "lnbrSlno":"3","bdNm":"합성빌딩"}]}}""")));
        wm.stubFor(get(urlPathEqualTo("/juso/addrLinkApi.do")).withQueryParam("keyword", equalTo("없는주소"))
                .willReturn(okJson("{\"results\":{\"common\":{\"errorCode\":\"0\",\"totalCount\":\"0\"},\"juso\":[]}}")));
        var juso = new JusoClient(FacExternalConfig.restClient(wm.baseUrl() + "/juso", Duration.ofSeconds(1),
                Duration.ofSeconds(1)), new FacExternalProperties.Juso(wm.baseUrl() + "/juso", "k", null, null));
        var a = juso.normalize("합성로 7").orElseThrow();
        assertThat(a.pnu().value()).isEqualTo("1156011000" + "2" + "0012" + "0003");
        assertThat(a.pnu().platGbCd()).isEqualTo("1");
        assertThat(a.roadAddress()).isEqualTo("서울 합성구 합성로 7");
        assertThat(juso.normalize("없는주소")).isEmpty();
    }
}
