/**
 * AIP AI 플랫폼 (설계 11 v0.3 / 11-IMPL Step 1 · 2 · 4 · 6 · 8 · 10 · 12 일부). 추론 Job 큐(상태 · 우선순위 · 마감 · 재시도 · lease),
 * 모델 · 프롬프트 레지스트리(shadow/canary/production 별칭 · 승격 전이), 추론 감사(월 파티션 PK (job_id, created_at)),
 * AI 제안 · 피드백(BR-AIP-001 · 012, 동의 · 블러 플래그), 데이터셋 커밋 기록, 균열 측정 결과 모델(순수 도메인).
 * 실제 추론은 Python 워커(services/aip-worker)가 하고, 이 모듈은 HTTP(기본 비활성)로 호출한다.
 * 다른 업무 모듈에는 {@code core::api} 공개 타입으로만 의존하며, RPT · QAR 은 {@code aip::api} 만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "AIP AI 플랫폼", allowedDependencies = {"core::api"})
package com.atomeng.erp.aip;
