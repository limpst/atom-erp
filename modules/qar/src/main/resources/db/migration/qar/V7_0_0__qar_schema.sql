-- =====================================================================
-- QAR 품질·검토 스키마 (설계 07 v0.3 B3 / 07-IMPL Step 1, Flyway 대역 V7_x)
--   * 07-IMPL 의 V0701~V0708 대신 V7_0_0(스키마 · 가드)·V7_0_1(시드 · 권한) — 모듈 번호 대역
--   * 이번 단계 범위: 워크플로우 · 검토/라운드 · 배정 · 체크리스트 · AUTO-CHECK(유효기간 규칙 세트 · 실행 · 결과) · waiver ·
--     AI 검토 작업/제안 · 코멘트/답변. 외부 평가 · 개선 과제 · 품질 지표(Step 11·12)는 후속
--   * report_id · revision_id 는 rpt 논리 참조(모듈 경계 — FK 없음, rpt::api RptRevisionQuery 로만 조회)
--   * 공통 6컬럼 version 은 bigint (다른 모듈과 동일)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS qar;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;   -- 체크리스트 유형별 유효기간 겹침 EXCLUDE

-- ---------------------------------------------------------------------
-- 1) 워크플로우 정의 (FR-QAR-001, 버전 관리)
-- ---------------------------------------------------------------------
CREATE TABLE qar.workflow_def (
  id             uuid PRIMARY KEY,
  code           varchar(50) NOT NULL,             -- WF_REG_BASIC · WF_PSD_STANDARD
  version        int NOT NULL,
  applies_to     jsonb NOT NULL,                   -- {"reportTypes":["RT_PSD"]}
  effective_from date NOT NULL, effective_to date,
  status         varchar(20) NOT NULL CHECK (status IN ('DRAFT','DEPLOYED','RETIRED')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, deleted_at timestamptz,
  UNIQUE (code, version)
);
CREATE TABLE qar.workflow_step_def (
  id              uuid PRIMARY KEY,
  workflow_def_id uuid NOT NULL REFERENCES qar.workflow_def(id),
  seq             smallint NOT NULL,
  step_code       varchar(30) NOT NULL CHECK (step_code IN ('SELF_CHECK','AI_REVIEW','QA_REVIEW','PEER_REVIEW',
                                                           'FINAL_APPROVAL')),
  role_required   varchar(20) NOT NULL,
  sla_hours       int,
  optional        boolean NOT NULL DEFAULT false,
  UNIQUE (workflow_def_id, seq)
);

-- ---------------------------------------------------------------------
-- 2) 검토 · 라운드 (FR-QAR-002, A6 검토 상태, BR-QAR-010)
-- ---------------------------------------------------------------------
CREATE TABLE qar.review (
  id              uuid PRIMARY KEY,
  report_id       uuid NOT NULL,                   -- rpt.report(id) 논리 참조
  project_id      uuid NOT NULL REFERENCES core.project(id),
  report_type     varchar(20) NOT NULL,
  workflow_def_id uuid NOT NULL REFERENCES qar.workflow_def(id),
  status          varchar(25) NOT NULL CHECK (status IN ('SELF_CHECK','SUBMITTED','AI_REVIEWING','QA_REVIEWING',
                                                         'PENDING_APPROVAL','REJECTED','APPROVED')),
  current_round   int NOT NULL DEFAULT 1,
  approved_at     timestamptz, approved_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  UNIQUE (report_id)                               -- 보고서당 1 검토 (정정본도 같은 검토의 새 라운드)
);
CREATE INDEX ix_review_status ON qar.review(status) WHERE deleted_at IS NULL;
CREATE INDEX ix_review_project ON qar.review(project_id);

