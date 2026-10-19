-- =====================================================================
-- V5_0_0 — fld 스키마 (설계 05 v0.2 B3.1, 05-IMPL Step 2 · 3 · 9 · 10 · 11 · 12 일부 · 13 일부 · 14 일부) — 서버 측만
--   * 05-IMPL 의 V5_001~V5_014 14개 대신 모듈 번호 대역 V5_0_x (repo 규약: core V1, fac V2, hrq V3, eqp V4 …)
--   * 내장 PostgreSQL 16: PostGIS 없음 → defect_current.geom_sheet(geometry) 생략, 좌표는 정규화 geom_norm(jsonb)만
--   * 이벤트 저장소 불변: 설계의 RULE DO INSTEAD NOTHING(조용히 0건) 대신 트리거로 예외(UPDATE · DELETE · TRUNCATE) —
--     05-IMPL Step 2 함정(버그가 조용히 묻힘)을 DB 에서 막는다. 권한 분리(erp_app GRANT)는 운영 계정 분리 후(Step 14 배포)
--   * 사진 원본은 core 첨부(core.attachment: WORM + chain_scope = 조사 회차 해시 체인)로 확정하고 fld.photo 가 참조한다
--     (설계 fld.evidence_link · evidence_anchor · TSA 는 Step 15 증거 모드 후속)
--   * 결함 계보(lineage) · 이전 회차 매칭 · 재료시험 · 패키지 manifest 는 후속 Step(4 · 12 · 13)
--   * 동기화 op 로그: sync_batch(배치) · sync_op(op 별 최초 판정 = 재전송 멱등 응답 원천) · sync_conflict(충돌 해결 UI)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS fld;

CREATE FUNCTION fld.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'FLD-409-001 % 는 append-only 입니다(정정은 새 행 · 새 이벤트)', TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END $$;

