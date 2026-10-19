-- =====================================================================
-- V0012 — eqp 스키마 (설계 04 v0.2 B3, 04-IMPL Step 1)
--   * 설계의 V4_001~V4_012 12개 대신 V0012~V0014 (전역 단조 번호, README 마이그레이션 규칙)
--   * 내장 PostgreSQL 16 에는 contrib(btree_gist)는 있고 PostGIS 는 없다 →
--       - 예약 기간 중복 금지·검교정 규칙 기간 겹침 금지: btree_gist EXCLUDE (설계 그대로, NFR-EQP-05 DB 제약)
--       - checkout 위치·비행 승인 구역·비행 경로: geometry 대신 위경도 numeric / jsonb 좌표 배열
--         (구역 이탈 판정은 Java 순수 도메인 Geo.pointInPolygon — PostGIS 프로필에서 ST_Within 으로 교체 예정)
--   * version 은 core 와 같은 bigint (설계 int)
--   * measurement_trace·depreciation·repair 는 후속 Step(9·10·12)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS eqp;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- ---------------------------------------------------------------- 유형·검교정 규칙
CREATE TABLE eqp.equipment_type (
  id                   uuid PRIMARY KEY,
  code                 varchar(20) NOT NULL UNIQUE,          -- NDT-RH, NDT-UPV, UAV ...
  name                 varchar(100) NOT NULL,
  calibration_required boolean NOT NULL DEFAULT true,
  measure_items        jsonb NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0
);

CREATE TABLE eqp.calibration_rule (
  id              uuid PRIMARY KEY,
  type_id         uuid NOT NULL REFERENCES eqp.equipment_type(id),
  interval_months int  NOT NULL CHECK (interval_months BETWEEN 1 AND 120),
  pre_use_check   jsonb,                                       -- {"anvil":{"target":80,"tol":2}}
  legal_basis     text NOT NULL,                               -- ⚠️ 법령 최신본 확인 필요
  effective_from  date NOT NULL,
  effective_to    date,
  approved_by     uuid,                                        -- NULL = 예시 값(QA 미승인)
  CONSTRAINT ck_rule_period CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT calibration_rule_no_overlap EXCLUDE USING gist (
    type_id WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&)
);

-- 장비코드 채번 카운터 (04-IMPL Step 3 함정: max()+1 금지 → INSERT … ON CONFLICT 카운터)
CREATE TABLE eqp.code_sequence (
  type_code varchar(20) PRIMARY KEY,
  last_seq  int NOT NULL
);

-- ---------------------------------------------------------------- 장비 대장
CREATE TABLE eqp.equipment (
  id                  uuid PRIMARY KEY,
  code                varchar(30) NOT NULL UNIQUE,             -- EQ-NDT-RH-0007
  type_id             uuid NOT NULL REFERENCES eqp.equipment_type(id),
  parent_id           uuid REFERENCES eqp.equipment(id),       -- 키트 구성(A8-4)
  model               varchar(100),
  manufacturer        varchar(100),
  serial_no           varchar(100) NOT NULL,
  ownership           varchar(10) NOT NULL DEFAULT 'OWNED' CHECK (ownership IN ('OWNED','RENTED','LEASED')),
  status              varchar(20) NOT NULL DEFAULT 'REGISTERED',
  spec                jsonb NOT NULL DEFAULT '{}',
  nfc_uid             varchar(40) UNIQUE,
  qr_secret           bytea NOT NULL,                          -- QR 서명키(HMAC-SHA256), 응답·로그 노출 금지
  qr_issued_at        timestamptz,
  home_location       varchar(100),
  acquired_on         date,
  acquisition_cost    numeric(15,0),
  useful_life_months  int,
  salvage_value       numeric(15,0) NOT NULL DEFAULT 0,
  depreciation_method varchar(10) NOT NULL DEFAULT 'SL' CHECK (depreciation_method IN ('SL','DB')),
  daily_rate          numeric(12,0),                           -- 참고 단가(CST 는 rate_table EQUIP_DAILY 로 계상)
  disposed_on         date,
  disposal_amount     numeric(15,0),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ux_equipment_type_serial UNIQUE (type_id, serial_no),      -- FR-EQP-001
  CONSTRAINT ck_equipment_status CHECK (status IN ('REGISTERED','AVAILABLE','RESERVED','CHECKED_OUT','CAL_DUE',
                    'IN_CALIBRATION','BROKEN','IN_REPAIR','LOST','DISPOSED')),
  CONSTRAINT ck_equipment_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
  CONSTRAINT ck_qr_secret_len CHECK (octet_length(qr_secret) >= 32)
);
CREATE INDEX ix_equipment_type_status ON eqp.equipment (type_id, status);
CREATE INDEX ix_equipment_parent ON eqp.equipment (parent_id) WHERE parent_id IS NOT NULL;

