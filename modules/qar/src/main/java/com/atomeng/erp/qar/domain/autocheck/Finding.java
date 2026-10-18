package com.atomeng.erp.qar.domain.autocheck;

import com.atomeng.erp.qar.domain.Severity;
import java.util.Map;

/**
 * 규칙 결과 1건. severity 가 null 이면 규칙 정의(default_severity)를 쓴다 — 문맥을 확정하지 못한 수치 · 원천 미연동은
 * INFO 로 명시(오탐 피로 방지, 07-IMPL Step 4 팁). reportAnchor = 보고서 위치, sourceRef = 원천 데이터 위치(딥링크).
 */
public record Finding(Severity severity, Map<String, Object> reportAnchor, Map<String, Object> sourceRef, String expected,
                      String actual, String message) {

    public static Finding of(Map<String, Object> anchor, Map<String, Object> source, String expected, String actual,
                             String message) {
        return new Finding(null, anchor, source, expected, actual, message);
    }

    public static Finding info(Map<String, Object> anchor, String message) {
        return new Finding(Severity.INFO, anchor, null, null, null, message);
    }

    /** 원천 데이터 모듈 미연동(HRQ · FLD 사진 · FAC 이력) — 수동 확인 안내 */
    public static Finding unconnected(String source) {
        return new Finding(Severity.INFO, null, Map.of("module", source), null, null,
                "원천 데이터(" + source + ") 미연동 — 수동 확인 필요");
    }

    public Severity effective(Severity ruleDefault) {
        return severity == null ? ruleDefault : severity;
    }
}
