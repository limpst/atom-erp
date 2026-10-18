package com.atomeng.erp.sal.adapter.out.g2b;

import com.atomeng.erp.sal.application.port.BidNoticeSourcePort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 나라장터 용역 공고 목록 ACL (01-IMPL Step 10 G2bApiClient). ⚠️ 명세 확인 필요 — 필드명은 공개 명세 관례(bidNtceNo · bidNtceOrd ·
 * bidNtceNm · ntceInsttNm · dminsttNm · cntrctCnclsMthdNm · sucsfbidMthdNm · ntceKindNm · presmptPrce · asignBdgtAmt ·
 * prtcptLmtRgnNm · bidNtceDt · bidClseDt · opengDt · bidNtceDtlUrl) 기준 가정이며, 원문은 raw 로 보존한다.
 * <ul>
 *   <li>시간 파라미터는 KST yyyyMMddHHmm (01-IMPL 팁: UTC 를 넣으면 9시간 누락)</li>
 *   <li>type=json 이어도 오류 시 XML 이 올 수 있어 Content-Type 이 아니라 본문 첫 글자로 판별</li>
 *   <li>타임아웃 · I/O · 5xx 만 재시도(기본 3회, 지수 백오프 최대 8초) — Resilience4j CircuitBreaker 는 후속(의존성 없이 같은 정책)</li>
 * </ul>
 */
public class G2bApiClient implements BidNoticeSourcePort {

    private static final Logger log = LoggerFactory.getLogger(G2bApiClient.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter PARAM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final List<DateTimeFormatter> READ = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"), DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    private final RestClient http;
    private final G2bProperties props;
    private final ObjectMapper om;

    public G2bApiClient(RestClient http, G2bProperties props, ObjectMapper om) {
        this.http = http;
        this.props = props;
        this.om = om;
    }

    public static G2bApiClient of(G2bProperties p, ObjectMapper om) {
        return new G2bApiClient(G2bConfig.restClient(p), p, om);
    }

    /** 조회 시각 파라미터(KST yyyyMMddHHmm) */
    public static String kst(OffsetDateTime t) {
        return t.atZoneSameInstant(KST).format(PARAM);
    }

    @Override
    public Page fetchNotices(OffsetDateTime from, OffsetDateTime to, int pageNo) {
        if (props.serviceKey() == null || props.serviceKey().isBlank()) {
            throw new SourceUnavailable("NOT_CONFIGURED", null);
        }
        String body = call(() -> http.get()
                .uri(b -> b.path("/" + props.noticeOperation())
                        .queryParam("serviceKey", props.serviceKey())
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", props.pageSize())
                        .queryParam("type", "json")
                        .queryParam("inqryDiv", 1)
                        .queryParam("inqryBgnDt", kst(from))
                        .queryParam("inqryEndDt", kst(to)).build())
                .retrieve().body(String.class));
        return parse(body, om);
    }

    /** 응답 파싱(정적 — WireMock 없이도 단위 시험 가능) */
    public static Page parse(String body, ObjectMapper om) {
        if (body == null || body.isBlank()) {
            throw new SourceUnavailable("EMPTY_BODY", null);
        }
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("<")) {   // 오류 XML (SERVICE_KEY_IS_NOT_REGISTERED_ERROR 등)
            throw new SourceUnavailable("XML_ERROR:" + abbreviate(trimmed, 200), null);
        }
        JsonNode root;
        try {
            root = om.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new SourceUnavailable("INVALID_JSON", e);
        }
        JsonNode resp = root.has("response") ? root.path("response") : root;
        String code = resp.path("header").path("resultCode").asText("");
        if (!"00".equals(code)) {
            throw new SourceUnavailable("RESULT_CODE:" + code + ":" + resp.path("header").path("resultMsg").asText(""),
                    null);
        }
        JsonNode bodyNode = resp.path("body");
        JsonNode items = bodyNode.path("items");
        if (items.has("item")) {
            items = items.path("item");
        }
        List<NoticeItem> list = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(i -> list.add(item(i)));
        } else if (items.isObject() && !items.isEmpty()) {
            list.add(item(items));
        }
        return new Page(List.copyOf(list), bodyNode.path("pageNo").asInt(1),
                bodyNode.path("numOfRows").asInt(Math.max(1, list.size())), bodyNode.path("totalCount").asInt(list.size()));
    }

    static NoticeItem item(JsonNode i) {
        String seq = text(i, "bidNtceOrd");
        return new NoticeItem(text(i, "bidNtceNo"), seq == null ? "000" : seq, text(i, "bidNtceNm"),
                text(i, "ntceInsttNm"), text(i, "dminsttNm"), text(i, "cntrctCnclsMthdNm"), text(i, "sucsfbidMthdNm"),
                text(i, "ntceKindNm"), money(i, "presmptPrce"), money(i, "asignBdgtAmt"), text(i, "prtcptLmtRgnNm"),
                time(i, "bidNtceDt"), time(i, "opengDt"), time(i, "bidClseDt"), text(i, "bidNtceDtlUrl"), i);
    }

    private static String text(JsonNode i, String f) {
        JsonNode n = i.get(f);
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    private static BigDecimal money(JsonNode i, String f) {
        String s = text(i, f);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static OffsetDateTime time(JsonNode i, String f) {
        String s = text(i, f);
        if (s == null) {
            return null;
        }
        for (DateTimeFormatter fmt : READ) {
            try {
                return LocalDateTime.parse(s, fmt).atZone(KST).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
                // 다음 형식 시도
            }
        }
        return null;
    }

    private <T> T call(Supplier<T> action) {
        Duration backoff = props.initialBackoff();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                log.warn("g2b 호출 실패 {}/{}: {}", attempt, props.maxAttempts(), e.getClass().getSimpleName());
                if (attempt < props.maxAttempts()) {
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
                }
            } catch (HttpClientErrorException e) {
                throw new SourceUnavailable("HTTP_" + e.getStatusCode().value(), e);
            }
        }
        throw new SourceUnavailable(last instanceof ResourceAccessException ? "TIMEOUT" : "HTTP_5XX", last);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailable("INTERRUPTED", e);
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