-- BR-EQP-008: 장비 행은 삭제하지 않는다(폐기 = status DISPOSED)
CREATE FUNCTION eqp.forbid_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'EQP-409-010 % 행은 삭제할 수 없습니다(BR-EQP-008)', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_equipment_no_delete BEFORE DELETE ON eqp.equipment
  FOR EACH ROW EXECUTE FUNCTION eqp.forbid_delete();
CREATE TRIGGER trg_equipment_no_truncate BEFORE TRUNCATE ON eqp.equipment
  FOR EACH STATEMENT EXECUTE FUNCTION eqp.forbid_delete();

-- 상태 전이 이력 (04-IMPL V4_010 설계 보강: 감사·가동률)
CREATE TABLE eqp.equipment_status_history (
  id            bigserial PRIMARY KEY,
  equipment_id  uuid NOT NULL REFERENCES eqp.equipment(id),
  from_status   varchar(20),
  to_status     varchar(20) NOT NULL,
  reason_code   varchar(40) NOT NULL,      -- REGISTER, CHECKOUT, RETURN, CAL_PASS, CAL_FAIL, CAL_EXPIRED, DISPOSE …
  ref_id        uuid,
  changed_by    uuid NOT NULL,
  changed_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_esh_eq_time ON eqp.equipment_status_history (equipment_id, changed_at);

-- ---------------------------------------------------------------- 검교정 (append-only)
CREATE TABLE eqp.calibration (
  id                   uuid PRIMARY KEY,
  equipment_id         uuid NOT NULL REFERENCES eqp.equipment(id),
  calibrated_on        date NOT NULL,
  next_due_on          date NOT NULL,                          -- = valid_until (이 날까지 유효)
  next_due_estimated   boolean NOT NULL DEFAULT false,         -- A8-3 차기일 누락 → 규칙 주기 추정
  result               varchar(10) NOT NULL CHECK (result IN ('PASS','FAIL','LIMITED')),
  cert_no              varchar(50),
  lab_name             varchar(100),
  lab_accreditation_no varchar(30),                            -- KOLAS 인정번호
  uncertainty          jsonb,
  cert_attachment_id   uuid REFERENCES core.attachment(id),
  cert_sha256          char(64) CHECK (cert_sha256 ~ '^[0-9a-f]{64}$'),
  cost                 numeric(12,0),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_cal_due_after CHECK (next_due_on > calibrated_on)
);
CREATE INDEX ix_cal_equipment_due ON eqp.calibration (equipment_id, next_due_on DESC);

CREATE FUNCTION eqp.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'EQP-409-010 % 는 append-only 입니다(정정은 새 행)', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_calibration_append_only BEFORE UPDATE OR DELETE ON eqp.calibration
  FOR EACH ROW EXECUTE FUNCTION eqp.forbid_mutation();
CREATE TRIGGER trg_calibration_no_truncate BEFORE TRUNCATE ON eqp.calibration
  FOR EACH STATEMENT EXECUTE FUNCTION eqp.forbid_mutation();

-- QA 예외 승인 (BR-EQP-003): 유효기간 최대 7일(양 끝 포함) — TC-EQP-008
CREATE TABLE eqp.calibration_override (
  id           uuid PRIMARY KEY,
  equipment_id uuid NOT NULL REFERENCES eqp.equipment(id),
  project_id   uuid REFERENCES core.project(id),
  reason       text NOT NULL CHECK (length(btrim(reason)) >= 5),
  valid_from   date NOT NULL,
  valid_to     date NOT NULL,
  approved_by  uuid NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_override_max_7_days CHECK (valid_to >= valid_from AND valid_to - valid_from <= 6)
);
CREATE INDEX ix_override_eq ON eqp.calibration_override (equipment_id, valid_from, valid_to);
CREATE TRIGGER trg_override_append_only BEFORE UPDATE OR DELETE ON eqp.calibration_override
  FOR EACH ROW EXECUTE FUNCTION eqp.forbid_mutation();

-- ---------------------------------------------------------------- 예약 (FR-EQP-006, TC-EQP-002)
CREATE TABLE eqp.reservation (
  id           uuid PRIMARY KEY,
  equipment_id uuid NOT NULL REFERENCES eqp.equipment(id),
  project_id   uuid NOT NULL REFERENCES core.project(id),
  period       daterange NOT NULL,                             -- [from, to+1) — 화면 "10/15–10/17" = [10-15, 10-18)
  status       varchar(15) NOT NULL DEFAULT 'REQUESTED',
  conditional  boolean NOT NULL DEFAULT false,
  requested_by uuid NOT NULL,
  approved_by  uuid,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_reservation_status CHECK (status IN ('REQUESTED','CONFIRMED','REJECTED','IN_USE','CANCELLED','EXPIRED',
                                                    'RETURNED','OVERDUE')),
  CONSTRAINT ck_reservation_period CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period)
                                          AND NOT lower_inf(period) AND NOT upper_inf(period)),
  -- 같은 장비 기간 중복 예약 금지 (취소·거절·만료·반납 제외) — 동시 요청도 DB 가 1건만 통과(NFR-EQP-05)
  CONSTRAINT no_double_booking EXCLUDE USING gist (equipment_id WITH =, period WITH &&)
    WHERE (status IN ('REQUESTED','CONFIRMED','IN_USE','OVERDUE'))
);
CREATE INDEX ix_reservation_project ON eqp.reservation (project_id);

