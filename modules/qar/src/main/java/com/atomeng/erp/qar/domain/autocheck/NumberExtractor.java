package com.atomeng.erp.qar.domain.autocheck;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 수치 표현 추출기 (07-IMPL Step 4 NumberExtractor): "폭 0.3mm 수직균열이 3개소", "총 4건", "약 2.4m", "0.2~0.3mm".
 * 단위 정규화 ㎜→mm, ㎡·m2→m², 곳·EA·개·건→COUNT. 범위는 [v1, v2] 로 보존. 문맥(층 · 부재 · 결함 유형)은
 * 수치가 들어 있는 문장의 수치 앞쪽에서 가장 가까운 표현으로 정한다.
 */
public final class NumberExtractor {

    public enum Unit { COUNT, MM, M, M2, PERCENT }

    public record Mention(int start, int end, String raw, boolean approx, BigDecimal v1, BigDecimal v2, Unit unit,
                          String floor, String member, String defectType, boolean total) {
        public boolean matches(BigDecimal expected, BigDecimal tol) {
            if (expected == null) {
                return false;
            }
            BigDecimal t = tol == null ? BigDecimal.ZERO : tol;
            if (v2 != null) {
                return expected.compareTo(v1.subtract(t)) >= 0 && expected.compareTo(v2.add(t)) <= 0;
            }
            BigDecimal allowed = approx ? t.max(BigDecimal.ONE.movePointLeft(Math.max(v1.scale(), 0))) : t;
            return expected.subtract(v1).abs().compareTo(allowed) <= 0;
        }

        public boolean hasLocation() {
            return floor != null && member != null && defectType != null;
        }
    }

    private static final Pattern NUM = Pattern.compile(
            "(?<approx>약\\s*)?(?<v1>\\d+(?:\\.\\d+)?)(?:\\s*[–\\-~]\\s*(?<v2>\\d+(?:\\.\\d+)?))?\\s*"
            + "(?<unit>mm|㎜|m²|㎡|m2|m(?![a-zA-Z])|개소|곳|EA|개|건|%)");
    private static final Pattern FLOOR = Pattern.compile("(?<b>지하\\s*(?<bn>\\d+)\\s*층|B(?<bb>\\d+)F)|(?<f>(?<fn>\\d+)\\s*(?:층|F))");
    private static final Map<String, String> MEMBERS = Map.of("기둥", "COLUMN", "보", "BEAM", "슬래브", "SLAB",
            "벽체", "WALL", "벽", "WALL", "계단", "STAIR");
    private static final Map<String, String> DEFECTS = Map.of("균열", "CRACK", "박리", "SPALL", "박락", "SPALL",
            "철근노출", "REBAR_EXPOSURE", "철근 노출", "REBAR_EXPOSURE", "백태", "EFFLORESCENCE", "누수", "LEAK");

    private NumberExtractor() {}

    public static List<Mention> extract(String text) {
        List<Mention> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            // 층 표기(1F · 2층) · 영문 식별자(D-0001, v1) 안의 숫자는 제외
            int s = m.start("v1");
            if (s > 0 && (Character.isLetter(text.charAt(s - 1)) && text.charAt(s - 1) < 128 || text.charAt(s - 1) == '-')) {
                continue;
            }
            Unit unit = switch (m.group("unit")) {
                case "mm", "㎜" -> Unit.MM;
                case "m²", "㎡", "m2" -> Unit.M2;
                case "m" -> Unit.M;
                case "%" -> Unit.PERCENT;
                default -> Unit.COUNT;
            };
            int sentenceStart = sentenceStart(text, m.start());
            String before = text.substring(sentenceStart, m.start());
            String after = text.substring(m.end(), sentenceEnd(text, m.end()));
            String floorBefore = lastFloor(before);
            String memberBefore = nearest(before, MEMBERS, "");
            String floor = floorBefore != null ? floorBefore : firstFloor(after);
            String member = nearest(before, MEMBERS, after);
            String defect = nearest(before, DEFECTS, after);
            // 총수 표현: "총 N건" · "결함 N건"(수치 앞에 층 · 부재 한정이 없을 때)
            boolean total = before.contains("총") || before.contains("전체") || before.contains("합계")
                    || (unit == Unit.COUNT && before.contains("결함") && floorBefore == null && memberBefore == null);
            out.add(new Mention(m.start(), m.end(), m.group().trim(), m.group("approx") != null,
                    new BigDecimal(m.group("v1")), m.group("v2") == null ? null : new BigDecimal(m.group("v2")), unit,
                    floor, member, defect, total));
        }
        return out;
    }

    private static int sentenceStart(String t, int pos) {
        for (int i = pos - 1; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == '\n' || (c == '.' && (i + 1 >= t.length() || !Character.isDigit(t.charAt(i + 1))))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int sentenceEnd(String t, int pos) {
        for (int i = pos; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || (c == '.' && (i + 1 >= t.length() || !Character.isDigit(t.charAt(i + 1))))) {
                return i;
            }
        }
        return t.length();
    }

    static String firstFloor(String s) {
        Matcher m = FLOOR.matcher(s);
        if (!m.find()) {
            return null;
        }
        return m.group("b") != null ? "B" + (m.group("bn") != null ? m.group("bn") : m.group("bb")) : m.group("fn") + "F";
    }

    static String lastFloor(String s) {
        Matcher m = FLOOR.matcher(s);
        String last = null;
        while (m.find()) {
            if (m.group("b") != null) {
                last = "B" + (m.group("bn") != null ? m.group("bn") : m.group("bb"));
            } else {
                last = m.group("fn") + "F";
            }
        }
        return last;
    }

    /** 수치 앞쪽에서 가장 가까운(마지막) 사전 단어, 없으면 뒤쪽 첫 단어 */
    private static String nearest(String before, Map<String, String> dict, String after) {
        int best = -1;
        String val = null;
        for (var e : dict.entrySet()) {
            int i = before.lastIndexOf(e.getKey());
            if (i >= 0 && (i > best || (i == best && val != null && e.getKey().length() > 1))) {
                if (e.getKey().equals("보") && isPartOfWord(before, i)) {
                    continue;
                }
                best = i;
                val = e.getValue();
            }
        }
        if (val != null) {
            return val;
        }
        int first = Integer.MAX_VALUE;
        for (var e : dict.entrySet()) {
            int i = after.indexOf(e.getKey());
            if (i >= 0 && i < first && !(e.getKey().equals("보") && isPartOfWord(after, i))) {
                first = i;
                val = e.getValue();
            }
        }
        return val;
    }

    /** "보수" · "보강" · "확보" 의 '보' 는 부재(보)가 아니다 */
    private static boolean isPartOfWord(String s, int i) {
        boolean prevHangul = i > 0 && Character.UnicodeBlock.of(s.charAt(i - 1)) == Character.UnicodeBlock.HANGUL_SYLLABLES;
        boolean nextHangul = i + 1 < s.length()
                && Character.UnicodeBlock.of(s.charAt(i + 1)) == Character.UnicodeBlock.HANGUL_SYLLABLES
                && "의에는가를와".indexOf(s.charAt(i + 1)) < 0;
        return prevHangul || nextHangul;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
