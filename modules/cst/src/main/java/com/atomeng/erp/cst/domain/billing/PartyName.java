package com.atomeng.erp.cst.domain.billing;

import java.util.regex.Pattern;

/**
 * 거래처 · 입금자 이름 정규화 (BR-CST-BIL-007). 법인 표기(㈜ · (주) · 주식회사 …)와 공백 · 기호를 지우고 소문자로 바꾼다.
 * 같은 회사가 통장과 세금계산서에 다르게 적혀도 같은 글자열이 되게 하는 것이 목적이다.
 */
public final class PartyName {
    private PartyName() {}

    private static final Pattern LEGAL =
            Pattern.compile("주식회사|유한책임회사|유한회사|사단법인|재단법인|\\(주\\)|㈜|\\(유\\)|\\(사\\)|\\(재\\)");
    private static final Pattern NOISE = Pattern.compile("[^0-9A-Za-z가-힣]");

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = LEGAL.matcher(raw).replaceAll("");
        return NOISE.matcher(s).replaceAll("").toLowerCase(java.util.Locale.ROOT);
    }
}
