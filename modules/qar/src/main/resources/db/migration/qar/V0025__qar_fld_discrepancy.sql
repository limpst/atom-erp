-- =====================================================================
-- V0025 — QAR 의 fld.ai-suggestion.discrepancy 구독 읽기 모델 (설계 05 v0.2 B6.2 · A8-8)
--   AI 균열폭과 사람 측정값이 2배 이상 다른 결함 = QAR 샘플링 검토 · 강조 대상.
-- =====================================================================
CREATE TABLE qar.fld_ai_discrepancy (
  suggestion_id  uuid PRIMARY KEY,                    -- fld.ai_suggestion (모듈 간 FK 미설정)
  defect_id      uuid NOT NULL,
  round_id       uuid NOT NULL,
  project_id     uuid NOT NULL REFERENCES core.project(id),
  defect_code    varchar(20),
  human_width_mm numeric(8,3),
  ai_width_mm    numeric(8,3),
  ratio          numeric(8,3),
  received_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_qar_fld_discrepancy_project ON qar.fld_ai_discrepancy (project_id);