-- ---------------------------------------------------------------- 반출·반납 (FR-EQP-007·008)
CREATE TABLE eqp.checkout (
  id             uuid PRIMARY KEY,
  equipment_id   uuid NOT NULL REFERENCES eqp.equipment(id),
  reservation_id uuid REFERENCES eqp.reservation(id),
  project_id     uuid NOT NULL REFERENCES core.project(id),
  out_by         uuid NOT NULL,
  out_at         timestamptz NOT NULL,
  out_lat        numeric(9,6), out_lng numeric(9,6),
  pre_check      jsonb NOT NULL,                               -- 점검표 + 앤빌값
  calibration_id uuid REFERENCES eqp.calibration(id),          -- 반출 시점 판정 근거 스냅샷
  override_id    uuid REFERENCES eqp.calibration_override(id),
  in_by          uuid,
  in_at          timestamptz,
  in_lat         numeric(9,6), in_lng numeric(9,6),
  post_check     jsonb,
  usage_days     int CHECK (usage_days IS NULL OR usage_days >= 1),
  client_op_id   uuid NOT NULL UNIQUE,                         -- 오프라인 멱등 키 (TC-EQP-004)
  sync_conflict  boolean NOT NULL DEFAULT false,               -- TC-EQP-003
  received_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_checkout_in_after_out CHECK (in_at IS NULL OR in_at >= out_at)
);
-- 활성 체크아웃은 장비당 1건 (BR-EQP-004)
CREATE UNIQUE INDEX ux_active_checkout ON eqp.checkout (equipment_id) WHERE in_at IS NULL AND NOT sync_conflict;
CREATE INDEX ix_checkout_project ON eqp.checkout (project_id);

