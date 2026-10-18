package com.atomeng.erp.fixture.violation.fld;

import com.atomeng.erp.fixture.violation.rpt.domain.Report;

/** 음성 테스트 픽스처: fld 가 rpt 내부 domain 타입을 직접 참조(금지) */
public class SurveyService {
    public String reportTitle(Report r) {
        return r.title();
    }
}
