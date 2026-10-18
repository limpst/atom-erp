package com.atomeng.common.web.problem;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** 서블릿 필터·보안 진입점처럼 MVC 밖에서 problem+json 을 쓰는 도우미. */
public class ProblemWriter {

    private final ProblemFactory factory;
    private final ObjectMapper objectMapper;

    public ProblemWriter(ProblemFactory factory, ObjectMapper objectMapper) {
        this.factory = factory;
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest req, HttpServletResponse res, String code, String detail) throws IOException {
        ProblemDetail pd = factory.create(code, detail, req.getRequestURI(), req.getLocale());
        res.setStatus(pd.getStatus());
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(), pd);
    }
}
