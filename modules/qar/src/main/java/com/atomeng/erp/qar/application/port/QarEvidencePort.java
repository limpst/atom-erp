package com.atomeng.erp.qar.application.port;

import com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * QAR 가 다른 모듈(HRQ 자격 · FLD 사진/재료시험 · FAC 등급 이력)에서 받아야 하는 원천 데이터 포트.
 * QAR 은 core::api · rpt::api 에만 의존하므로(모듈 경계) 해당 모듈 공개 API 가 생기기 전까지 기본 구현은
 * {@code UnconnectedEvidenceAdapter}(모두 미연동 → 규칙은 INFO "수동 확인", 승인 자격은 UNKNOWN).
 */
public interface QarEvidencePort {

    record Query(UUID projectId, UUID revisionId, UUID facilityId, List<UUID> participants, LocalDate asOf) {}

    ExternalEvidence evidence(Query q);

    enum Qualification { VALID, INVALID, UNKNOWN }

    /** 최종 승인자(책임기술자) 자격 as-of 재검증 (FR-QAR-013, TC-QAR-011) */
    Qualification approverQualification(UUID approverId, UUID projectId, LocalDate on);
}
