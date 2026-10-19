-- =====================================================================
-- V0009 — hrq 스키마 (설계 03 v0.4 B3, 03-IMPL Step 1)
--   * PostGIS 컬럼(employee.home_location, timesheet_entry.checkin_point)은 db/migration-ext/V0011 (프로필 postgis)
--   * 장애정보·고용지표·what-if 테이블은 Step 8·14 (후속 마이그레이션)
--   * version 은 core 와 같은 bigint (설계 int)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS hrq;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE hrq.employee (
  id                uuid PRIMARY KEY REFERENCES core.employee(id),   -- core 사용자와 1:1
  emp_no            varchar(20) NOT NULL UNIQUE,
  name              varchar(50) NOT NULL,
  field             varchar(20) NOT NULL CHECK (field IN ('ARCH','CIVIL','SAFETY','STRUCT','OTHER')),
  employment_type   varchar(20) NOT NULL CHECK (employment_type IN ('FULLTIME','PARTTIME','CONCURRENT','CONTRACT')),
  status            varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LEAVE','RESIGN_PLANNED','RESIGNED')),
  registered_field  boolean NOT NULL DEFAULT false,
  hired_on          date NOT NULL,
  resign_planned_on date,
  resigned_on       date,
  douzone_synced_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);

-- 퇴사 확정 이벤트 멱등키 (설계 B6 v0.4: employeeId + resignedOn 1회)
CREATE TABLE hrq.employee_termination (
  employee_id uuid NOT NULL REFERENCES hrq.employee(id),
  resigned_on date NOT NULL,
  source      varchar(20) NOT NULL CHECK (source IN ('DOUZONE_SYNC','MANUAL')),
  event_id    uuid NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (employee_id, resigned_on)
);

