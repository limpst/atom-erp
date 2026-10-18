package com.atomeng.erp.sal.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 나라장터(조달청 입찰공고정보서비스) 공고 수집 포트 (FR-SAL-001, 01-IMPL Step 10).
 * ⚠️ 오퍼레이션명 · 파라미터 · 응답 필드는 공공데이터포털 활용신청 후 명세서로 확정 — 어댑터가 원문(raw)을 함께 넘겨 재파싱 가능하게 한다.
 */
public interface BidNoticeSourcePort {

    /** 정규화된 공고 1건 + 원문 */
    record NoticeItem(String bidNo, String bidSeq, String title, String orgName, String demandOrgName,
                      String contractMethod, String awardMethod, String noticeKind, BigDecimal estPrice,
                      BigDecimal basePrice, String regionLimit, OffsetDateTime noticeAt, OffsetDateTime openAt,
                      OffsetDateTime closeAt, String noticeUrl, JsonNode raw) {

        public boolean isCancellation() {
            return noticeKind != null && noticeKind.contains("취소");
        }
    }

    record Page(List<NoticeItem> items, int pageNo, int numOfRows, int totalCount) {
        public boolean hasNext() {
            return (long) pageNo * numOfRows < totalCount && !items.isEmpty();
        }
    }

    /** 외부 장애 · 미설정 — 수집 실패로 기록(collect_log.success=false)하고 다음 실행이 같은 구간을 다시 포함한다 */
    class SourceUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public SourceUnavailable(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** @param from · to 조회 구간(어댑터가 KST yyyyMMddHHmm 로 변환) */
    Page fetchNotices(OffsetDateTime from, OffsetDateTime to, int pageNo);
}
