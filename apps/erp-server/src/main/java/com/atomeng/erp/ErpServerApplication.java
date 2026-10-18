package com.atomeng.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 에이톰 안전진단 ERP — 모듈형 모놀리스(설계 AP-02). 업무 모듈은 com.atomeng.erp.{mod} 하위 패키지,
 * 공통 서비스는 com.atomeng.common.* 라이브러리(자동 구성)로 조립된다.
 */
@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing(auditorAwareRef = "atomAuditorAware", dateTimeProviderRef = "atomDateTimeProvider")
public class ErpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpServerApplication.class, args);
    }
}
