package com.atomeng.devserver;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * dev 서버용 공공 API(도로명주소 · 건축HUB) 대역 — 127.0.0.1 임의 포트, 합성 응답만(실존 주소 아님).
 *
 * <ul>
 *   <li>"합성로 1000"~"합성로 1999": 표제부 1건(지상 5 · 지하 1, 사용승인 2001-05-20) → 자동 입력 · VERIFIED</li>
 *   <li>"합성로 2000"~"합성로 2999": 표제부 2건(101동 · 102동) → 동 선택 필요(FAC-422-001 registerCandidates)</li>
 *   <li>그 밖의 주소: 도로명주소 0건 → ADDRESS_NOT_FOUND → 수기 입력</li>
 * </ul>
 */
final class DevPublicApiStub {

    private DevPublicApiStub() {}

    static String start() {
        WireMockServer s = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort()
                .globalTemplating(true));
        s.start();
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        String bun = "{{regexExtract request.query.keyword '[0-9]{4}$'}}";
        s.stubFor(get(urlPathEqualTo("/juso/addrLinkApi.do")).atPriority(10)
                .willReturn(okJson("{\"results\":{\"common\":{\"errorCode\":\"0\",\"totalCount\":\"0\"},\"juso\":[]}}")));
        s.stubFor(get(urlPathEqualTo("/juso/addrLinkApi.do")).atPriority(1)
                .withQueryParam("keyword", matching("^합성로 [12][0-9]{3}$"))
                .willReturn(okJson("""
                        {"results":{"common":{"errorCode":"0","totalCount":"1"},"juso":[{"roadAddr":"서울 합성구 합성로 %s",
                         "jibunAddr":"서울 합성구 예시동 %s-7","admCd":"1156011000","mtYn":"0","lnbrMnnm":"%s",
                         "lnbrSlno":"7","bdNm":"(합성) 합성로 %s 빌딩"}]}}""".formatted(bun, bun, bun, bun))));
        String hub = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":%s}}}";
        String empty = hub.formatted("\"\"");
        s.stubFor(get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).willReturn(okJson(empty)));
        s.stubFor(get(urlPathEqualTo("/hub/getBrFlrOulnInfo")).willReturn(okJson(empty)));
        String one = """
                {"mgmBldrgstPk":"11560-D{{request.query.bun}}-%s","bldNm":"(합성) 합성로 {{request.query.bun}} 빌딩",
                 "dongNm":"%s","mainPurpsCdNm":"업무시설","strctCdNm":"철근콘크리트구조","totArea":"%s",
                 "grndFlrCnt":"%d","ugrndFlrCnt":"1","useAprDay":"20010520"}""";
        s.stubFor(get(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", matching("1[0-9]{3}"))
                .willReturn(okJson(hub.formatted("{\"item\":" + one.formatted("1", "", "12000.5", 5) + "}"))));
        s.stubFor(get(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", matching("2[0-9]{3}"))
                .willReturn(okJson(hub.formatted("{\"item\":[" + one.formatted("1", "101동", "8200.0", 15) + ","
                        + one.formatted("2", "102동", "8100.0", 15) + "]}"))));
        System.out.println("[dev-server] 공공 API 대역(WireMock) " + s.baseUrl());
        return s.baseUrl();
    }
}
