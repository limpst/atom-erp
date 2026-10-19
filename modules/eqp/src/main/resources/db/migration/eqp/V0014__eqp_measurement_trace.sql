-- =====================================================================
-- V0014 — eqp.measurement_trace (04-IMPL Step 9 측정 추적성, 05 FLD BR-FLD-008 · FR-FLD-010)
--   * 측정값 확정(FLD 동기화 서버 재검증 통과) 1건 = 추적 1행. INSERT ONLY(append-only 트리거).
--   * 판정 근거(교정 · Override · 교정 비대상)를 측정 시점 스냅샷으로 남긴다 — 이후 교정이 바뀌어도 당시 근거 재현.
--   * 멱등: (source_module, source_id) UNIQUE — 같은 측정 레코드 재전송은 기존 행.
-- =====================================================================
CREATE TABLE eqp.measurement_trace (
  id             uuid PRIMARY KEY,
  equipment_id   uuid NOT NULL REFERENCES eqp.equipment(id),
  project_id     uuid REFERENCES core.project(id),
  measured_on    date NOT NULL,
  source_module  varchar(4) NOT NULL,              -- FLD …
  source_type    varchar(30) NOT NULL,             -- MEASUREMENT
  source_id      uuid NOT NULL,                    -- fld.measurement.id
  item           varchar(30),
  value          numeric(12,4),
  unit           varchar(10),
  basis          varchar(15) NOT NULL CHECK (basis IN ('CALIBRATION','OVERRIDE','NOT_REQUIRED')),
  calibration_id uuid,
  override_id    uuid,
  cert_no        varchar(60),
  valid_until    date,
  recorded_at    timestamptz NOT NULL DEFAULT now(),
  recorded_by    uuid NOT NULL,
  UNIQUE (source_module, source_id)
);
CREATE INDEX ix_trace_equipment ON eqp.measurement_trace (equipment_id, measured_on);

CREATE TRIGGER trg_trace_append_only BEFORE UPDATE OR DELETE ON eqp.measurement_trace
  FOR EACH ROW EXECUTE FUNCTION eqp.forbid_mutation();
CREATE TRIGGER trg_trace_no_truncate BEFORE TRUNCATE ON eqp.measurement_trace
  FOR EACH STATEMENT EXECUTE FUNCTION eqp.forbid_mutation();
