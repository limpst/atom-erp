package com.atomeng.erp.rpt.application;

import com.atomeng.erp.rpt.adapter.out.pdf.GotenbergPdfConverter;
import com.atomeng.erp.rpt.adapter.out.pdf.StubPdfConverter;
import com.atomeng.erp.rpt.application.port.PdfConverterPort;
import com.atomeng.erp.rpt.domain.grade.GradeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RptProperties.class)
class RptConfig {

    private static final Logger log = LoggerFactory.getLogger(RptConfig.class);

    @Bean
    GradeEngine rptGradeEngine() {
        return new GradeEngine();
    }

    /** atom.rpt.pdf.gotenberg-url 이 있으면 Gotenberg, 없으면 스텁(합성 PDF — 경고 로그) */
    @Bean
    @ConditionalOnMissingBean(PdfConverterPort.class)
    PdfConverterPort rptPdfConverter(RptProperties p) {
        if (p.pdf().gotenbergEnabled()) {
            return new GotenbergPdfConverter(p.pdf().gotenbergUrl(), p.pdf().timeout());
        }
        log.warn("atom.rpt.pdf.gotenberg-url 미설정 — 스텁 PDF 변환기 사용(운영 금지)");
        return new StubPdfConverter();
    }
}