-- ---------------------------------------------------------------- 조사 회차 · 구역
CREATE TABLE fld.survey_round (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES core.project(id),
  building_id   uuid NOT NULL REFERENCES fac.building(id),       -- 설계 facility_id
  round_no      int NOT NULL CHECK (round_no >= 0),
  survey_type   varchar(20) NOT NULL CHECK (survey_type IN ('REGULAR','PRECISE_INSPECTION','PRECISE_DIAGNOSIS',
                                                               'ADJACENT_PRE','ADJACENT_POST','DEMOLITION')),
  evidence_mode boolean NOT NULL DEFAULT false,
  status        varchar(12) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  started_on    date,
  ended_on      date,
  completed_at  timestamptz,                                      -- fld.survey.completed 1회 발행 기준(NULL → 값 1회만)
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid NOT NULL,
  version       bigint NOT NULL DEFAULT 0,
  UNIQUE (project_id, round_no),
  CONSTRAINT ck_round_completed CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_round_building ON fld.survey_round (building_id);

CREATE TABLE fld.zone_assignment (
  id             uuid PRIMARY KEY,
  round_id       uuid NOT NULL REFERENCES fld.survey_round(id),
  zone_name      varchar(50) NOT NULL,
  floor_node_id  uuid REFERENCES fac.location_node(id),           -- 층 노드(선택)
  sheet_id       uuid,                                            -- fac 도면 시트(선택)
  zone_geom_norm jsonb,                                           -- Polygon (정규화)
  assignee_id    uuid NOT NULL,
  status         varchar(12) NOT NULL DEFAULT 'NOT_STARTED' CHECK (status IN ('NOT_STARTED','IN_PROGRESS','LOCKED')),
  locked_at      timestamptz,
  locked_by      uuid,
  version        bigint NOT NULL DEFAULT 0,
  UNIQUE (round_id, zone_name),
  CONSTRAINT ck_zone_lock CHECK ((status = 'LOCKED') = (locked_at IS NOT NULL AND locked_by IS NOT NULL))
);

-- BR-FLD-009 잠금 · 해제 이력(append-only) — 해제는 사유 필수
CREATE TABLE fld.zone_lock_audit (
  id        uuid PRIMARY KEY,
  zone_id   uuid NOT NULL REFERENCES fld.zone_assignment(id),
  action    varchar(8) NOT NULL CHECK (action IN ('LOCK','UNLOCK')),
  reason    text,
  actor_id  uuid NOT NULL,
  at        timestamptz NOT NULL,
  CONSTRAINT ck_unlock_reason CHECK (action = 'LOCK' OR length(trim(coalesce(reason, ''))) >= 5)
);
CREATE TRIGGER trg_zone_lock_audit_append_only BEFORE UPDATE OR DELETE ON fld.zone_lock_audit
  FOR EACH ROW EXECUTE FUNCTION fld.forbid_mutation();

-- 결함 번호 채번(회차 · 유형별 CRK-0001) — max()+1 금지
CREATE TABLE fld.code_counter (
  round_id  uuid NOT NULL REFERENCES fld.survey_round(id),
  type_code varchar(10) NOT NULL,
  last_seq  int NOT NULL,
  PRIMARY KEY (round_id, type_code)
);

-- ---------------------------------------------------------------- 이벤트 저장소 (append-only, 진실의 원천)
CREATE TABLE fld.defect_event (
  seq          bigserial PRIMARY KEY,                             -- 서버 전역 순번 = change feed 커서(연속성 가정 금지)
  op_id        uuid NOT NULL UNIQUE,                              -- 클라이언트 생성 clientOpId, 멱등 키
  defect_id    uuid NOT NULL,
  round_id     uuid NOT NULL REFERENCES fld.survey_round(id),
  zone_id      uuid REFERENCES fld.zone_assignment(id),
  event_type   varchar(40) NOT NULL CHECK (event_type IN ('DefectCreated','DefectGeometryChanged','DefectClassified',
                 'DefectMeasured','DefectAttributesSet','DefectMemberLinked','PhotoAttached','PhotoDetached',
                 'VoiceMemoAttached','AiSuggestionAttached','AiSuggestionDecided','DefectVerified','DefectUnverified',
                 'DefectRemoved')),
  payload      jsonb NOT NULL,                                    -- 클라이언트 payload + "_server"(서버 판정: 번호 · 잠정 여부)
  hlc          varchar(64) NOT NULL CHECK (hlc ~ '^[0-9]{16}:[0-9]{4}:[A-Za-z0-9_-]{1,40}$'),
  device_id    uuid NOT NULL,
  actor_id     uuid NOT NULL,
  after_lock   boolean NOT NULL DEFAULT false,                    -- BR-FLD-009
  received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_devt_defect ON fld.defect_event (defect_id, seq);
CREATE INDEX ix_devt_round_seq ON fld.defect_event (round_id, seq);
CREATE TRIGGER trg_defect_event_append_only BEFORE UPDATE OR DELETE ON fld.defect_event
  FOR EACH ROW EXECUTE FUNCTION fld.forbid_mutation();
CREATE TRIGGER trg_defect_event_no_truncate BEFORE TRUNCATE ON fld.defect_event
  FOR EACH STATEMENT EXECUTE FUNCTION fld.forbid_mutation();

-- ---------------------------------------------------------------- 프로젝션 (읽기 모델, 이벤트에서 언제든 재구축)
CREATE TABLE fld.defect_current (
  id               uuid PRIMARY KEY,
  round_id         uuid NOT NULL REFERENCES fld.survey_round(id),
  zone_id          uuid REFERENCES fld.zone_assignment(id),
  code             varchar(20) NOT NULL,                          -- CRK-0001 (회차 내 유형별)
  location_node_id uuid,                                          -- fac.location_node (모듈 간 FK 미설정, fac::api 로 검증)
  sheet_id         uuid,
  sheet_version    int,
  type_code        varchar(10) NOT NULL,
  subtype_code     varchar(20),
  geom_norm        jsonb NOT NULL,
  attrs            jsonb NOT NULL DEFAULT '{}',                   -- width_mm · length_m · area_m2 … + direction · memo …
  measure_meta     jsonb NOT NULL DEFAULT '{}',                   -- 측정 항목별 method · equipmentId · measurementId
  field_hlc        jsonb NOT NULL DEFAULT '{}',                   -- 필드별 마지막 HLC(LWW = lastModified)
  version_vector   jsonb NOT NULL DEFAULT '{}',                   -- 기기별 반영된 최대 HLC
  photos           jsonb NOT NULL DEFAULT '[]',
  voice_memos      jsonb NOT NULL DEFAULT '[]',
  ai_pending       uuid,                                          -- 미결 AI 제안
  status           varchar(15) NOT NULL CHECK (status IN ('DRAFT','RECORDED','AI_SUGGESTED','VERIFIED','REMOVED')),
  removed_reason   text,
  version          bigint NOT NULL,                               -- 반영 이벤트 수(엔티티 버전)
  last_seq         bigint NOT NULL,
  created_by       uuid NOT NULL,
  created_device   uuid NOT NULL,
  updated_at       timestamptz NOT NULL,
  UNIQUE (round_id, code)
);
CREATE INDEX ix_defect_round_zone ON fld.defect_current (round_id, zone_id) WHERE status <> 'REMOVED';

-- ---------------------------------------------------------------- 측정값 (정정은 신규 행, BR-FLD-003 · 008)
CREATE TABLE fld.measurement (
  id                 uuid PRIMARY KEY,
  op_id              uuid NOT NULL UNIQUE,
  seq                bigint NOT NULL,
  defect_id          uuid NOT NULL,
  round_id           uuid NOT NULL REFERENCES fld.survey_round(id),
  item               varchar(30) NOT NULL,
  value              numeric(12,4) NOT NULL,
  unit               varchar(10) NOT NULL,
  method             varchar(15) NOT NULL CHECK (method IN ('INSTRUMENT','GAUGE','VISUAL','AI','DRAWING_CALC')),
  equipment_id       uuid,                                        -- eqp.equipment (eqp::api 로만 검증)
  measured_at        timestamptz NOT NULL,
  calibration_status varchar(15) NOT NULL CHECK (calibration_status IN ('VALID','INVALID','NOT_APPLICABLE')),
  calibration_basis  varchar(15),
  calibration_reason varchar(40),
  trace_id           uuid,                                        -- eqp.measurement_trace
  provisional        boolean NOT NULL,                            -- 서버 재검증 실패 → 잠정 유지(TC-FLD-014)
  recorded_at        timestamptz NOT NULL,
  CONSTRAINT ck_measure_equipment CHECK (method NOT IN ('INSTRUMENT','GAUGE') OR equipment_id IS NOT NULL),
  CONSTRAINT ck_measure_valid CHECK (calibration_status <> 'INVALID' OR provisional)
);
CREATE INDEX ix_measure_defect ON fld.measurement (defect_id, item, seq);
CREATE TRIGGER trg_measurement_append_only BEFORE UPDATE OR DELETE ON fld.measurement
  FOR EACH ROW EXECUTE FUNCTION fld.forbid_mutation();

-- ---------------------------------------------------------------- 사진 (원본 = core 첨부 WORM, BR-FLD-002)
CREATE TABLE fld.photo (
  id             uuid PRIMARY KEY,                                -- 기기 생성 photoId
  round_id       uuid NOT NULL REFERENCES fld.survey_round(id),
  defect_id      uuid,
  owner_type     varchar(15) NOT NULL DEFAULT 'DEFECT' CHECK (owner_type IN ('DEFECT','MATERIAL_TEST','GENERAL','EVIDENCE')),
  kind           varchar(10) NOT NULL DEFAULT 'NEAR' CHECK (kind IN ('FAR','NEAR','SCALE','OVERVIEW')),
  device_sha256  char(64) NOT NULL UNIQUE,                        -- 촬영 직후 기기 해시
  size_bytes     bigint,
  mime_type      varchar(60) NOT NULL DEFAULT 'image/jpeg',
  exif           jsonb NOT NULL DEFAULT '{}',
  taken_at       timestamptz,
  device_id      uuid NOT NULL,
  upload_state   varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (upload_state IN ('PENDING','UPLOADING','VERIFIED','REJECTED')),
  attachment_id  uuid REFERENCES core.attachment(id),
  server_sha256  char(64),
  chain_hash     char(64),
  reject_reason  varchar(60),
  reject_count   int NOT NULL DEFAULT 0,
  verified_at    timestamptz,
  invalidated    boolean NOT NULL DEFAULT false,                  -- 증거 모드 무효 표시(BR-FLD-010)
  created_at     timestamptz NOT NULL,
  created_by     uuid NOT NULL,
  CONSTRAINT ck_photo_verified CHECK (upload_state <> 'VERIFIED'
      OR (attachment_id IS NOT NULL AND server_sha256 = device_sha256 AND chain_hash IS NOT NULL))
);
CREATE INDEX ix_photo_round ON fld.photo (round_id, upload_state);
CREATE INDEX ix_photo_defect ON fld.photo (defect_id);

-- 검증된 사진은 원본 참조 · 해시가 바뀌지 않고, 사진 행은 지우지 않는다
CREATE FUNCTION fld.photo_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'FLD-409-001 fld.photo 는 삭제할 수 없습니다(무효 표시만)' USING ERRCODE = 'restrict_violation';
  END IF;
  IF OLD.upload_state = 'VERIFIED' AND (NEW.upload_state, NEW.attachment_id, NEW.server_sha256, NEW.device_sha256,
       NEW.chain_hash) IS DISTINCT FROM (OLD.upload_state, OLD.attachment_id, OLD.server_sha256, OLD.device_sha256,
       OLD.chain_hash) THEN
    RAISE EXCEPTION 'FLD-409-001 검증된 사진 원본 · 해시는 바꿀 수 없습니다' USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_photo_guard BEFORE UPDATE OR DELETE ON fld.photo
  FOR EACH ROW EXECUTE FUNCTION fld.photo_guard();

-- 이어 올리기 세션(tus 1.0 core 의미: 생성 → HEAD offset → PATCH 청크 → 완료 시 SHA-256 검증)
CREATE TABLE fld.upload_session (
  id             uuid PRIMARY KEY,
  photo_id       uuid NOT NULL REFERENCES fld.photo(id),
  total_bytes    bigint NOT NULL CHECK (total_bytes > 0),
  received_bytes bigint NOT NULL DEFAULT 0,
  status         varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','COMPLETED','REJECTED','EXPIRED')),
  reject_reason  varchar(60),
  device_id      uuid,
  created_at     timestamptz NOT NULL,
  updated_at     timestamptz NOT NULL,
  expires_at     timestamptz NOT NULL,
  created_by     uuid NOT NULL,
  CONSTRAINT ck_upload_offset CHECK (received_bytes BETWEEN 0 AND total_bytes)
);
CREATE UNIQUE INDEX ux_upload_open ON fld.upload_session (photo_id) WHERE status = 'OPEN';

CREATE TABLE fld.upload_chunk (
  session_id   uuid NOT NULL REFERENCES fld.upload_session(id),
  offset_bytes bigint NOT NULL,
  length       int NOT NULL CHECK (length > 0),
  object_key   varchar(300) NOT NULL,
  sha256       char(64) NOT NULL,
  received_at  timestamptz NOT NULL,
  PRIMARY KEY (session_id, offset_bytes)
);

-- ---------------------------------------------------------------- 음성 메모 (원본 음성 = core 첨부, STT 텍스트)
CREATE TABLE fld.voice_memo (
  id            uuid PRIMARY KEY,
  op_id         uuid NOT NULL UNIQUE,
  seq           bigint NOT NULL,
  defect_id     uuid NOT NULL,
  round_id      uuid NOT NULL REFERENCES fld.survey_round(id),
  attachment_id uuid REFERENCES core.attachment(id),
  transcript    text,
  stt_model     varchar(60),
  duration_ms   int CHECK (duration_ms IS NULL OR duration_ms >= 0),
  recorded_at   timestamptz NOT NULL
);
CREATE TRIGGER trg_voice_memo_append_only BEFORE UPDATE OR DELETE ON fld.voice_memo
  FOR EACH ROW EXECUTE FUNCTION fld.forbid_mutation();

-- ---------------------------------------------------------------- AI 제안 (BR-FLD-004: 수락 전 보고서 미반영)
CREATE TABLE fld.ai_suggestion (
  id               uuid PRIMARY KEY,
  defect_id        uuid NOT NULL,
  photo_id         uuid REFERENCES fld.photo(id),
  round_id         uuid NOT NULL REFERENCES fld.survey_round(id),
  model_version    varchar(60) NOT NULL,
  job_id           uuid,                                          -- aip.job (aip::api 동기 Job)
  result           jsonb NOT NULL,
  width_mm         numeric(8,3),
  confidence       numeric(4,3),
  decision         varchar(10) CHECK (decision IN ('ACCEPTED','MODIFIED','REJECTED')),
  decided_width_mm numeric(8,3),
  decided_by       uuid,
  decided_at       timestamptz,
  discrepancy_at   timestamptz,                                   -- fld.ai-suggestion.discrepancy 1회 발행 기준
  created_at       timestamptz NOT NULL
);
CREATE INDEX ix_ai_defect ON fld.ai_suggestion (defect_id, created_at);

-- ---------------------------------------------------------------- 동기화 op 로그 · 커서 · 충돌
CREATE TABLE fld.sync_batch (
  id                      uuid PRIMARY KEY,
  round_id                uuid NOT NULL REFERENCES fld.survey_round(id),
  device_id               uuid NOT NULL,
  actor_id                uuid NOT NULL,
  client_last_pulled_seq  bigint NOT NULL,
  op_count                int NOT NULL,
  accepted                int NOT NULL,
  duplicates              int NOT NULL,
  conflicts               int NOT NULL,
  rejected                int NOT NULL,
  clock_drift_ms          bigint,
  received_at             timestamptz NOT NULL
);
CREATE TRIGGER trg_sync_batch_append_only BEFORE UPDATE OR DELETE ON fld.sync_batch
  FOR EACH ROW EXECUTE FUNCTION fld.forbid_mutation();

-- op 별 최초 판정 — 같은 opId 재전송은 이 행으로 같은 답(ACCEPTED 원본은 DUPLICATE + seq)
CREATE TABLE fld.sync_op (
  op_id        uuid PRIMARY KEY,
  batch_id     uuid NOT NULL REFERENCES fld.sync_batch(id),
  round_id     uuid NOT NULL REFERENCES fld.survey_round(id),
  defect_id    uuid,
  device_id    uuid NOT NULL,
  actor_id     uuid NOT NULL,
  event_type   varchar(40),
  hlc          varchar(64),
  status       varchar(12) NOT NULL CHECK (status IN ('ACCEPTED','CONFLICT','REJECTED')),
  seq          bigint,
  conflict_id  uuid,
  reason       varchar(60),
  result       jsonb NOT NULL,
  received_at  timestamptz NOT NULL,
  CONSTRAINT ck_sync_op_status CHECK ((status = 'ACCEPTED') = (seq IS NOT NULL)
                                   AND (status = 'CONFLICT') = (conflict_id IS NOT NULL))
);
CREATE INDEX ix_sync_op_batch ON fld.sync_op (batch_id);

CREATE TABLE fld.device_cursor (
  device_id        uuid NOT NULL,
  round_id         uuid NOT NULL REFERENCES fld.survey_round(id),
  last_pulled_seq  bigint NOT NULL DEFAULT 0,
  last_seen_at     timestamptz NOT NULL,
  PRIMARY KEY (device_id, round_id)
);

CREATE TABLE fld.sync_conflict (
  id               uuid PRIMARY KEY,
  round_id         uuid NOT NULL REFERENCES fld.survey_round(id),
  zone_id          uuid REFERENCES fld.zone_assignment(id),
  defect_id        uuid NOT NULL,
  field            varchar(40) NOT NULL,                          -- attrs.width_mm · geom · status
  strategy         varchar(12) NOT NULL CHECK (strategy IN ('MANUAL','GEOM_5MIN','REMOVE_WINS')),
  local_op_id      uuid NOT NULL UNIQUE,
  local_event_type varchar(40) NOT NULL,
  local_payload    jsonb NOT NULL,                                -- 원 op 그대로(이벤트로 저장하지 않으므로 감사 원천)
  local_value      jsonb,
  local_hlc        varchar(64) NOT NULL,
  local_device_id  uuid NOT NULL,
  local_actor_id   uuid NOT NULL,
  server_seq       bigint NOT NULL,
  server_value     jsonb,
  server_hlc       varchar(64),
  server_device_id uuid,
  status           varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
  resolution       varchar(12) CHECK (resolution IN ('KEEP_SERVER','KEEP_CLIENT','MERGE')),
  resolved_value   jsonb,
  resolution_op_id uuid UNIQUE,
  resolution_seq   bigint,
  resolved_by      uuid,
  resolved_at      timestamptz,
  created_at       timestamptz NOT NULL,
  CONSTRAINT ck_conflict_resolved CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX ix_conflict_open ON fld.sync_conflict (round_id, zone_id) WHERE status = 'OPEN';
