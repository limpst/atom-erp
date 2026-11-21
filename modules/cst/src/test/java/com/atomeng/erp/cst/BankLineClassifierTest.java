package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.cst.domain.billing.BankLineClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시제품 classify_rules.py 와 같은 판정이어야 하는 대표 사례 — 합성 거래내용 */
class BankLineClassifierTest {

    @Test
    @DisplayName("대체(자기 계좌 간 이동 · 사무실경비 현금 인출)만 자동 확정")
    void transferAutoConfirm() {
        assertThat(BankLineClassifier.classify("OUT", "대체 운영→급여계좌")).get().extracting("acct", "autoConfirm").containsExactly("대체", true);
        assertThat(BankLineClassifier.classify("OUT", "사무실경비")).get().extracting("acct", "autoConfirm").containsExactly("대체", true);
        assertThat(BankLineClassifier.classify("IN", "현금지급(사무실경비) 9월")).get().extracting("acct", "autoConfirm").containsExactly("대체", true);
        assertThat(BankLineClassifier.classify("OUT", "9월 급여")).get().extracting("acct", "autoConfirm").containsExactly("급여", false);
    }

    @Test
    @DisplayName("첫 일치 규칙이 이긴다 — 순서는 시제품 RULES 그대로")
    void firstRuleWins() {
        assertThat(BankLineClassifier.classify("OUT", "국민연금 10월")).get().extracting("acct").isEqualTo("4대보험");
        assertThat(BankLineClassifier.classify("OUT", "부가세 예정신고")).get().extracting("acct").isEqualTo("세금과공과");
        assertThat(BankLineClassifier.classify("OUT", "신한카드 결제")).get().extracting("acct").isEqualTo("법인카드결재");
        assertThat(BankLineClassifier.classify("OUT", "KT 인터넷 요금")).get().extracting("acct").isEqualTo("통신비");
        assertThat(BankLineClassifier.classify("OUT", "자동차보험 갱신")).get().extracting("acct").isEqualTo("보험료");   // 보험료(보험) 가 차량유지비(차량) 보다 앞
        assertThat(BankLineClassifier.classify("OUT", "측량 용역비")).get().extracting("acct").isEqualTo("외주비");
        assertThat(BankLineClassifier.classify("OUT", "이체수수료")).get().extracting("acct").isEqualTo("지급수수료");   // '이체$' 는 끝에 올 때만 대체
        assertThat(BankLineClassifier.classify("OUT", "합성 물품")).isEmpty();
    }

    @Test
    @DisplayName("입금 줄: 발주처 입금은 '매출입금' 기본, 이자 · 대체 · 펀드는 그 규칙")
    void inflowDefaults() {
        assertThat(BankLineClassifier.classify("IN", "합성건설 기성")).get().extracting("acct", "autoConfirm").containsExactly("매출입금", false);
        assertThat(BankLineClassifier.classify("IN", "예금이자")).get().extracting("acct").isEqualTo("지급이자");
        assertThat(BankLineClassifier.classify("IN", "적금 만기")).get().extracting("acct").isEqualTo("펀드");
        assertThat(BankLineClassifier.classify("IN", "")).isEmpty();
        assertThat(BankLineClassifier.ACCOUNTS).contains("대체", "급여", "매출입금", "기타").doesNotHaveDuplicates();
    }
}
