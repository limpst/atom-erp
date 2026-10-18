package com.atomeng.erp.rpt.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * atom.rpt.* — facilityClass = 기본 시설물 분류(rule table 선택), pdf.gotenbergUrl 이 비어 있으면 스텁 PDF 변환기.
 */
@ConfigurationProperties(prefix = "atom.rpt")
public record RptProperties(String facilityClass, Pdf pdf) {

    public RptProperties {
        facilityClass = facilityClass == null || facilityClass.isBlank() ? "BLD_RC" : facilityClass;
        pdf = pdf == null ? new Pdf(null, null) : pdf;
    }

    public record Pdf(String gotenbergUrl, Duration timeout) {
        public Pdf {
            timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        }

        public boolean gotenbergEnabled() {
            return gotenbergUrl != null && !gotenbergUrl.isBlank();
        }
    }
}