-- 라운드는 자체검토 단계부터 존재하고(체크리스트 · AUTO-CHECK 결과가 붙는다) 제출(SUBMIT) 때 리비전 · 해시 · 규칙 버전을 고정한다
CREATE TABLE qar.review_round (
  id             uuid PRIMARY KEY,
  review_id      uuid NOT NULL REFERENCES qar.review(id),
  round_no       int NOT NULL CHECK (round_no >= 1),
  revision_id    uuid NOT NULL,                    -- rpt.report_revision(id) 논리 참조
  rev_no         int NOT NULL,
  snapshot_ver   int,
  pdf_sha256     char(64),
  content_hash   char(64),                         -- RPT 본문 정규 해시(제출 후 변경 감지)
  rule_versions  jsonb,                            -- {"AUTOCHECK":"2026.1","CHECKLIST":"RT_COMMON@2026.1"} (BR-QAR-007)
  submitted_at   timestamptz, submitted_by uuid,
  ai_status      varchar(12) NOT NULL DEFAULT 'NONE' CHECK (ai_status IN ('NONE','RUNNING','DONE','SKIPPED')),
  ai_skip_reason varchar(30),                      -- TIMEOUT · FAILED · DISABLED ("AI 검토 미수행" 배지)
  opened_by_cmd  varchar(25) NOT NULL DEFAULT 'CREATE' CHECK (opened_by_cmd IN ('CREATE','RESUBMIT_PREP',
                                                                                'REOPEN_FOR_CORRECTION')),
  closed_at      timestamptz,
  outcome        varchar(15) CHECK (outcome IN ('REJECTED','APPROVED')),
  reject_summary text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (review_id, round_no),
  CONSTRAINT ck_round_frozen CHECK (submitted_at IS NULL OR (pdf_sha256 IS NOT NULL AND content_hash IS NOT NULL
                                                             AND rule_versions IS NOT NULL AND submitted_by IS NOT NULL)),
  CONSTRAINT ck_round_outcome CHECK ((outcome IS NULL) = (closed_at IS NULL))
);

