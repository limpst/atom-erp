package com.atomeng.erp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.support.IntegrationTestBase;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 설계 9.1·9.2 RFC 7807 오류 모델 — 모든 오류가 problem+json + code */
class ProblemJsonIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("설계 9.2: 필수값 누락 → 422 problem+json CORE-422-001, errors[].field, 한국어 title")
    void validationError() throws Exception {
        mvc.perform(as(postJson("/api/v1/core/clients", "{\"clientType\":\"PRIVATE\",\"bizRegNo\":\"12-34\"}"),
                        actor(), "SALES").locale(Locale.KOREAN))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-422-001"))
                .andExpect(jsonPath("$.title").value("입력값 검증 실패"))
                .andExpect(jsonPath("$.instance").value("/api/v1/core/clients"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'bizRegNo')]").exists());
    }

    @Test
    @DisplayName("없는 고객 → 404 CORE-404-001, type URI 규칙")
    void notFound() throws Exception {
        mvc.perform(as(get("/api/v1/core/clients/" + UUID.randomUUID()), actor(), "SALES"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-404-001"))
                .andExpect(jsonPath("$.type").value("https://erp.atom-eng.co.kr/problems/core-404-001"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("프레임워크 오류(깨진 JSON 400)도 problem+json + code(CORE-400-000)")
    void malformedJson() throws Exception {
        mvc.perform(as(postJson("/api/v1/core/clients", "{\"name\":"), actor(), "SALES"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-400-000"));
    }

    @Test
    @DisplayName("영문 로캘이면 영문 title (설계 9.3 다국어 메시지)")
    void englishTitle() throws Exception {
        mvc.perform(as(get("/api/v1/core/clients/" + UUID.randomUUID()), actor(), "SALES").locale(Locale.ENGLISH))
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }
}
