package com.atomeng.erp.cst.domain.billing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 통장 줄 계정 제안(설계 §9-2) — 거래내용 키워드 → 계정과목. 시제품 {@code tools/classify_rules.py} RULES 이식(순서 그대로, 첫 일치가 이긴다).
 * <ul>
 *   <li>'대체'(자기 계좌 간 이동 · 현금 인출→현금시제)만 autoConfirm. 나머지는 제안이고 사람이 확정한다.</li>
 *   <li>입금(IN) 줄은 '매출입금' 을 기본 제안으로 둔다(발주처 입금은 bank_receipt 에서 청구와 짝을 맞춘다). 입금인데 '대체 · 이자 · 대여 · 펀드' 는 그 규칙을 따른다.</li>
 *   <li>직원 이름 매칭(시제품의 staff_names.txt)은 개인정보라 서버에 넣지 않는다.</li>
 * </ul>
 */
public final class BankLineClassifier {
    private BankLineClassifier() {}

    public record Suggestion(String acct, String ruleCode, boolean autoConfirm) {}

    private record Rule(String acct, Pattern pattern, boolean auto) {
        Rule(String acct, String regex) {
            this(acct, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), false);
        }
    }

    private static final Pattern OFFICE_CASH = Pattern.compile("^\\s*사무실경비\\s*$|^현금지급\\(사무실경비");

    static final List<Rule> RULES = List.of(
            new Rule("대체", Pattern.compile("대체|이체$|계좌이체"), true),
            new Rule("급여", "급여|월급|상여|연봉|퇴직"),
            new Rule("4대보험", "건강보험|국민연금|연금보험|고용보험|산재|근로복지"),
            new Rule("세금과공과", "부가세|법인세|지방세|원천세|소득세|주민세|세금|국세|세무서|취득세|재산세|자동차세|면허세"),
            new Rule("지급이자", "이자"),
            new Rule("대출금상환", "대출.*상환|원금|상환"),
            new Rule("법인카드결재", "카드|KB국민카드|신한카드|삼성카드|현대카드|BC카드|롯데카드|하나카드|우리카드"),
            new Rule("임차료", "임대료|임차|관리비|월세"),
            new Rule("통신비", "통신|KT|SKT|LG유플|LGU|유플러스|인터넷|휴대폰|전화"),
            new Rule("보험료", "보험|화재|자동차보험|손해"),
            new Rule("차량유지비", "주유|하이패스|톨게이트|주차|정비|타이어|차량|유류"),
            new Rule("외주비", "외주|용역|설계비|도면|감리|측량|시험|검사비|탐사"),
            new Rule("지급수수료", "수수료|송금|이체수수료|인지|공증|등기|법무|회계|세무사|노무"),
            new Rule("복리후생비", "식대|식당|점심|저녁|회식|커피|간식|경조|축의|조의"),
            new Rule("여비교통비", "교통|택시|KTX|기차|항공|숙박|호텔|출장"),
            new Rule("도서인쇄비", "인쇄|출력|도서|복사|제본|명함"),
            new Rule("사무실경비", "사무실경비|경비\\s*현금|소액경비"),
            new Rule("사무용품", "문구|용품|프린터|토너"),
            new Rule("협회비", "협회|학회|회비|조합"),
            new Rule("가지급금", "가지급"),
            new Rule("단기대여금", "대여"),
            new Rule("접대비", "접대|선물|화환"),
            new Rule("광고선전비", "광고|홍보|마케팅"),
            new Rule("기부금", "기부|후원"),
            new Rule("펀드", "펀드|적금|예금|증권"));

    /** 확정에 쓸 수 있는 계정명(규칙 계정 + 입금용 + 미분류 수동 계정) */
    public static final List<String> ACCOUNTS;
    static {
        var l = new java.util.ArrayList<String>(RULES.stream().map(Rule::acct).toList());
        for (String extra : List.of("매출입금", "선수금", "잡이익", "잡손실", "기타")) {
            if (!l.contains(extra)) {
                l.add(extra);
            }
        }
        ACCOUNTS = List.copyOf(l);
    }

    /** direction = IN | OUT, memo = 거래내용(비어 있으면 제안 없음) */
    public static Optional<Suggestion> classify(String direction, String memo) {
        if (memo == null || memo.isBlank()) {
            return Optional.empty();
        }
        String m = memo.trim();
        if (OFFICE_CASH.matcher(m).find()) {
            return Optional.of(new Suggestion("대체", "현금인출→현금시제(대체)", true));
        }
        boolean in = "IN".equalsIgnoreCase(direction);
        for (Rule r : RULES) {
            if (in && !List.of("대체", "지급이자", "단기대여금", "펀드", "가지급금").contains(r.acct())) {
                continue;
            }
            if (r.pattern().matcher(m).find()) {
                String code = r.pattern().pattern().split("\\|")[0];
                return Optional.of(new Suggestion(r.acct(), code.toLowerCase(Locale.ROOT).equals(code) ? code : code, r.auto()));
            }
        }
        return in ? Optional.of(new Suggestion("매출입금", "입금 기본", false)) : Optional.empty();
    }
}
