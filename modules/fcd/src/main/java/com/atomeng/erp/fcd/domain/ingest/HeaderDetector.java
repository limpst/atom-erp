package com.atomeng.erp.fcd.domain.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 헤더 행 자동 탐지 · 매핑 프로파일 자동 선택 (설계 B7.1 Step 5–6, Python {@code atomeng_common.ingest.header} 이식).
 * 상위 30행을 동의어 사전으로 점수화, 2단 병합 헤더는 위 칸 상속 후 '상위_하위' 결합, 프로파일은 헤더 시그니처
 * Jaccard ≥ 0.8 이면 자동 선택(아니면 매핑 화면).
 */
public final class HeaderDetector {

    public static final class HeaderNotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public HeaderNotFound(String m) {
            super(m);
        }
    }

    public record HeaderMatch(int headerRow, int nRows, List<String> headers, Map<String, Integer> fieldToCol, int hits) {
        public int dataStart() {
            return headerRow + nRows;
        }

        public Set<String> signature() {
            Set<String> s = new TreeSet<>();
            headers.forEach(h -> {
                String n = normHeader(h);
                if (!n.isEmpty()) {
                    s.add(n);
                }
            });
            return s;
        }
    }

    /** 카드사 × 파일형식 × 양식버전 매핑 프로파일 (fcd.import_profile) */
    public record MappingProfile(String profileCode, String cardCompany, String fileType, String kind, String name,
                                 List<String> headerSignature, Map<String, String> columnMap, String dateFormat,
                                 String cancelMode, List<String> cancelValues, List<String> partialCancelValues,
                                 String footerStopPattern, boolean active) {
        public Map<String, Integer> columnIndex(HeaderMatch hm) {
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < hm.headers().size(); i++) {
                idx.putIfAbsent(normHeader(hm.headers().get(i)), i);
            }
            // Python dict comprehension: 같은 정규화 헤더가 여러 번이면 마지막 열
            for (int i = 0; i < hm.headers().size(); i++) {
                idx.put(normHeader(hm.headers().get(i)), i);
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            columnMap.forEach((f, h) -> {
                Integer i = idx.get(normHeader(h));
                if (i != null) {
                    out.put(f, i);
                }
            });
            return out;
        }
    }

    /** 헤더 동의어 사전(프로토타입 profiles.py) — 카드사 양식에서 추출한 헤더만 */
    public static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();
    public static final List<String> REQUIRED = List.of("approved_date", "amount", "merchant_name");

    static {
        SYNONYMS.put("card_ref", List.of("카드번호", "이용카드", "카드no", "카드 번호"));
        SYNONYMS.put("card_alias", List.of("카드별칭", "별칭"));
        SYNONYMS.put("approved_date", List.of("승인일자", "이용일자", "거래일자", "이용일", "승인일", "거래일시", "이용일시", "승인일시"));
        SYNONYMS.put("approved_time", List.of("승인시간", "이용시간", "이용시각", "승인시각"));
        SYNONYMS.put("approval_no", List.of("승인번호", "승인no"));
        SYNONYMS.put("amount", List.of("이용금액", "승인금액", "매출금액", "청구금액", "이용금액_원화", "금액"));
        SYNONYMS.put("merchant_name", List.of("가맹점명", "이용가맹점", "가맹점"));
        SYNONYMS.put("biz_reg_no", List.of("사업자번호", "가맹점사업자번호", "사업자등록번호"));
        SYNONYMS.put("category", List.of("업종", "업종명", "mcc"));
        SYNONYMS.put("status", List.of("승인구분", "구분", "상태", "취소여부"));
        SYNONYMS.put("currency", List.of("통화", "결제통화"));
        SYNONYMS.put("amount_foreign", List.of("현지금액", "이용금액_현지", "외화금액"));
        SYNONYMS.put("amount_krw_fx", List.of("원화환산금액"));
        SYNONYMS.put("fx_rate", List.of("적용환율", "환율"));
        SYNONYMS.put("purchased_on", List.of("매입일", "매입일자"));
        SYNONYMS.put("note", List.of("비고", "메모"));
    }

    private static final Pattern PAREN = Pattern.compile("\\(.*?\\)|\\[.*?\\]");
    private static final Pattern SEP = Pattern.compile("[\\s._/\\-:·]");

    /** "이용 금액(원)" → "이용금액" */
    public static String normHeader(Object s) {
        if (s == null) {
            return "";
        }
        String x = PAREN.matcher(String.valueOf(s)).replaceAll("");
        x = SEP.matcher(x).replaceAll("");
        return x.toLowerCase(Locale.ROOT);
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        if (u.isEmpty()) {
            return 0.0;
        }
        Set<String> i = new HashSet<>(a);
        i.retainAll(b);
        return (double) i.size() / u.size();
    }

    private final Map<String, Set<String>> synonyms = new LinkedHashMap<>();

    public HeaderDetector() {
        this(List.of());
    }

    /**
     * 프로파일의 헤더 이름(column_map 값)을 동의어에 더한다 — 온보딩 도구로 만든 YAML 프로파일의 '처음 보는' 헤더
     * (예: "매출일자")도 헤더 행 탐지에 쓰이게 한다(Python {@code profiles.make_detector(profiles)} 와 동일).
     */
    public HeaderDetector(java.util.Collection<MappingProfile> profiles) {
        SYNONYMS.forEach((f, xs) -> {
            Set<String> s = new HashSet<>();
            xs.forEach(x -> s.add(normHeader(x)));
            synonyms.put(f, s);
        });
        for (MappingProfile p : profiles) {
            if (p == null) {
                continue;
            }
            p.columnMap().forEach((f, h) -> synonyms.computeIfAbsent(f, k -> new HashSet<>()).add(normHeader(h)));
        }
    }

    private Map<String, Integer> score(List<String> row) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int c = 0; c < row.size(); c++) {
            String n = normHeader(row.get(c));
            if (n.isEmpty()) {
                continue;
            }
            for (var e : synonyms.entrySet()) {
                if (e.getValue().contains(n) && !found.containsKey(e.getKey())) {
                    found.put(e.getKey(), c);
                }
            }
        }
        return found;
    }

    static List<String> cellStrings(List<Object> row) {
        List<String> out = new ArrayList<>(row.size());
        for (Object c : row) {
            out.add(c == null ? "" : String.valueOf(c));
        }
        return out;
    }

    /** 병합 셀: 위 칸이 비어 있고 아래 칸이 있으면 왼쪽 위 값을 상속 */
    static List<String> combine(List<Object> upper, List<Object> lower) {
        int n = Math.max(upper.size(), lower.size());
        List<String> up = new ArrayList<>();
        List<String> lo = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            up.add(i >= upper.size() || upper.get(i) == null ? "" : String.valueOf(upper.get(i)).strip());
            lo.add(i >= lower.size() || lower.get(i) == null ? "" : String.valueOf(lower.get(i)).strip());
        }
        List<String> filled = new ArrayList<>();
        String last = "";
        for (int i = 0; i < n; i++) {
            if (!up.get(i).isEmpty()) {
                last = up.get(i);
                filled.add(up.get(i));
            } else if (!lo.get(i).isEmpty() && !last.isEmpty()) {
                filled.add(last);
            } else {
                filled.add("");
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String u = filled.get(i);
            String l = lo.get(i);
            out.add(!u.isEmpty() && !l.isEmpty() ? u + "_" + l : (!u.isEmpty() ? u : l));
        }
        return out;
    }

    public HeaderMatch detect(List<List<Object>> topRows) {
        HeaderMatch best = null;
        List<List<Object>> rows = topRows.subList(0, Math.min(30, topRows.size()));
        for (int i = 0; i < rows.size(); i++) {
            List<List<String>> cands = new ArrayList<>();
            List<Integer> nrows = new ArrayList<>();
            cands.add(cellStrings(rows.get(i)));
            nrows.add(1);
            if (i + 1 < rows.size()) {
                cands.add(combine(rows.get(i), rows.get(i + 1)));
                nrows.add(2);
            }
            for (int k = 0; k < cands.size(); k++) {
                Map<String, Integer> found = score(cands.get(k));
                if (!REQUIRED.stream().allMatch(found::containsKey)) {
                    continue;
                }
                HeaderMatch hm = new HeaderMatch(i, nrows.get(k), cands.get(k), found, found.size());
                // 2단 결합은 단일 행보다 적중이 엄격히 많을 때만 채택
                if (best == null || hm.hits() > best.hits()
                        || (hm.hits() == best.hits() && hm.nRows() < best.nRows() && i == best.headerRow())) {
                    best = hm;
                }
            }
        }
        if (best == null) {
            throw new HeaderNotFound("필수 헤더(날짜·금액·가맹점)를 상위 30행에서 찾지 못함 → 매핑 화면");
        }
        return best;
    }

    public record ProfilePick(Optional<MappingProfile> profile, double score) {}

    public static ProfilePick pickProfile(HeaderMatch hm, List<MappingProfile> profiles, double threshold) {
        Set<String> sig = hm.signature();
        MappingProfile best = null;
        double bestS = 0.0;
        for (MappingProfile p : profiles) {
            if (!p.active()) {
                continue;
            }
            Set<String> ps = new HashSet<>();
            p.headerSignature().forEach(x -> ps.add(normHeader(x)));
            double s = jaccard(sig, ps);
            if (s > bestS) {
                best = p;
                bestS = s;
            }
        }
        return new ProfilePick(bestS >= threshold ? Optional.ofNullable(best) : Optional.empty(), bestS);
    }
}
