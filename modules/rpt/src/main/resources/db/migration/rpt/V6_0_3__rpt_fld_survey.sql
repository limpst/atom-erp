-- =====================================================================
-- V6_0_3 — RPT 의 fld.survey.completed 구독 읽기 모델 (설계 05 v0.2 B6.2 · 06-IMPL Step 3)
--   조사 회차 완료(모든 구역 잠금) = 보고서 작성 가능 신호. 스냅샷 동결 시 fld::api 원천(fldRoundId)의 워터마크와 대조한다.
-- =====================================================================
CREATE TABLE rpt.field_survey_completion (
  round_id     uuid PRIMARY KEY,                     -- fld.survey_round (모듈 간 FK 미설정)
  project_id   uuid NOT NULL REFERENCES core.project(id),
  building_id  uuid,
  round_no     int NOT NULL,
  survey_type  varchar(20),
  zone_count   int NOT NULL,
  defect_count int NOT NULL,
  last_seq     bigint NOT NULL,
  completed_on date NOT NULL,
  received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_rpt_fld_survey_project ON rpt.field_survey_completion (project_id);
