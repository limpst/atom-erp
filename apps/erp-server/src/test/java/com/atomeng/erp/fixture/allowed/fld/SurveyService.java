package com.atomeng.erp.fixture.allowed.fld;

import com.atomeng.erp.fixture.allowed.rpt.api.ReportApi;

/** 양성 픽스처: fld 는 rpt::api 만 사용 */
public class SurveyService {
    private final ReportApi reports;

    public SurveyService(ReportApi reports) {
        this.reports = reports;
    }

    public String title(String no) {
        return reports.titleOf(no);
    }
}
