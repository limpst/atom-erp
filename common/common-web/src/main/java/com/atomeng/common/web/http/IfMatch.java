package com.atomeng.common.web.http;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import java.util.regex.Pattern;

/**
 * 낙관적 동시성 (설계 9.1): 응답 ETag "v{version}", 수정 요청 If-Match 필수 → 불일치 시 412.
 */
public final class IfMatch {

    private static final Pattern ETAG = Pattern.compile("^(W/)?\"v(\\d+)\"$");

    private IfMatch() {}

    public static String etagOf(long version) {
        return "\"v" + version + "\"";
    }

    /** If-Match 헤더 → 기대 version. 없으면 428 CORE-428-002, 형식 오류면 400 CORE-400-001. */
    public static long require(String header) {
        if (header == null || header.isBlank()) {
            throw new DomainException(CoreErrors.IF_MATCH_REQUIRED, "If-Match 헤더가 필요합니다(예: \"v3\")");
        }
        var m = ETAG.matcher(header.trim());
        if (!m.matches()) {
            throw new DomainException(CoreErrors.MALFORMED_REQUEST, "If-Match 형식 오류: " + header);
        }
        return Long.parseLong(m.group(2));
    }

    /** 기대 version 과 현재 version 비교 → 412 CORE-412-001 */
    public static void check(long expected, long actual) {
        if (expected != actual) {
            throw new DomainException(CoreErrors.VERSION_MISMATCH,
                    "다른 사용자가 먼저 수정했습니다(요청 v%d, 현재 v%d)".formatted(expected, actual));
        }
    }
}
