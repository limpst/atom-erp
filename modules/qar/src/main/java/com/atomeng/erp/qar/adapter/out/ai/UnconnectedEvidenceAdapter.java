package com.atomeng.erp.qar.adapter.out.ai;

import com.atomeng.erp.qar.application.port.QarEvidencePort;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 원천 모듈 공개 API 연결 전 기본 구현 — 모두 미연동(규칙 INFO), 승인자 자격 UNKNOWN(가드 통과 + 응답에 표시) */
@Component
public class UnconnectedEvidenceAdapter implements QarEvidencePort {

    @Override
    public ExternalEvidence evidence(Query q) {
        return ExternalEvidence.NONE;
    }

    @Override
    public Qualification approverQualification(UUID approverId, UUID projectId, LocalDate on) {
        return Qualification.UNKNOWN;
    }
}
