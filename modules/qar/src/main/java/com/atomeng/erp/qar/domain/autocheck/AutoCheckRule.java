package com.atomeng.erp.qar.domain.autocheck;

import java.util.List;

/**
 * AUTO-CHECK 규칙 SPI (설계 B1 · 07-IMPL Step 4). 새 규칙 = 클래스 추가 + 규칙 세트(qar.autocheck_rule_def) 등록.
 * {@link ReviewContext} 는 라운드 대상 리비전의 읽기 전용 컨텍스트라 규칙 실행은 부작용이 없고 병렬 실행할 수 있다.
 */
public interface AutoCheckRule {

    /** "AC-NUM-001" */
    String id();

    /** @param params 규칙 세트 버전에 고정된 파라미터(허용오차 · 사전 등) */
    List<Finding> evaluate(ReviewContext ctx, RuleParams params);
}
