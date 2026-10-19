-- =====================================================================
-- V0028 — dem 스키마 (설계 10 v0.3 B3, 10-IMPL Step 1 · 3 · 4 · 5 · 6 · 7 · 11 · 12 · 13 · 14 일부)
--   * 10-IMPL 의 V10_001~V10_010 대신 V0028·V0029 (전역 단조 번호, README 마이그레이션 규칙)
--   * 내장 PostgreSQL 16: btree_gist 있음(책임감리자 기간 EXCLUDE 설계 그대로) · PostGIS 없음 →
--       evidence_photo.location geometry 대신 위경도 numeric (PostGIS 프로필에서 확장 예정)
--   * version 은 core 와 같은 bigint (설계 int)
--   * v0.3: work_package.facility_id → fac.building(id). DEM 프로젝트 상태(A6.1)는 core.project 1:1 확장 dem.project
--   * 구조안전성 검토(safety_review) · 해체계획서 · V.E · 시정지시 에스컬레이션 배치는 후속 Step(8 · 10 · 14)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS dem;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- ---------------------------------------------------------------- 규칙(효력일, 설계 B3 rule_param)
CREATE TABLE dem.rule_param (
  rule_key       varchar(60) NOT NULL,      -- MAX_CONCURRENT_SUPERVISION, SUPERVISOR_QUALIFICATION_CODES …
  value_json     jsonb NOT NULL,
  effective_from date NOT NULL,
  effective_to   date,
  legal_basis    text,                      -- ⚠️ 법령 최신본 확인 필요
  approved_by    uuid,                      -- NULL = 예시 값(검토 서명 전)
  PRIMARY KEY (rule_key, effective_from),
  CONSTRAINT ck_rule_param_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- 채번 카운터(CO-2026-0001, TR-2026-0001) — max()+1 금지
CREATE TABLE dem.code_sequence (
  prefix   varchar(10) NOT NULL,
  year     int NOT NULL,
  last_seq int NOT NULL,
  PRIMARY KEY (prefix, year)
);

-- ---------------------------------------------------------------- 프로젝트 · 업무 패키지 (A6.1, FR-DEM-001)
CREATE TABLE dem.project (
  project_id      uuid PRIMARY KEY REFERENCES core.project(id),
  building_id     uuid REFERENCES fac.building(id),           -- 해체 대상 건축물(v0.3 FAC 정식 테이블)
  status          varchar(20) NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','PLANNING','REVIEWING','PERMIT_PENDING','IN_PROGRESS','SUSPENDED',
                                    'COMPLETING','CLOSED')),
  completed_on    date,                                      -- 해체 완료 신고일(COMPLETING 전이)
  completion_submission_id uuid,
  closed_at       timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0
);