CREATE TABLE qar.review_assignment (
  id           uuid PRIMARY KEY,
  round_id     uuid NOT NULL REFERENCES qar.review_round(id),
  step_code    varchar(30) NOT NULL CHECK (step_code IN ('QA_REVIEW','PEER_REVIEW','FINAL_APPROVAL')),
  assignee_id  uuid NOT NULL,                      -- core.employee
  assigned_by  varchar(10) NOT NULL CHECK (assigned_by IN ('AUTO','MANUAL')),
  score_detail jsonb,                              -- 자동 배정 점수 근거(B7.2 설명 가능성)
  due_at       timestamptz,
  started_at   timestamptz, completed_at timestamptz,
  decision     varchar(10) CHECK (decision IN ('PASS','REJECT','REASSIGNED')),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_assignment_inbox ON qar.review_assignment(assignee_id) WHERE completed_at IS NULL;
-- 단계별 활성 담당자는 1명
CREATE UNIQUE INDEX ux_assignment_active ON qar.review_assignment(round_id, step_code) WHERE completed_at IS NULL;

-- QA 검토자 풀(자동 배정 후보) — HRQ 일정 · 전문분야 연동 전 수기 프로필
CREATE TABLE qar.reviewer_profile (
  employee_id uuid PRIMARY KEY,
  step_code   varchar(30) NOT NULL DEFAULT 'QA_REVIEW',
  active      boolean NOT NULL DEFAULT true,
  max_open    int NOT NULL DEFAULT 5 CHECK (max_open > 0),
  expertise   jsonb NOT NULL DEFAULT '{}',          -- {"RT_PSD": 12, "RT_REG": 3} 과거 검토 건수
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3) 체크리스트 rule table (FR-QAR-006·007, BR-QAR-007)  ⚠️ 법령 최신본 확인 필요
-- ---------------------------------------------------------------------
CREATE TABLE qar.checklist_template (
  id             uuid PRIMARY KEY,
  report_type    varchar(20) NOT NULL,             -- RT_COMMON = 유형별 템플릿이 없을 때 공통
  version        varchar(20) NOT NULL,
  effective_from date NOT NULL, effective_to date,
  status         varchar(20) NOT NULL CHECK (status IN ('DRAFT','APPROVED','DEPLOYED','RETIRED')),
  UNIQUE (report_type, version),
  CONSTRAINT ck_ct_period CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT ex_ct_overlap EXCLUDE USING gist (report_type WITH =,
      daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&) WHERE (status = 'DEPLOYED')
);
CREATE TABLE qar.checklist_item (
  id                uuid PRIMARY KEY,
  template_id       uuid NOT NULL REFERENCES qar.checklist_template(id),
  item_no           varchar(10) NOT NULL,
  category          varchar(30) NOT NULL,
  question          text NOT NULL,
  legal_basis       text,
  weight            numeric(4,2) NOT NULL DEFAULT 1,
  criticality       varchar(10) NOT NULL CHECK (criticality IN ('CRITICAL','MAJOR','MINOR')),
  autocheck_rule_id varchar(20) CHECK (autocheck_rule_id ~ '^AC-[A-Z]{3,4}-\d{3}$'),
  stage             varchar(10) NOT NULL CHECK (stage IN ('SELF','QA','BOTH')),
  UNIQUE (template_id, item_no)
);
CREATE TABLE qar.checklist_response (
  id             uuid PRIMARY KEY,
  round_id       uuid NOT NULL REFERENCES qar.review_round(id),
  item_id        uuid NOT NULL REFERENCES qar.checklist_item(id),
  stage          varchar(10) NOT NULL CHECK (stage IN ('SELF','QA')),
  answer         varchar(10) NOT NULL CHECK (answer IN ('PASS','FAIL','NA')),
  auto_prefilled boolean NOT NULL DEFAULT false,
  note           text,
  answered_by    uuid NOT NULL, answered_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (round_id, item_id, stage)
);

-- ---------------------------------------------------------------------
-- 4) AUTO-CHECK (FR-QAR-008, A4.1) — 규칙 세트 유효기간(effective-dated) · 실행 묶음 · 결과(append-only)
-- ---------------------------------------------------------------------
CREATE TABLE qar.autocheck_rule_set (
  id             uuid PRIMARY KEY,
  version        varchar(20) NOT NULL UNIQUE,      -- 2025.1 · 2026.1
  effective_from date NOT NULL, effective_to date,
  status         varchar(20) NOT NULL CHECK (status IN ('DRAFT','APPROVED','DEPLOYED','RETIRED')),
  legal_basis    text,
  approved_by    uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_rs_period CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT ex_rs_overlap EXCLUDE USING gist (
      daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&) WHERE (status = 'DEPLOYED')
);
CREATE TABLE qar.autocheck_rule_def (
  rule_set_id      uuid NOT NULL REFERENCES qar.autocheck_rule_set(id),
  rule_id          varchar(20) NOT NULL CHECK (rule_id ~ '^AC-[A-Z]{3,4}-\d{3}$'),
  description      text NOT NULL,
  default_severity varchar(10) NOT NULL CHECK (default_severity IN ('BLOCKING','WARNING','INFO')),
  params           jsonb NOT NULL DEFAULT '{}',    -- 허용오차 · 금지어 사전 · 필수 섹션 등
  enabled          boolean NOT NULL DEFAULT true,
  applies_to       text[] NOT NULL,                -- 보고서 유형 ('*' = 전체)
  PRIMARY KEY (rule_set_id, rule_id)
);
CREATE TABLE qar.autocheck_run (
  id            uuid PRIMARY KEY,                  -- run_id
  round_id      uuid NOT NULL REFERENCES qar.review_round(id),
  rule_set_id   uuid NOT NULL REFERENCES qar.autocheck_rule_set(id),
  rule_version  varchar(20) NOT NULL,
  as_of         date NOT NULL,
  content_hash  char(64) NOT NULL,                 -- 실행 대상 RPT 본문 해시(제출 가드: 최신 본문으로 실행했는지)
  blocking_count int NOT NULL, warning_count int NOT NULL, info_count int NOT NULL,
  duration_ms   int NOT NULL,
  ran_by        uuid NOT NULL,
  ran_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_autocheck_run_round ON qar.autocheck_run(round_id, ran_at DESC);
CREATE TABLE qar.autocheck_result (
  id            uuid PRIMARY KEY,
  round_id      uuid NOT NULL REFERENCES qar.review_round(id),
  run_id        uuid NOT NULL REFERENCES qar.autocheck_run(id),
  rule_id       varchar(20) NOT NULL,
  severity      varchar(10) NOT NULL CHECK (severity IN ('BLOCKING','WARNING','INFO')),
  report_anchor jsonb,                             -- 보고서 위치(blockId · start/end · sectionKey)
  source_ref    jsonb,                             -- 원천 데이터 위치(물량표 셀 · 결함 ID · 장비)
  expected      text, actual text,
  message       text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_autocheck_round ON qar.autocheck_result(round_id, severity);
CREATE INDEX ix_autocheck_result_run ON qar.autocheck_result(run_id);

-- Waiver (BR-QAR-006): PM · QA 두 사람 사유 — 먼저 입력한 쪽이 PENDING, 나머지가 입력하면 GRANTED
CREATE TABLE qar.waiver (
  id                  uuid PRIMARY KEY,
  autocheck_result_id uuid NOT NULL UNIQUE REFERENCES qar.autocheck_result(id),
  pm_id uuid, pm_reason text, pm_at timestamptz,
  qa_id uuid, qa_reason text, qa_at timestamptz,
  status              varchar(10) NOT NULL CHECK (status IN ('PENDING','GRANTED')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_waiver_people CHECK (pm_id IS NULL OR qa_id IS NULL OR pm_id <> qa_id),
  CONSTRAINT ck_waiver_reasons CHECK ((pm_id IS NULL) = (pm_reason IS NULL) AND (qa_id IS NULL) = (qa_reason IS NULL)),
  CONSTRAINT ck_waiver_granted CHECK (status <> 'GRANTED' OR (pm_id IS NOT NULL AND qa_id IS NOT NULL))
);

-- ---------------------------------------------------------------------
-- 5) AI 검토 (FR-QAR-010·011, BR-QAR-005, 07-IMPL Step 9)
-- ---------------------------------------------------------------------
CREATE TABLE qar.ai_review_job (
  id            uuid PRIMARY KEY,
  round_id      uuid NOT NULL REFERENCES qar.review_round(id),
  status        varchar(10) NOT NULL CHECK (status IN ('QUEUED','RUNNING','DONE','TIMEOUT','FAILED')),
  requested_by  varchar(10) NOT NULL CHECK (requested_by IN ('AUTO','MANUAL')),  -- MANUAL = AI_START 수동 실행
  deadline_at   timestamptz NOT NULL,             -- 요청 + 10분 (NFR-QAR-02)
  started_at    timestamptz, finished_at timestamptz,
  chunk_count   int, finding_count int,
  error         text,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_job_pending ON qar.ai_review_job(status, deadline_at) WHERE status IN ('QUEUED','RUNNING');

CREATE TABLE qar.ai_finding (
  id                 uuid PRIMARY KEY,
  round_id           uuid NOT NULL REFERENCES qar.review_round(id),
  job_id             uuid NOT NULL REFERENCES qar.ai_review_job(id),
  category           varchar(30) NOT NULL,         -- NUM_MISMATCH · MISSING_EVIDENCE · LOGIC_GAP · RISKY_PHRASE · MISSING_SECTION
  severity_suggested varchar(10) NOT NULL CHECK (severity_suggested IN ('CRITICAL','MAJOR','MINOR','INFO')),
  statement          text NOT NULL,
  citations          jsonb NOT NULL,
  guard_passed       boolean NOT NULL,             -- CitationGuard 결과(false 는 저장만, 검토자 비노출)
  guard_reason       varchar(30),                  -- NO_CITATION · UNKNOWN_BLOCK · QUOTE_NOT_FOUND · DATA_MISMATCH …
  model              varchar(100) NOT NULL, prompt_hash char(64) NOT NULL,
  decision           varchar(15) CHECK (decision IN ('ADOPTED','DISMISSED')),
  dismiss_reason     varchar(30) CHECK (dismiss_reason IN ('FALSE_POSITIVE','NOT_IMPORTANT','ALREADY_FIXED')),
  adopted_comment_id uuid,
  decided_by uuid, decided_at timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_ai_guard CHECK (guard_passed = (guard_reason IS NULL)),
  CONSTRAINT ck_ai_decision_visible CHECK (decision IS NULL OR guard_passed),
  CONSTRAINT ck_ai_dismiss CHECK (decision IS DISTINCT FROM 'DISMISSED' OR dismiss_reason IS NOT NULL)
);
CREATE INDEX ix_ai_finding_round ON qar.ai_finding(round_id) WHERE guard_passed;

-- ---------------------------------------------------------------------
-- 6) 앵커 코멘트 · 스레드 (FR-QAR-004, A6 코멘트 상태, BR-QAR-004)
-- ---------------------------------------------------------------------
CREATE TABLE qar.review_comment (
  id              uuid PRIMARY KEY,
  round_id        uuid NOT NULL REFERENCES qar.review_round(id),
  origin_round_no int NOT NULL,                    -- 최초 생성 라운드 (라운드 간 이월)
  carried_from    uuid REFERENCES qar.review_comment(id),
  author_id       uuid NOT NULL,
  severity        varchar(10) NOT NULL CHECK (severity IN ('CRITICAL','MAJOR','MINOR','INFO')),
  issue_code      varchar(30) NOT NULL CHECK (issue_code ~ '^QI-[A-Z]+$'),
  anchor          jsonb NOT NULL,
  anchor_status   varchar(10) NOT NULL DEFAULT 'OK' CHECK (anchor_status IN ('OK','REMAPPED','LOST')),
  body            text NOT NULL,
  status          varchar(25) NOT NULL CHECK (status IN ('OPEN','ANSWERED','RESOLUTION_REQUESTED','RESOLVED','WITHDRAWN')),
  ai_finding_id   uuid,
  resolved_by     uuid, resolved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_comment_open ON qar.review_comment(round_id, severity) WHERE status NOT IN ('RESOLVED','WITHDRAWN');
CREATE TABLE qar.comment_reply (
  id          uuid PRIMARY KEY,
  comment_id  uuid NOT NULL REFERENCES qar.review_comment(id),
  author_id   uuid NOT NULL,
  body        text NOT NULL,
  action      varchar(25) NOT NULL CHECK (action IN ('ANSWER','REQUEST_RESOLUTION','REOPEN','CONFIRM','WITHDRAW')),
  from_status varchar(25) NOT NULL,
  to_status   varchar(25) NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_reply_comment ON qar.comment_reply(comment_id, created_at);

-- ---------------------------------------------------------------------
-- 7) DB 계층 가드 (설계 B3 v0.2 / 07-IMPL V0706) — BR-QAR-001 · 006, NFR-QAR-03, TC-QAR-009 · 013
-- ---------------------------------------------------------------------
CREATE FUNCTION qar.fn_waiver_only_warning() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sev varchar(10);
BEGIN
  SELECT severity INTO sev FROM qar.autocheck_result WHERE id = NEW.autocheck_result_id;
  IF sev IS DISTINCT FROM 'WARNING' THEN
    RAISE EXCEPTION 'QAR_WAIVER_NOT_ALLOWED: severity=%', sev USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_waiver_only_warning BEFORE INSERT OR UPDATE OF autocheck_result_id ON qar.waiver
  FOR EACH ROW EXECUTE FUNCTION qar.fn_waiver_only_warning();

CREATE FUNCTION qar.fn_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'QAR_APPEND_ONLY: % (%)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'P0001';
END $$;
CREATE TRIGGER trg_reply_append_only  BEFORE UPDATE OR DELETE ON qar.comment_reply
  FOR EACH ROW EXECUTE FUNCTION qar.fn_append_only();
CREATE TRIGGER trg_result_append_only BEFORE UPDATE OR DELETE ON qar.autocheck_result
  FOR EACH ROW EXECUTE FUNCTION qar.fn_append_only();
CREATE TRIGGER trg_run_append_only    BEFORE UPDATE OR DELETE ON qar.autocheck_run
  FOR EACH ROW EXECUTE FUNCTION qar.fn_append_only();
CREATE TRIGGER trg_reply_truncate  BEFORE TRUNCATE ON qar.comment_reply
  FOR EACH STATEMENT EXECUTE FUNCTION qar.fn_append_only();
CREATE TRIGGER trg_result_truncate BEFORE TRUNCATE ON qar.autocheck_result
  FOR EACH STATEMENT EXECUTE FUNCTION qar.fn_append_only();
CREATE TRIGGER trg_run_truncate    BEFORE TRUNCATE ON qar.autocheck_run
  FOR EACH STATEMENT EXECUTE FUNCTION qar.fn_append_only();
