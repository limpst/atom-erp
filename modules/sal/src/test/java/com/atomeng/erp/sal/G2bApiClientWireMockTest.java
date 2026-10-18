package com.atomeng.erp.sal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.sal.adapter.out.g2b.G2bApiClient;
import com.atomeng.erp.sal.adapter.out.g2b.G2bProperties;
import com.atomeng.erp.sal.application.port.BidNoticeSourcePort.SourceUnavailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 01-IMPL Step 10 G2bApiClient — WireMock 대역(합성 응답: 정상 / 단건 / 오류 XML / resultCode 오류 / 500 / 빈 페이지).
 * ⚠️ 오퍼레이션명 · 응답 필드는 공공데이터포털 명세서 최신본 확인 필요.
 */
class G2bApiClientWireMockTest {

    static WireMockServer wm;
    static final ObjectMapper OM = new ObjectMapper();
    static final String OP = "/g2b/getBidPblancListInfoServc";

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

    static G2bApiClient client(String key) {
        return G2bApiClient.of(new G2bProperties(wm.baseUrl() + "/g2b", key, null, 100, Duration.ofSeconds(1),
                Duration.ofMillis(300), 3, Duration.ofMillis(10)), OM);
    }

    static final String ITEM_00 = """
            {"bidNtceNo":"R26BK00000001","bidNtceOrd":"000","bidNtceNm":"합성시 공공청사 정밀안전진단 용역",
             "ntceInsttNm":"합성시","dminsttNm":"합성시 회계과","cntrctCnclsMthdNm":"제한경쟁","sucsfbidMthdNm":"적격심사",
             "ntceKindNm":"등록공고","presmptPrce":"182000000","asignBdgtAmt":"200,200,000","prtcptLmtRgnNm":"서울특별시",
             "bidNtceDt":"2026-10-12 10:00:00","bidClseDt":"2026-10-26 18:00:00","opengDt":"2026-10-27 11:00:00",
             "bidNtceDtlUrl":"https://example.invalid/notice/1"}""";

    static String page(String items, int total, int pageNo) {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"정상"},
                 "body":{"items":%s,"numOfRows":100,"pageNo":%d,"totalCount":%d}}}""".formatted(items, pageNo, total);
    }

    static final OffsetDateTime FROM = OffsetDateTime.of(2026, 10, 18, 23, 50, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime TO = OffsetDateTime.of(2026, 10, 19, 0, 30, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("정상 목록: 필드 매핑(추정가격 · 기초금액 쉼표 · KST 일시), 조회 구간은 KST yyyyMMddHHmm(UTC 23:50 → 0850)")
    void parsesListAndSendsKstWindow() {
        wm.stubFor(get(urlPathEqualTo(OP)).willReturn(okJson(page("[" + ITEM_00 + "]", 1, 1))));
        var p = client("synthetic-key").fetchNotices(FROM, TO, 1);
        assertThat(p.items()).hasSize(1);
        var it = p.items().get(0);
        assertThat(it.bidNo()).isEqualTo("R26BK00000001");
        assertThat(it.bidSeq()).isEqualTo("000");
        assertThat(it.estPrice()).isEqualByComparingTo("182000000");
        assertThat(it.basePrice()).isEqualByComparingTo("200200000");
        assertThat(it.closeAt()).isEqualTo(OffsetDateTime.of(2026, 10, 26, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(it.isCancellation()).isFalse();
        assertThat(it.raw().get("bidNtceNm").asText()).contains("정밀안전진단");
        assertThat(p.hasNext()).isFalse();
        wm.verify(getRequestedFor(urlPathEqualTo(OP)).withQueryParam("inqryBgnDt", equalTo("202610190850"))
                .withQueryParam("inqryEndDt", equalTo("202610190930")).withQueryParam("type", equalTo("json"))
                .withQueryParam("pageNo", equalTo("1")).withQueryParam("serviceKey", equalTo("synthetic-key")));
    }

    @Test
    @DisplayName("items.item 단건(객체) · 빈 페이지 · 다음 페이지 판정(totalCount)")
    void singleItemEmptyAndPaging() {
        var single = G2bApiClient.parse(page("{\"item\":" + ITEM_00.replace("등록공고", "취소공고") + "}", 250, 1), OM);
        assertThat(single.items()).hasSize(1);
        assertThat(single.items().get(0).isCancellation()).isTrue();
        assertThat(single.hasNext()).isTrue();
        var empty = G2bApiClient.parse(page("\"\"", 0, 1), OM);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.hasNext()).isFalse();
    }

    @Test
    @DisplayName("type=json 이어도 오류 XML · resultCode 비정상 · 키 미설정 → SourceUnavailable(수집 실패 기록 대상)")
    void errorBodies() {
        assertThatThrownBy(() -> G2bApiClient.parse("<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30"
                + "</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>", OM))
                .isInstanceOf(SourceUnavailable.class).hasMessageStartingWith("XML_ERROR");
        assertThatThrownBy(() -> G2bApiClient.parse(
                "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED NUMBER OF SERVICE REQUESTS\"}}}", OM))
                .hasMessageStartingWith("RESULT_CODE:22");
        assertThatThrownBy(() -> client("").fetchNotices(FROM, TO, 1)).hasMessage("NOT_CONFIGURED");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("TC-SAL-003(부분): 500 ×3 → 3회 재시도 후 HTTP_5XX / 500 → 200 은 재시도로 성공")
    void retriesServerErrors() {
        wm.stubFor(get(urlPathEqualTo(OP)).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client("k").fetchNotices(FROM, TO, 1)).isInstanceOf(SourceUnavailable.class)
                .hasMessage("HTTP_5XX");
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(OP)))).hasSize(3);

        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo(OP)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        wm.stubFor(get(urlPathEqualTo(OP)).inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(okJson(page("[" + ITEM_00 + "]", 1, 1))));
        assertThat(client("k").fetchNotices(FROM, TO, 1).items()).hasSize(1);
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(OP)))).hasSize(2);
    }

    @Test
    @DisplayName("4xx 는 재시도하지 않음(키 오류 등) — 1회 호출 후 HTTP_401")
    void clientErrorNoRetry() {
        wm.stubFor(get(urlPathEqualTo(OP)).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client("k").fetchNotices(FROM, TO, 1)).hasMessage("HTTP_401");
        assertThat(wm.findAll(getRequestedFor(urlPathEqualTo(OP)))).hasSize(1);
    }
}
