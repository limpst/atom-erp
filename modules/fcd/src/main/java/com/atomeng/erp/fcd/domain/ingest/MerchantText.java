package com.atomeng.erp.fcd.domain.ingest;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 가맹점 정규화 · 사업자번호 체크섬 · Jaro-Winkler · PG 레지스트리 (Python {@code atomeng_common.ingest.merchant} 이식).
 */
public final class MerchantText {

    private MerchantText() {}

    private static final Pattern CORP = Pattern.compile("\\(주\\)|㈜|주식회사|유한책임회사|유한회사|\\(유\\)|\\(재\\)|\\(사\\)|\\(예시\\)");
    private static final Pattern BRANCH = Pattern.compile("(본점|지점|영업소|센터|\\d+호점)$");
    private static final Pattern PUNCT = Pattern.compile("[()\\[\\]{}\\-_.,·/&'\"]");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** NFKC → 소문자 → 법인격 제거 → (선택) 지점 제거 → 기호 · 공백 제거 */
    public static String normalize(String name, boolean dropBranch) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = CORP.matcher(s).replaceAll("");
        s = PUNCT.matcher(s).replaceAll(" ");
        s = SPACE.matcher(s).replaceAll(" ").strip();
        if (dropBranch) {
            List<String> toks = new java.util.ArrayList<>(List.of(s.split(" ", -1)));
            if (toks.size() > 1 && toks.get(toks.size() - 1).endsWith("점")) {
                toks.remove(toks.size() - 1);
            }
            s = String.join(" ", toks);
            s = BRANCH.matcher(s).replaceAll("");
        }
        return SPACE.matcher(s).replaceAll("");
    }

    /** 사업자등록번호 10자리 체크섬 (v3.1 F2.1) */
    public static boolean validBizNo(String s) {
        if (s == null) {
            return false;
        }
        int[] d = s.chars().filter(Character::isDigit).map(c -> c - '0').toArray();
        if (d.length != 10) {
            return false;
        }
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += d[i] * w[i];
        }
        total += (d[8] * 5) / 10;
        return d[9] == (10 - total % 10) % 10;
    }

    /** Jaro-Winkler (p=0.1, 접두 4) — Python 구현과 같은 정의(코드포인트 단위) */
    public static double jaroWinkler(String a0, String b0) {
        int[] a = a0.codePoints().toArray();
        int[] b = b0.codePoints().toArray();
        if (java.util.Arrays.equals(a, b)) {
            return a.length > 0 ? 1.0 : 0.0;
        }
        int la = a.length;
        int lb = b.length;
        if (la == 0 || lb == 0) {
            return 0.0;
        }
        int rng = Math.max(la, lb) / 2 - 1;
        boolean[] ma = new boolean[la];
        boolean[] mb = new boolean[lb];
        int m = 0;
        for (int i = 0; i < la; i++) {
            int lo = Math.max(0, i - rng);
            int hi = Math.min(lb, i + rng + 1);
            for (int j = lo; j < hi; j++) {
                if (!mb[j] && b[j] == a[i]) {
                    ma[i] = true;
                    mb[j] = true;
                    m++;
                    break;
                }
            }
        }
        if (m == 0) {
            return 0.0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < la; i++) {
            if (ma[i]) {
                while (!mb[k]) {
                    k++;
                }
                if (a[i] != b[k]) {
                    t++;
                }
                k++;
            }
        }
        double jaro = ((double) m / la + (double) m / lb + (m - t / 2.0) / m) / 3;
        int pre = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)); i++) {
            if (a[i] != b[i]) {
                break;
            }
            pre++;
        }
        return jaro + pre * 0.1 * (1 - jaro);
    }

    /** PG(결제대행) 레지스트리 — 부록 A 결함 1(R-137). 사업자번호는 가상(합성) 번호 */
    public static final Set<String> DEFAULT_PG_BIZNOS = Set.of("999-86-00011");
    private static final List<String> PG_PATTERNS = List.of("결제대행", "pg", "페이먼츠", "kcp", "이니시스", "다날");

    public static boolean isPg(String bizNo, String name) {
        if (bizNo != null && DEFAULT_PG_BIZNOS.contains(bizNo)) {
            return true;
        }
        String n = normalize(name, false);
        return PG_PATTERNS.stream().anyMatch(n::contains);
    }
}
