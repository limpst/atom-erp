package com.atomeng.erp.fld.domain;

import java.util.regex.Pattern;

/**
 * Hybrid Logical Clock (설계 B7.1) — 문자열 'wallMs(16자리):counter(4자리):node'. 0 채움 고정 폭이라 문자열 사전순 = 시간순이고,
 * 서버 · 앱 모두 단순 비교로 LWW 를 판정한다(05-IMPL Step 3 팁: received_at · seq 로 비교하지 않는다).
 */
public record Hlc(long wallMs, int counter, String node) implements Comparable<Hlc> {

    public static final Pattern FORMAT = Pattern.compile("^([0-9]{16}):([0-9]{4}):([A-Za-z0-9_-]{1,40})$");

    public Hlc {
        if (wallMs < 0 || counter < 0 || counter > 9999 || node == null || node.isBlank()) {
            throw new IllegalArgumentException("HLC 범위 오류");
        }
    }

    public static boolean valid(String s) {
        return s != null && FORMAT.matcher(s).matches();
    }

    public static Hlc parse(String s) {
        var m = FORMAT.matcher(s == null ? "" : s);
        if (!m.matches()) {
            throw new IllegalArgumentException("HLC 형식 오류(wallMs16:counter4:node): " + s);
        }
        return new Hlc(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));
    }

    public String pack() {
        return "%016d:%04d:%s".formatted(wallMs, counter, node);
    }

    /** 이 HLC 보다 확실히 늦은 HLC(서버가 충돌 해결 이벤트에 부여 — 해결 값이 LWW 에서 이기도록) */
    public Hlc successor(long nowMs, String newNode) {
        long w = Math.max(wallMs, nowMs);
        int c = w == wallMs ? counter + 1 : 0;
        if (c > 9999) {
            w += 1;
            c = 0;
        }
        return new Hlc(w, c, newNode);
    }

    public long distanceMillis(Hlc other) {
        return Math.abs(wallMs - other.wallMs);
    }

    @Override
    public int compareTo(Hlc o) {
        return pack().compareTo(o.pack());
    }

    /** 문자열 비교(둘 다 형식이 맞다는 전제, null 은 가장 이른 값) */
    public static int compare(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    @Override
    public String toString() {
        return pack();
    }
}
