package com.atomeng.erp.qar.domain.anchor;

import java.text.Normalizer;

/** 앵커 · 인용 비교용 정규화(07-IMPL Step 7 팁): NFC, 하이픈 줄바꿈 제거, 공백 압축 */
public final class TextNorm {

    private TextNorm() {}

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("-\\s*\\n\\s*", "");
        return n.replaceAll("\\s+", " ").strip();
    }

    /** 레벤슈타인 거리 */
    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