-- 등급 이력: 같은 기술자의 유효기간 겹침 금지
CREATE TABLE hrq.grade_history (
  id          uuid PRIMARY KEY,
  employee_id uuid NOT NULL REFERENCES hrq.employee(id),
  grade       varchar(10) NOT NULL CHECK (grade IN ('JUNIOR','INTER','SENIOR','MASTER')),
  valid_from  date NOT NULL,
  valid_to    date,
  evidence_attachment_id uuid REFERENCES core.attachment(id),
  created_at  timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_grade_period CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT grade_history_no_overlap EXCLUDE USING gist
    (employee_id WITH =, daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[)') WITH &&)
);

CREATE TABLE hrq.credential_type (
  code        varchar(30) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  category    varchar(20) NOT NULL CHECK (category IN ('NATIONAL','PRIVATE','LICENSE')),
  has_expiry  boolean NOT NULL DEFAULT false
);

CREATE TABLE hrq.credential (
  id            uuid PRIMARY KEY,
  employee_id   uuid NOT NULL REFERENCES hrq.employee(id),
  type_code     varchar(30) NOT NULL REFERENCES hrq.credential_type(code),
  cert_no       varchar(50),
  acquired_on   date NOT NULL,
  expires_on    date,
  status        varchar(15) NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING','VALID','EXPIRING','EXPIRED','REJECTED','SUPERSEDED','REVOKED')),
  attachment_id uuid REFERENCES core.attachment(id),
  approved_by   uuid, approved_at timestamptz,
  reject_reason text,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_cred_expiry ON hrq.credential (expires_on) WHERE status IN ('VALID','EXPIRING');

CREATE TABLE hrq.training_course (
  code           varchar(30) PRIMARY KEY,
  name           varchar(150) NOT NULL,
  provider       varchar(100),
  required_hours smallint
);

CREATE TABLE hrq.training_renewal_rule (
  id              uuid PRIMARY KEY,
  course_code     varchar(30) NOT NULL REFERENCES hrq.training_course(code),
  valid_months    smallint NOT NULL CHECK (valid_months > 0),
  effective_from  date NOT NULL,
  effective_to    date,
  legal_reference varchar(300) NOT NULL,
  CONSTRAINT training_rule_no_overlap EXCLUDE USING gist
    (course_code WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&)
);

CREATE TABLE hrq.training_record (
  id            uuid PRIMARY KEY,
  employee_id   uuid NOT NULL REFERENCES hrq.employee(id),
  course_code   varchar(30) NOT NULL REFERENCES hrq.training_course(code),
  completed_on  date NOT NULL,
  valid_until   date,
  rule_id       uuid REFERENCES hrq.training_renewal_rule(id),
  status        varchar(15) NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING','VALID','EXPIRING','EXPIRED','REJECTED','SUPERSEDED')),
  attachment_id uuid REFERENCES core.attachment(id),
  approved_by   uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL
);

-- ---------------------------------------------------------------------
-- 규칙 테이블 (BR-HRQ-001: 법령값은 effective-dated 데이터, 기간 중복 금지) ⚠️ 법령 최신본 확인 필요
-- ---------------------------------------------------------------------
CREATE TABLE hrq.registration_rule (
  id               uuid PRIMARY KEY,
  rule_set_version varchar(20) NOT NULL,
  field            varchar(20) NOT NULL,
  min_grade        varchar(10) NOT NULL CHECK (min_grade IN ('JUNIOR','INTER','SENIOR','MASTER')),
  min_count        smallint NOT NULL CHECK (min_count >= 0),
  required_credential varchar(30) REFERENCES hrq.credential_type(code),
  count_leave      boolean NOT NULL DEFAULT false,      -- BR-HRQ-006 휴직자 포함 여부
  count_upward     boolean NOT NULL DEFAULT true,       -- 상위 등급이 하위 요건을 겸용(03-IMPL Step 6 팁, QA 결정)
  effective_from   date NOT NULL,
  effective_to     date,
  legal_reference  varchar(300) NOT NULL,
  approved_by      uuid,
  CONSTRAINT registration_rule_no_overlap EXCLUDE USING gist
    (field WITH =, min_grade WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&)
);

CREATE TABLE hrq.role_requirement_rule (
  id               uuid PRIMARY KEY,
  inspection_type  varchar(20) NOT NULL,
  role             varchar(20) NOT NULL CHECK (role IN ('RESPONSIBLE','PARTICIPANT')),
  min_grade        varchar(10),
  required_credentials varchar(30)[],
  required_courses varchar(30)[],
  effective_from   date NOT NULL, effective_to date,
  legal_reference  varchar(300) NOT NULL,
  CONSTRAINT role_req_no_overlap EXCLUDE USING gist
    (inspection_type WITH =, role WITH =, daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&)
);

CREATE TABLE hrq.concurrency_rule (
  id               uuid PRIMARY KEY,
  code             varchar(20) NOT NULL UNIQUE,
  role             varchar(20) NOT NULL,
  max_concurrent   smallint NOT NULL CHECK (max_concurrent > 0),
  scope_expr       jsonb,
  severity         varchar(4) NOT NULL CHECK (severity IN ('HARD','SOFT')),
  effective_from   date NOT NULL, effective_to date,
  legal_reference  varchar(300) NOT NULL
);

-- ---------------------------------------------------------------------
-- 배정 (Step 9·10 — 스키마만)
-- ---------------------------------------------------------------------
CREATE TABLE hrq.allocation (
  id            uuid PRIMARY KEY,
  project_id    uuid NOT NULL REFERENCES core.project(id),
  employee_id   uuid NOT NULL REFERENCES hrq.employee(id),
  role          varchar(20) NOT NULL CHECK (role IN ('RESPONSIBLE','PARTICIPANT','ASSISTANT','LABELER')),
  inspection_type varchar(20) NOT NULL,
  start_on      date NOT NULL,
  end_on        date NOT NULL CHECK (end_on >= start_on),
  ratio         numeric(4,2) NOT NULL CHECK (ratio > 0 AND ratio <= 1.5),
  status        varchar(20) NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','BLOCKED','PENDING_OVERRIDE','CONFIRMED','ACTIVE','RELEASED','CANCELLED')),
  override_by   uuid, override_reason text,
  check_result  jsonb,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_alloc_emp_period ON hrq.allocation USING gist (employee_id, daterange(start_on, end_on, '[]'))
  WHERE status IN ('CONFIRMED','ACTIVE','PENDING_OVERRIDE');
CREATE UNIQUE INDEX ux_alloc_responsible ON hrq.allocation (project_id)
  WHERE role = 'RESPONSIBLE' AND status IN ('CONFIRMED','ACTIVE');

-- ---------------------------------------------------------------------
-- 타임시트 (v0.3 리비전, BR-HRQ-014: 확정 후 append-only)
-- ---------------------------------------------------------------------
CREATE TABLE hrq.timesheet_entry (
  id             uuid PRIMARY KEY,
  employee_id    uuid NOT NULL REFERENCES hrq.employee(id),
  project_id     uuid NOT NULL REFERENCES core.project(id),
  allocation_id  uuid REFERENCES hrq.allocation(id),
  work_date      date NOT NULL,
  work_type      varchar(15) NOT NULL CHECK (work_type IN ('FIELD','OFFICE','REPORT','TRAVEL','REVIEW','LABELING')),
  hours          numeric(4,2) NOT NULL CHECK (hours > 0 AND hours <= 24),
  source         varchar(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','CHECKIN')),
  status         varchar(12) NOT NULL DEFAULT 'DRAFT'
                 CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','LOCKED','SUPERSEDED')),
  timesheet_key  uuid NOT NULL,                 -- 최초 리비전 id
  revision_no    smallint NOT NULL DEFAULT 1 CHECK (revision_no >= 1),
  supersedes_id  uuid REFERENCES hrq.timesheet_entry(id),
  correction_reason text,
  posting_period char(6) CHECK (posting_period ~ '^\d{4}(0[1-9]|1[0-2])$'),   -- 원가 귀속 월 YYYYMM
  approved_by    uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_ts_revision CHECK ((revision_no = 1) = (supersedes_id IS NULL)),
  CONSTRAINT ck_ts_correction CHECK (revision_no = 1 OR correction_reason IS NOT NULL)
);
CREATE INDEX ix_ts_project_month ON hrq.timesheet_entry (project_id, work_date);
CREATE INDEX ix_ts_emp_date ON hrq.timesheet_entry (employee_id, work_date);
CREATE INDEX ix_ts_posting ON hrq.timesheet_entry (posting_period) WHERE status IN ('APPROVED','LOCKED');
CREATE UNIQUE INDEX ux_ts_revision ON hrq.timesheet_entry (timesheet_key, revision_no);
CREATE UNIQUE INDEX ux_ts_current ON hrq.timesheet_entry (timesheet_key) WHERE status <> 'SUPERSEDED';

-- 확정(APPROVED·LOCKED·SUPERSEDED) 행은 값 변경·삭제 금지. 허용 전이만: APPROVED→LOCKED, APPROVED|LOCKED→SUPERSEDED
CREATE FUNCTION hrq.timesheet_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status IN ('DRAFT','SUBMITTED') THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'confirmed timesheet is append-only (DELETE %, BR-HRQ-014)', OLD.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF OLD.status IN ('DRAFT','SUBMITTED') THEN RETURN NEW; END IF;
  IF (NEW.employee_id, NEW.project_id, NEW.work_date, NEW.work_type, NEW.hours, NEW.timesheet_key, NEW.revision_no,
      NEW.supersedes_id, NEW.posting_period)
     IS DISTINCT FROM
     (OLD.employee_id, OLD.project_id, OLD.work_date, OLD.work_type, OLD.hours, OLD.timesheet_key, OLD.revision_no,
      OLD.supersedes_id, OLD.posting_period) THEN
    RAISE EXCEPTION 'confirmed timesheet values are immutable — correct via new revision (BR-HRQ-014)'
      USING ERRCODE = 'restrict_violation';
  END IF;
  IF NOT ((OLD.status = 'APPROVED' AND NEW.status IN ('APPROVED','LOCKED','SUPERSEDED'))
       OR (OLD.status = 'LOCKED' AND NEW.status IN ('LOCKED','SUPERSEDED'))) THEN
    RAISE EXCEPTION 'timesheet status % -> % not allowed', OLD.status, NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_timesheet_guard BEFORE UPDATE OR DELETE ON hrq.timesheet_entry
  FOR EACH ROW EXECUTE FUNCTION hrq.timesheet_guard();

-- 월 마감·재오픈 (BR-HRQ-013·014)
CREATE TABLE hrq.month_close (
  year_month    char(6) PRIMARY KEY CHECK (year_month ~ '^\d{4}(0[1-9]|1[0-2])$'),
  status        varchar(10) NOT NULL CHECK (status IN ('OPEN','CLOSED','REOPENED')),
  total_mandays numeric(10,2),
  event_id      uuid,
  closed_at     timestamptz, closed_by uuid,
  reopened_at   timestamptz, reopened_by uuid, reopen_reason text
);

-- 만료 알림 멱등 (03-IMPL Step 5 V3_0_5): {recordId}:{threshold}
CREATE TABLE hrq.notification_log (
  idempotency_key varchar(120) PRIMARY KEY,
  record_id       uuid NOT NULL,
  threshold_days  smallint NOT NULL,
  sent_at         timestamptz NOT NULL DEFAULT now()
);
