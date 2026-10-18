package com.atomeng.erp.fac.adapter.out.external;

import com.atomeng.erp.fac.domain.register.RegisterModels.BuildingCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 건축HUB 응답 → 내부 값 객체 (외부 스키마 격리). ⚠️ 필드명은 공공데이터포털 명세 최신본 확인 필요.
 * 함정(02-IMPL Step 3): 결과 1건이면 item 이 배열이 아니라 객체, 0건이면 items 가 빈 문자열.
 */
public final class RegisterTranslator {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private RegisterTranslator() {}

    /** response.header.resultCode — "00" 정상 */
    public static String resultCode(JsonNode root) {
        return root == null ? "" : root.path("response").path("header").path("resultCode").asText("");
    }

    public static List<JsonNode> items(JsonNode root) {
        JsonNode item = root.path("response").path("body").path("items").path("item");
        List<JsonNode> out = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(out::add);
        } else if (item.isObject()) {
            out.add(item);
        }
        return out;
    }

    public static BuildingCandidate toCandidate(JsonNode t) {
        return new BuildingCandidate(
                text(t, "mgmBldrgstPk"),
                t.path("bldNm").asText(""),
                t.path("dongNm").asText("").trim(),
                text(t, "mainPurpsCd"), text(t, "mainPurpsCdNm"),
                text(t, "strctCd"), text(t, "strctCdNm"),
                decimal(t, "totArea"),
                shortOrNull(t, "grndFlrCnt"), shortOrNull(t, "ugrndFlrCnt"),
                parseYmd(t.path("useAprDay").asText("")),
                t.toString());
    }

    static LocalDate parseYmd(String s) {
        if (s == null || s.isBlank() || !s.trim().matches("\\d{8}") || s.trim().equals("00000000")) {
            return null; // 준공일 미상 — 오래된 건물에 흔함
        }
        return LocalDate.parse(s.trim(), YMD);
    }

    private static String text(JsonNode t, String f) {
        String v = t.path(f).asText("");
        return v.isBlank() ? null : v.trim();
    }

    private static BigDecimal decimal(JsonNode t, String f) {
        String v = t.path(f).asText("");
        try {
            return v.isBlank() ? null : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Short shortOrNull(JsonNode t, String f) {
        String v = t.path(f).asText("");
        return v.isBlank() ? null : Short.valueOf(v.trim());
    }
}
