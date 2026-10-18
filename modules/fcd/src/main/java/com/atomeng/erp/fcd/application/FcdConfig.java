package com.atomeng.erp.fcd.application;

import com.atomeng.erp.fcd.adapter.out.aip.HttpFraudServiceAdapter;
import com.atomeng.erp.fcd.adapter.out.aip.StubFraudServiceAdapter;
import com.atomeng.erp.fcd.application.port.FraudServicePort;
import com.atomeng.erp.fcd.domain.features.CategoryCatalog;
import com.atomeng.erp.fcd.domain.features.HolidayCalendar;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FCD 빈 구성 — Python 서비스 주소가 있으면 HTTP 어댑터, 없으면 스텁 */
@Configuration
public class FcdConfig {

    @Bean
    @ConditionalOnMissingBean(FraudServicePort.class)
    FraudServicePort fraudServicePort(@Value("${atom.fcd.aip.base-url:}") String baseUrl,
                                      @Value("${atom.fcd.aip.timeout:800ms}") Duration timeout) {
        return baseUrl == null || baseUrl.isBlank() ? new StubFraudServiceAdapter()
                : new HttpFraudServiceAdapter(baseUrl, timeout);
    }

    @Bean
    HolidayCalendar fcdHolidayCalendar() {
        return HolidayCalendar.fromClasspath();
    }

    @Bean
    CategoryCatalog fcdCategoryCatalog() {
        return CategoryCatalog.fromClasspath();
    }
}
