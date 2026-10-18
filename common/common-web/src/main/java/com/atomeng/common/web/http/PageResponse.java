package com.atomeng.common.web.http;

import java.util.List;
import org.springframework.data.domain.Page;

/** 목록 공통 응답 (설계 9.1 페이징). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
