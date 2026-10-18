package com.atomeng.erp.sal.application;

import com.atomeng.erp.sal.domain.DiscountPolicy;
import com.atomeng.erp.sal.domain.fee.FeeEngine;
import com.atomeng.erp.sal.domain.qual.QualificationScorer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalProperties.class)
class SalConfig {

    @Bean
    DiscountPolicy salDiscountPolicy(SalProperties p) {
        return new DiscountPolicy(p.discountLimits());
    }

    @Bean
    FeeEngine salFeeEngine() {
        return new FeeEngine();
    }

    @Bean
    QualificationScorer salQualificationScorer() {
        return new QualificationScorer();
    }
}