CREATE TABLE dem.work_package (
  id               uuid PRIMARY KEY,
  project_id       uuid NOT NULL REFERENCES dem.project(project_id),
  facility_id      uuid REFERENCES fac.building(id),           -- v0.3: FAC 정식 테이블
  package_type     varchar(30) NOT NULL CHECK (package_type IN
                   ('DEM_PLAN','DEM_SAFETY','DEM_TEMP','DEM_SUPERVISION',
                    'STR_DESIGN','STR_REVIEW','STR_SUPERVISION','VE')),
  status           varchar(20) NOT NULL DEFAULT 'DRAFT',
  locale           varchar(5)  NOT NULL DEFAULT 'ko',
  design_code      varchar(40),
  currency         char(3)     NOT NULL DEFAULT 'KRW',
  fx_rate_snapshot numeric(18,6),
  planned_start    date,
  planned_end      date,
  supervisor_gap   boolean NOT NULL DEFAULT false,            -- A8 감리자 공백(퇴사 · 자격 만료) → 일지 작성 차단
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ux_wp_project_type UNIQUE (project_id, package_type)
);
CREATE INDEX ix_wp_project ON dem.work_package(project_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------- 감리자 지정 (FR-DEM-002, BR-DEM-002·003, TC-DEM-003)
CREATE TABLE dem.supervisor_assignment (
  id               uuid PRIMARY KEY,
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  employee_id      uuid NOT NULL REFERENCES core.employee(id),
  role             varchar(20) NOT NULL CHECK (role IN ('CHIEF','ASSISTANT')),
  valid_from       date NOT NULL,
  valid_to         date,
  status           varchar(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
  reason           text,
  release_reason   varchar(60),
  basis_attachment_id uuid REFERENCES core.attachment(id),
  hrq_check_snapshot jsonb NOT NULL,                         -- 지정 시점 자격 · 동시배정 검증 결과 보존(감사 증빙)
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ck_sa_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ex_chief_period EXCLUDE USING gist (work_package_id WITH =, role WITH =,
          daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') WITH &&)
          WHERE (role = 'CHIEF' AND deleted_at IS NULL)        -- 책임감리자 기간 중복 금지
);
CREATE INDEX ix_sa_employee ON dem.supervisor_assignment(employee_id) WHERE deleted_at IS NULL;

-- HRQ 이벤트 읽기 모델(모듈 경계: hrq 내부 테이블을 읽지 않고 hrq::api 이벤트로만 반영)
CREATE TABLE dem.hrq_credential_block (                      -- hrq.credential.expired
  credential_id   uuid PRIMARY KEY,
  employee_id     uuid NOT NULL,
  credential_type varchar(30) NOT NULL,
  expired_on      date NOT NULL,
  received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_cred_block_emp ON dem.hrq_credential_block(employee_id);

CREATE TABLE dem.hrq_employee_block (                        -- hrq.employee.terminated
  employee_id uuid PRIMARY KEY,
  resigned_on date NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dem.supervisor_alert (                          -- A8 감리자 공백 경고(PM · EXEC 알림 대상)
  id              uuid PRIMARY KEY,
  work_package_id uuid NOT NULL REFERENCES dem.work_package(id),
  employee_id     uuid NOT NULL,
  kind            varchar(30) NOT NULL CHECK (kind IN ('CREDENTIAL_EXPIRED','EMPLOYEE_TERMINATED')),
  detail          jsonb NOT NULL DEFAULT '{}',
  created_at      timestamptz NOT NULL DEFAULT now(),
  resolved_at     timestamptz
);

-- ---------------------------------------------------------------- 점검표 템플릿 (FR-DEM-006, 10-IMPL Step 4)
CREATE TABLE dem.checklist_template (
  id             uuid PRIMARY KEY,
  phase_code     varchar(30) NOT NULL CHECK (phase_code IN
                 ('TEMPORARY','ASBESTOS_CHECK','UPPER_DEMOLITION','LOWER_DEMOLITION','DEBRIS_OUT')),
  version_no     int NOT NULL CHECK (version_no >= 1),
  effective_from date NOT NULL,
  legal_basis    text,                       -- ⚠️ 법령 최신본 확인 필요
  approved_by    uuid,                       -- NULL = 예시 항목(감리 책임기술자 검수 전)
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (phase_code, version_no)
);
CREATE TABLE dem.checklist_template_item (
  id               uuid PRIMARY KEY,
  template_id      uuid NOT NULL REFERENCES dem.checklist_template(id),
  seq              smallint NOT NULL,
  title            varchar(200) NOT NULL,
  photo_required   boolean NOT NULL DEFAULT false,   -- BR-DEM-005
  default_severity varchar(10) NOT NULL DEFAULT 'MEDIUM' CHECK (default_severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  UNIQUE (template_id, seq)
);

-- ---------------------------------------------------------------- 감리일지 (FR-DEM-007, BR-DEM-005·006, A8 오프라인)
CREATE TABLE dem.daily_log (
  id               uuid PRIMARY KEY,
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  log_date         date NOT NULL,
  author_id        uuid NOT NULL REFERENCES core.employee(id),
  template_id      uuid NOT NULL REFERENCES dem.checklist_template(id),
  weather_json     jsonb,
  phase_code       varchar(30) NOT NULL,
  progress_pct     smallint CHECK (progress_pct BETWEEN 0 AND 100),
  workers_count    smallint CHECK (workers_count >= 0),
  equipment_json   jsonb,
  remarks          text,
  status           varchar(15) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','LOCKED')),
  device_signed_at timestamptz,
  server_received_at timestamptz NOT NULL DEFAULT now(),
  late_sync        boolean NOT NULL DEFAULT false,
  locked_at        timestamptz,
  content_sha256   char(64),                 -- 잠금 시점 정규화 JSON 해시(사진 해시 목록 포함)
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  UNIQUE (work_package_id, log_date, author_id),
  CONSTRAINT ck_log_locked CHECK (status <> 'LOCKED' OR (locked_at IS NOT NULL AND content_sha256 IS NOT NULL))
);
CREATE INDEX ix_log_unlocked ON dem.daily_log(log_date) WHERE status <> 'LOCKED';

-- 오프라인 동기화 작업 로그: 같은 clientOpId 재전송 → 같은 결과(재처리 0)
CREATE TABLE dem.sync_op (
  client_op_id  uuid PRIMARY KEY,
  daily_log_id  uuid REFERENCES dem.daily_log(id),
  op_kind       varchar(30) NOT NULL,       -- DAILY_LOG_UPSERT
  http_status   int NOT NULL,
  response      jsonb NOT NULL,
  actor_id      uuid NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dem.log_amendment (
  id               uuid PRIMARY KEY,
  daily_log_id     uuid NOT NULL REFERENCES dem.daily_log(id),
  field_path       varchar(120) NOT NULL,
  old_value        jsonb,
  new_value        jsonb,
  reason           text NOT NULL,
  status           varchar(15) NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED','PENDING')),
  client_op_id     uuid,                     -- 잠금 후 도착한 오프라인 동기화(자동 초안, PM 확인 대기)
  amended_by       uuid NOT NULL,
  amended_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dem.checklist_result (
  id               uuid PRIMARY KEY,
  daily_log_id     uuid NOT NULL REFERENCES dem.daily_log(id),
  template_item_id uuid NOT NULL REFERENCES dem.checklist_template_item(id),
  result           varchar(10) NOT NULL CHECK (result IN ('PASS','FAIL','NA')),
  severity         varchar(10) CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  note             text,
  UNIQUE (daily_log_id, template_item_id)
);

-- ---------------------------------------------------------------- 증거 사진 (FR-DEM-008, core 첨부 재사용)
CREATE TABLE dem.evidence_photo (
  id               uuid PRIMARY KEY,
  attachment_id    uuid NOT NULL UNIQUE REFERENCES core.attachment(id),
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  sha256           char(64) NOT NULL,
  captured_at      timestamptz,              -- EXIF / 기기
  received_at      timestamptz NOT NULL DEFAULT now(),
  lat              numeric(9,6),             -- PostGIS 없음 → 위경도 숫자
  lng              numeric(9,6),
  device_id        varchar(80),
  last_verify      varchar(10) CHECK (last_verify IN ('MATCH','MISMATCH')),
  last_verified_at timestamptz,
  created_by       uuid NOT NULL,
  UNIQUE (sha256)
);
CREATE TABLE dem.checklist_photo (
  checklist_result_id uuid NOT NULL REFERENCES dem.checklist_result(id),
  evidence_photo_id   uuid NOT NULL REFERENCES dem.evidence_photo(id),
  PRIMARY KEY (checklist_result_id, evidence_photo_id)
);

-- 시정지시(FR-DEM-009 발행만 — 에스컬레이션 배치 · 작업중지 SLA 는 Step 8 후속)
CREATE TABLE dem.corrective_order (
  id               uuid PRIMARY KEY,
  checklist_result_id uuid NOT NULL UNIQUE REFERENCES dem.checklist_result(id),
  order_no         varchar(30) NOT NULL UNIQUE,  -- CO-2026-0001
  severity         varchar(10) NOT NULL,
  due_at           timestamptz NOT NULL,
  stop_work_requested boolean NOT NULL DEFAULT false,
  status           varchar(15) NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','ACTIONED','VERIFIED','ESCALATED')),
  action_note      text,
  verified_by      uuid,
  verified_at      timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------- 도서 · 리비전 · 해석모델 (FR-DEM-012·013, BR-DEM-008)
CREATE TABLE dem.document (
  id               uuid PRIMARY KEY,
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  doc_no           varchar(40) NOT NULL,     -- S-101, CAL-01
  title            varchar(200) NOT NULL,
  discipline       varchar(20) NOT NULL DEFAULT 'STR',
  is_input         boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  UNIQUE (work_package_id, doc_no)
);

CREATE TABLE dem.analysis_model_version (
  id               uuid PRIMARY KEY,
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  version_no       int NOT NULL,
  software         varchar(40) NOT NULL,     -- MIDAS Gen 2026, ETABS 22
  file_attachment_id uuid NOT NULL REFERENCES core.attachment(id),
  load_combinations jsonb,
  design_code      varchar(40),
  change_summary   text NOT NULL,
  frozen           boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  UNIQUE (work_package_id, version_no)
);

CREATE TABLE dem.transmittal (
  id               uuid PRIMARY KEY,
  work_package_id  uuid NOT NULL REFERENCES dem.work_package(id),
  transmittal_no   varchar(30) NOT NULL UNIQUE,  -- TR-2026-0001
  purpose          varchar(20) NOT NULL CHECK (purpose IN ('FOR_REVIEW','FOR_APPROVAL','FOR_CONSTRUCTION')),
  recipient_client_id uuid REFERENCES core.client(id),
  recipient_name   varchar(200),
  issued_at        timestamptz NOT NULL DEFAULT now(),
  issued_by        uuid NOT NULL,
  acknowledged_at  timestamptz
);

CREATE TABLE dem.document_revision (
  id               uuid PRIMARY KEY,
  document_id      uuid NOT NULL REFERENCES dem.document(id),
  rev_code         varchar(5) NOT NULL,      -- 내부 A, B, … Z, AA
  issued_rev       varchar(5),               -- 발행 0, 1, 2 … (ISSUED 때 부여)
  status           varchar(20) NOT NULL DEFAULT 'WIP'
                   CHECK (status IN ('WIP','INTERNAL_REVIEW','APPROVED','ISSUED','SUPERSEDED')),
  file_attachment_id uuid NOT NULL REFERENCES core.attachment(id),
  model_version_id uuid REFERENCES dem.analysis_model_version(id),
  change_summary   text,
  depends_on       uuid[],                   -- 입력문서 리비전 id 목록(영향분석)
  needs_recheck    boolean NOT NULL DEFAULT false,
  approved_by      uuid,
  approved_at      timestamptz,
  transmittal_id   uuid REFERENCES dem.transmittal(id),
  issued_at        timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  UNIQUE (document_id, rev_code),
  CONSTRAINT ck_rev_issued CHECK (status NOT IN ('ISSUED','SUPERSEDED')
                                  OR (issued_rev IS NOT NULL AND issued_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_rev_issued ON dem.document_revision(document_id, issued_rev) WHERE issued_rev IS NOT NULL;
-- 한 도서에 현재 발행본(ISSUED)은 1개
CREATE UNIQUE INDEX ux_rev_current_issued ON dem.document_revision(document_id) WHERE status = 'ISSUED';

CREATE TABLE dem.transmittal_item (
  transmittal_id   uuid NOT NULL REFERENCES dem.transmittal(id),
  revision_id      uuid NOT NULL REFERENCES dem.document_revision(id),
  PRIMARY KEY (transmittal_id, revision_id)
);

-- ---------------------------------------------------------------- 검토의견서 (FR-DEM-014, BR-DEM-009, A6.3)
CREATE TABLE dem.review_comment (
  id               uuid PRIMARY KEY,
  revision_id      uuid NOT NULL REFERENCES dem.document_revision(id),
  comment_no       varchar(20) NOT NULL,
  source           varchar(20) NOT NULL CHECK (source IN ('INTERNAL','CLIENT','AUTHORITY','EXTERNAL_DESIGNER')),
  severity         varchar(10) NOT NULL DEFAULT 'MINOR' CHECK (severity IN ('MINOR','MAJOR','CRITICAL')),
  body             text NOT NULL,
  markup_json      jsonb,
  status           varchar(15) NOT NULL DEFAULT 'OPEN'
                   CHECK (status IN ('OPEN','RESPONDED','ACCEPTED','REJECTED','CLOSED')),
  author_id        uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (revision_id, comment_no)
);
CREATE INDEX ix_comment_unresolved ON dem.review_comment(revision_id)
  WHERE status IN ('OPEN','RESPONDED','REJECTED');           -- BR-DEM-009 미결 = OPEN · RESPONDED · REJECTED
CREATE TABLE dem.review_reply (
  id               uuid PRIMARY KEY,
  comment_id       uuid NOT NULL REFERENCES dem.review_comment(id),
  author_id        uuid,
  body             text NOT NULL,
  previous_status  varchar(15) NOT NULL,
  resulting_status varchar(15) NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 지자체 제출 (FR-DEM-011)
CREATE TABLE dem.submission (
  id               uuid PRIMARY KEY,
  project_id       uuid NOT NULL REFERENCES dem.project(project_id),
  authority_name   varchar(100) NOT NULL,
  submission_type  varchar(30) NOT NULL CHECK (submission_type IN
                   ('PLAN_PERMIT','START_NOTICE','SUPERVISION_REPORT','COMPLETION')),
  seq              smallint NOT NULL DEFAULT 1,
  submitted_at     timestamptz NOT NULL,
  receipt_no       varchar(60),
  status           varchar(25) NOT NULL DEFAULT 'SUBMITTED'
                   CHECK (status IN ('SUBMITTED','SUPPLEMENT_REQUESTED','ACCEPTED')),
  bundle_attachment_ids uuid[] NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  UNIQUE (project_id, submission_type, seq)
);

-- ---------------------------------------------------------------- 인접건축물 사전 · 사후 조사 (FR-DEM-018, B7.2)
CREATE TABLE dem.adjacent_survey (
  id               uuid PRIMARY KEY,
  project_id       uuid NOT NULL REFERENCES dem.project(project_id),
  adjacent_building_id uuid REFERENCES fac.building(id),       -- 인접 건축물(FAC 에 있으면)
  adjacent_name    varchar(200) NOT NULL,
  phase            varchar(5) NOT NULL CHECK (phase IN ('PRE','POST')),
  surveyed_on      date NOT NULL,
  drawing_version  varchar(40),                                -- 사전 · 사후 같은 도면 버전이어야 좌표 매칭 성립
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL
);
CREATE TABLE dem.adjacent_defect (
  id               uuid PRIMARY KEY,
  survey_id        uuid NOT NULL REFERENCES dem.adjacent_survey(id),
  defect_no        varchar(30) NOT NULL,
  floor            varchar(10) NOT NULL,
  member_ref       varchar(60) NOT NULL,                       -- 벽체/부재 ID
  defect_type      varchar(30) NOT NULL,                       -- CRACK, LEAK, SPALL, TILT …
  x_m              numeric(10,3) NOT NULL,                     -- 도면 좌표(결함 핀, m)
  y_m              numeric(10,3) NOT NULL,
  crack_width_mm   numeric(6,2),
  length_mm        numeric(10,1),
  photo_id         uuid REFERENCES dem.evidence_photo(id),
  UNIQUE (survey_id, defect_no)
);
CREATE TABLE dem.adjacent_comparison (
  id               uuid PRIMARY KEY,
  project_id       uuid NOT NULL REFERENCES dem.project(project_id),
  pre_survey_id    uuid NOT NULL REFERENCES dem.adjacent_survey(id),
  post_survey_id   uuid NOT NULL REFERENCES dem.adjacent_survey(id),
  thresholds       jsonb NOT NULL,           -- 적용한 rule_param 값(재현성)
  summary          jsonb NOT NULL,           -- {SAME, PROGRESSED, NEW, REPAIRED_OR_UNCONFIRMED}
  result           jsonb NOT NULL,
  result_sha256    char(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL
);

-- =====================================================================
-- 불변 트리거 (BR-DEM-008 · BR-DEM-006, 설계 v0.2 강화판 · TC-DEM-008 · 013 · 014)
--   document_revision: ISSUED → SUPERSEDED 상태 전이만 허용, 그 외 컬럼은 한 글자도 바꿀 수 없음
--   daily_log LOCKED · analysis_model_version frozen: UPDATE 금지
--   셋 모두 DELETE 금지, TRUNCATE 금지
-- =====================================================================
-- ⚠️ 설계 B3 원문은 한 IF 식에 OLD.status 와 OLD.frozen 을 OR 로 묶어 PL/pgSQL 이 다른 테이블의 없는 필드를 해석하다
--    "record old has no field frozen" 오류를 낸다(WIP 리비전 UPDATE 도 실패). 테이블별 분기로 고친다(설계 의도 동일).
CREATE OR REPLACE FUNCTION dem.forbid_update_when_immutable() RETURNS trigger AS $$
BEGIN
  IF TG_TABLE_NAME = 'document_revision' THEN
    IF OLD.status IN ('ISSUED','SUPERSEDED') THEN
      IF NOT (OLD.status = 'ISSUED' AND NEW.status = 'SUPERSEDED'
              AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status')) THEN
        RAISE EXCEPTION 'DEM_IMMUTABLE: document_revision % is immutable', OLD.id USING ERRCODE = 'restrict_violation';
      END IF;
    END IF;
  ELSIF TG_TABLE_NAME = 'daily_log' THEN
    IF OLD.status = 'LOCKED' THEN
      RAISE EXCEPTION 'DEM_IMMUTABLE: daily_log % is immutable', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
  ELSIF TG_TABLE_NAME = 'analysis_model_version' THEN
    IF OLD.frozen THEN
      RAISE EXCEPTION 'DEM_IMMUTABLE: analysis_model_version % is immutable', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_rev_immutable BEFORE UPDATE ON dem.document_revision
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_update_when_immutable();
CREATE TRIGGER trg_log_immutable BEFORE UPDATE ON dem.daily_log
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_update_when_immutable();
CREATE TRIGGER trg_model_immutable BEFORE UPDATE ON dem.analysis_model_version
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_update_when_immutable();

CREATE OR REPLACE FUNCTION dem.forbid_delete_when_immutable() RETURNS trigger AS $$
DECLARE
  locked boolean := false;
BEGIN
  IF TG_TABLE_NAME = 'document_revision' THEN
    locked := OLD.status IN ('ISSUED','SUPERSEDED');
  ELSIF TG_TABLE_NAME = 'daily_log' THEN
    locked := OLD.status = 'LOCKED';
  ELSIF TG_TABLE_NAME = 'analysis_model_version' THEN
    locked := OLD.frozen;
  END IF;
  IF locked THEN
    RAISE EXCEPTION 'DEM_IMMUTABLE: delete forbidden on % %', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN OLD;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_rev_nodelete   BEFORE DELETE ON dem.document_revision      FOR EACH ROW EXECUTE FUNCTION dem.forbid_delete_when_immutable();
CREATE TRIGGER trg_log_nodelete   BEFORE DELETE ON dem.daily_log              FOR EACH ROW EXECUTE FUNCTION dem.forbid_delete_when_immutable();
CREATE TRIGGER trg_model_nodelete BEFORE DELETE ON dem.analysis_model_version FOR EACH ROW EXECUTE FUNCTION dem.forbid_delete_when_immutable();

-- append-only: 정정 · 회신 · 템플릿 항목 · 비교 결과(정정은 새 행, 템플릿 변경은 새 버전)
CREATE OR REPLACE FUNCTION dem.forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'DEM_IMMUTABLE: % is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_amendment_append_only BEFORE UPDATE OR DELETE ON dem.log_amendment
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_mutation();
CREATE TRIGGER trg_reply_append_only BEFORE UPDATE OR DELETE ON dem.review_reply
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_mutation();
CREATE TRIGGER trg_tpl_item_append_only BEFORE UPDATE OR DELETE ON dem.checklist_template_item
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_mutation();
CREATE TRIGGER trg_comparison_append_only BEFORE UPDATE OR DELETE ON dem.adjacent_comparison
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_mutation();

CREATE TRIGGER trg_rev_no_truncate BEFORE TRUNCATE ON dem.document_revision
  FOR EACH STATEMENT EXECUTE FUNCTION dem.forbid_mutation();
CREATE TRIGGER trg_log_no_truncate BEFORE TRUNCATE ON dem.daily_log
  FOR EACH STATEMENT EXECUTE FUNCTION dem.forbid_mutation();
CREATE TRIGGER trg_model_no_truncate BEFORE TRUNCATE ON dem.analysis_model_version
  FOR EACH STATEMENT EXECUTE FUNCTION dem.forbid_mutation();
