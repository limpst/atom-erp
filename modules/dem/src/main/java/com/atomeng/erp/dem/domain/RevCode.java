package com.atomeng.erp.dem.domain;

import java.util.Optional;

/** 리비전 채번 (10-IMPL Step 11) — 내부 A → B … → Z → AA → AB, 발행 0 → 1 → 2 … */
public final class RevCode {

    private RevCode() {}

    /** 내부 리비전: null → A, Z → AA, AZ → BA, ZZ → AAA */
    public static String nextInternal(String current) {
        if (current == null || current.isEmpty()) {
            return "A";
        }
        if (!current.matches("[A-Z]+")) {
            throw new IllegalArgumentException("내부 리비전은 대문자 알파벳: " + current);
        }
        char[] c = current.toCharArray();
        int i = c.length - 1;
        while (i >= 0 && c[i] == 'Z') {
            c[i] = 'A';
            i--;
        }
        if (i < 0) {
            return "A" + new String(c);
        }
        c[i]++;
        return new String(c);
    }

    /** 발행 리비전: 없음 → 0, n → n+1 */
    public static String nextIssued(Optional<String> lastIssued) {
        return lastIssued.map(s -> String.valueOf(Integer.parseInt(s) + 1)).orElse("0");
    }

    /** 내부 리비전 정렬 키(길이 우선, 같은 길이는 사전순) — A < Z < AA */
    public static int compareInternal(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
}