CREATE TABLE eqp.fault (
  id           uuid PRIMARY KEY,
  equipment_id uuid NOT NULL REFERENCES eqp.equipment(id),
  checkout_id  uuid REFERENCES eqp.checkout(id),
  symptom      text NOT NULL,
  reported_by  uuid NOT NULL,
  reported_at  timestamptz NOT NULL DEFAULT now(),
  status       varchar(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REPAIR','CLOSED'))
);

-- ---------------------------------------------------------------- 알림 멱등 기록
--   due-soon: {equipmentId}:{nextDueOn}:{threshold} / expired: {equipmentId}:{expiredOn} (설계 B6 v0.2 멱등키)
CREATE TABLE eqp.notification_log (
  idempotency_key varchar(120) PRIMARY KEY,
  kind            varchar(20) NOT NULL CHECK (kind IN ('CAL_DUE_SOON','CAL_EXPIRED')),
  equipment_id    uuid NOT NULL REFERENCES eqp.equipment(id),
  ref_date        date NOT NULL,
  threshold_days  smallint,
  event_id        uuid,                                       -- 발행 후 기록(outbox 이벤트 id)
  sent_at         timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 드론 (FR-EQP-016·017·018)
CREATE TABLE eqp.drone_profile (
  equipment_id         uuid PRIMARY KEY REFERENCES eqp.equipment(id),
  registration_no      varchar(30) NOT NULL UNIQUE,            -- 기체 신고번호
  mtow_kg              numeric(5,2) NOT NULL CHECK (mtow_kg > 0),
  insurance_policy_no  varchar(50),
  insurance_expires_on date,
  battery_cycles       jsonb,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL
);

CREATE TABLE eqp.flight_approval (
  id              uuid PRIMARY KEY,
  equipment_id    uuid NOT NULL REFERENCES eqp.drone_profile(equipment_id),
  project_id      uuid NOT NULL REFERENCES core.project(id),
  approval_no     varchar(50) NOT NULL,                        -- 드론원스톱
  area            jsonb NOT NULL,                              -- [[lng,lat], …] 외곽 링(WGS84)
  max_alt_m       int,
  valid_from      timestamptz NOT NULL,
  valid_to        timestamptz NOT NULL,
  photo_permit_no varchar(50),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_approval_period CHECK (valid_to > valid_from),
  CONSTRAINT ck_approval_area CHECK (jsonb_typeof(area) = 'array' AND jsonb_array_length(area) >= 3)
);

CREATE TABLE eqp.flight_log (
  id                       uuid PRIMARY KEY,
  equipment_id             uuid NOT NULL REFERENCES eqp.drone_profile(equipment_id),
  project_id               uuid NOT NULL REFERENCES core.project(id),
  approval_id              uuid REFERENCES eqp.flight_approval(id),
  pilot_id                 uuid NOT NULL,
  started_at               timestamptz NOT NULL,
  ended_at                 timestamptz NOT NULL,
  track                    jsonb NOT NULL,                     -- [[lng,lat,alt_m], …]
  max_alt_m                numeric(6,1),
  raw_log_attachment_id    uuid REFERENCES core.attachment(id),
  out_of_area              boolean NOT NULL DEFAULT false,     -- TC-EQP-010
  outside_approval_period  boolean NOT NULL DEFAULT false,     -- FR-EQP-017 수용기준(경고)
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_flight_period CHECK (ended_at > started_at)
);
CREATE INDEX ix_flight_log_project ON eqp.flight_log (project_id);
CREATE TRIGGER trg_flight_log_append_only BEFORE UPDATE OR DELETE ON eqp.flight_log
  FOR EACH ROW EXECUTE FUNCTION eqp.forbid_mutation();
