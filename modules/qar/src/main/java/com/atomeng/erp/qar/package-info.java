/**
 * QAR 품질·검토 (설계 07 v0.3 / 07-IMPL). RPT 가 만든 보고서 리비전을 자체검토 → 제출 → AI 검토 → QA 검토 → 책임기술자 승인의
 * 명시적 전이 테이블(B7.1)로 통제하고, AUTO-CHECK 규칙 15종(유효기간 규칙 세트) · 앵커 코멘트 · CitationGuard 로
 * 발행 전 부실 위험을 거른다. 승인 · 반려는 {@code qar.review.approved/rejected} 이벤트로 RPT 에 전달된다.
 * 다른 업무 모듈에는 {@code core::api} · {@code rpt::api}(RptRevisionQuery · 구독 계약) 공개 타입으로만 의존한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "QAR 품질·검토",
        allowedDependencies = {"core::api", "rpt::api"})
package com.atomeng.erp.qar;
