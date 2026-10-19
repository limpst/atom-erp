package com.atomeng.erp.qar.adapter.out.fld;

import com.atomeng.erp.fld.api.FldSurveyQuery;
import com.atomeng.erp.qar.application.port.QarEvidencePort;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.Photo;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * QAR 원천 데이터 포트 구현 — FLD 사진(AC-PHO-001 · 002)은 fld::api {@link FldSurveyQuery} 로 연결(05 FLD 서버 측 구현 후).
 * 프로젝트 · 시설물의 최근 조사 회차(완료 우선)의 검증(VERIFIED) 사진만 쓴다. 회차가 없으면 미연동(null → 규칙 INFO).
 * HRQ 자격 · FAC 등급 이력 · 재료시험은 아직 원천 API 가 없어 미연동(null), 승인자 자격은 UNKNOWN(가드 통과 + 응답에 표시).
 */
@Component
public class FldEvidenceAdapter implements QarEvidencePort {

    private final FldSurveyQuery fld;

    public FldEvidenceAdapter(FldSurveyQuery fld) {
        this.fld = fld;
    }

    @Override
    public ExternalEvidence evidence(Query q) {
        List<Photo> photos = q.projectId() == null ? null : fld.latestRound(q.projectId(), q.facilityId())
                .map(r -> fld.verifiedPhotos(r.roundId()).stream()
                        .filter(p -> p.defectCode() != null)
                        .map(p -> new Photo(p.photoId().toString(), p.defectCode(), p.takenOn(), p.sha256()))
                        .toList())
                .orElse(null);
        return new ExternalEvidence(null, photos, null, null);
    }

    @Override
    public Qualification approverQualification(UUID approverId, UUID projectId, LocalDate on) {
        return Qualification.UNKNOWN;
    }
}
