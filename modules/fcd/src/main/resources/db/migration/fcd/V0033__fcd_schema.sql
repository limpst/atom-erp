-- =====================================================================
-- 12 FCD 법인카드 관리 · 부정사용 탐지 — 스키마 (설계 12 B3 / 12-IMPL Step 1·2·3·5·7·8 일부)
-- 내장 PostgreSQL(확장 없음) 호환: PostGIS geom · pg_trgm · btree_gist EXCLUDE 대신 위경도 숫자 · 트리거로 구현.
-- PAN(카드번호 원문)은 어떤 컬럼에도 없다(BR-FCD-001): HMAC-SHA256 토큰 · 앞6 · 뒤4 만 저장.
-- 승인시각은 KST 현지시각(timestamp without time zone) — 명세서 · 자연키 분 단위 비교와 같은 기준.
-- 모든 예시 · 테스트 데이터는 합성 데이터.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS fcd;

-- ---------------------------------------------------------------- 카드
CREATE TABLE fcd.card (
  id                     uuid PRIMARY KEY,
  card_company           varchar(20) NOT NULL,
  card_token             char(64) NOT NULL UNIQUE,          -- HMAC-SHA256(key_v, PAN) hex
  token_key_version      smallint NOT NULL,
  first6                 char(6) NOT NULL,
  last4                  char(4) NOT NULL,
  masked_no              text GENERATED ALWAYS AS (first6 || '******' || last4) STORED,
  alias                  varchar(100),
  card_type              varchar(20) NOT NULL CHECK (card_type IN ('PERSONAL_ASSIGNED','SHARED')),
  holder_employee_id     uuid,
  dept                   varchar(100),
  limit_per_txn          numeric(15,0) NOT NULL,
  limit_daily            numeric(15,0) NOT NULL,
  limit_monthly          numeric(15,0) NOT NULL,
  valid_thru             date,
  status                 varchar(20) NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE','SUSPEND_REQUESTED','SUSPENDED','CLOSED')),
  closed_on              date,
  -- hrq.employee.terminated 수신 시 '정지 검토' 표시만(BR-FCD-014 자동 정지 금지 — 결제 차단 없음)
  deactivation_flag      boolean NOT NULL DEFAULT false,
  deactivation_reason    text,
  deactivation_flagged_at timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             uuid NOT NULL,
  updated_at             timestamptz NOT NULL DEFAULT now(),
  updated_by             uuid NOT NULL,
  version                bigint NOT NULL DEFAULT 0,
  CHECK ((card_type = 'SHARED') = (holder_employee_id IS NULL))
);
CREATE INDEX ix_card_mask ON fcd.card (card_company, first6, last4);
CREATE INDEX ix_card_holder ON fcd.card (holder_employee_id);

-- 공용카드 대여(BR-FCD-006) — 기간 중복 금지는 트리거(btree_gist EXCLUDE 대체)
CREATE TABLE fcd.card_assignment (
  id            uuid PRIMARY KEY,
  card_id       uuid NOT NULL REFERENCES fcd.card(id),
  employee_id   uuid NOT NULL,
  period_start  timestamp NOT NULL,
  period_end    timestamp NOT NULL,
  purpose       text NOT NULL,
  project_id    uuid,
  returned_at   timestamp,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid NOT NULL,
  CHECK (period_end > period_start)
);
CREATE FUNCTION fcd.forbid_assignment_overlap() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM fcd.card_assignment a WHERE a.card_id = NEW.card_id AND a.id <> NEW.id
             AND tsrange(a.period_start, a.period_end) && tsrange(NEW.period_start, NEW.period_end)) THEN
    RAISE EXCEPTION 'FCD-409-004 공용카드 대여 기간 중복' USING ERRCODE = 'exclusion_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_assignment_overlap BEFORE INSERT OR UPDATE ON fcd.card_assignment
  FOR EACH ROW EXECUTE FUNCTION fcd.forbid_assignment_overlap();

-- hrq.employee.terminated 읽기 모델(퇴사일) — R-CARD-INACTIVE 판정용
CREATE TABLE fcd.employee_status (
  employee_id  uuid PRIMARY KEY,
  emp_no       varchar(30),
  resigned_on  date,
  source       varchar(30),
  received_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 가맹점
CREATE TABLE fcd.merchant (
  id                  uuid PRIMARY KEY,
  biz_reg_no          varchar(20),
  name_raw            text NOT NULL,
  name_norm           text NOT NULL,
  card_category_name  text,
  mcc                 char(4),
  internal_category   varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  address             text,
  lat                 numeric(9,6),
  lon                 numeric(9,6),
  country             char(2) NOT NULL DEFAULT 'KR',
  is_online           boolean NOT NULL DEFAULT false,
  is_pg               boolean NOT NULL DEFAULT false,
  tax_type            varchar(20) NOT NULL DEFAULT 'GENERAL' CHECK (tax_type IN ('GENERAL','SIMPLIFIED','EXEMPT')),
  blacklisted         boolean NOT NULL DEFAULT false,
  related_party       boolean NOT NULL DEFAULT false,
  first_seen_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_merchant_bizno ON fcd.merchant (biz_reg_no) WHERE biz_reg_no IS NOT NULL;
CREATE INDEX ix_merchant_norm ON fcd.merchant (name_norm);

-- ---------------------------------------------------------------- 가져오기
CREATE TABLE fcd.import_profile (
  id                    uuid PRIMARY KEY,
  profile_code          varchar(60) NOT NULL UNIQUE,
  card_company          varchar(20) NOT NULL,
  file_type             varchar(10) NOT NULL CHECK (file_type IN ('CSV','XLSX','XLS')),
  kind                  varchar(10) NOT NULL CHECK (kind IN ('APPROVAL','PURCHASE','BILLING')),
  form_version          int NOT NULL DEFAULT 1,
  name                  text NOT NULL,
  header_signature      text[] NOT NULL,
  column_map            jsonb NOT NULL,
  date_format           varchar(30),
  cancel_mode           varchar(20) NOT NULL CHECK (cancel_mode IN ('NEGATIVE_AMOUNT','SEPARATE_ROW_FLAG','STATUS_COLUMN')),
  cancel_values         text[] NOT NULL DEFAULT '{취소,승인취소,매입취소}',
  partial_cancel_values text[] NOT NULL DEFAULT '{부분취소}',
  footer_stop_pattern   text DEFAULT '합계|소계|총계',
  active                boolean NOT NULL DEFAULT true,
  created_at            timestamptz NOT NULL DEFAULT now(),
  UNIQUE (card_company, file_type, form_version, kind)
);

CREATE TABLE fcd.import_batch (
  id                  uuid PRIMARY KEY,
  channel             varchar(10) NOT NULL CHECK (channel IN ('API','CSV','EXCEL')),
  profile_id          uuid REFERENCES fcd.import_profile(id),
  filename            text,
  file_sha256         char(64),
  status              varchar(30) NOT NULL CHECK (status IN ('UPLOADED','PARSED','VALIDATED','COMMITTED','DISCARDED',
                                                            'FAILED','ROLLED_BACK','PARTIALLY_ROLLED_BACK','NEEDS_MAPPING')),
  file_kind           varchar(15),
  detected_encoding   varchar(20),
  detected_delimiter  varchar(4),
  detected_header_row int,
  header_rows         int,
  profile_score       numeric(5,3),
  rows_total          int NOT NULL DEFAULT 0,
  rows_error          int NOT NULL DEFAULT 0,
  counts              jsonb NOT NULL DEFAULT '{}',
  warnings            jsonb NOT NULL DEFAULT '[]',
  detected_headers    jsonb,
  uploaded_by         uuid NOT NULL,
  uploaded_at         timestamptz NOT NULL DEFAULT now(),
  committed_at        timestamptz, committed_by uuid,
  rolled_back_at      timestamptz, rolled_back_by uuid, rollback_reason text
);
-- BR-FCD-004 동일 파일 재업로드 차단(폐기 · 실패 · 롤백 배치는 제외)
CREATE UNIQUE INDEX ux_batch_sha ON fcd.import_batch (file_sha256)
  WHERE file_sha256 IS NOT NULL AND status NOT IN ('DISCARDED','FAILED','ROLLED_BACK');

CREATE TABLE fcd.import_row_error (
  batch_id     uuid NOT NULL REFERENCES fcd.import_batch(id),
  row_no       int NOT NULL,
  column_name  text,
  code         varchar(30) NOT NULL,
  raw_value    text,
  message      text NOT NULL,
  PRIMARY KEY (batch_id, row_no, code)
);

-- 검증 통과 표준 레코드(커밋 대기) — PAN 은 마스킹 후에만 들어온다
CREATE TABLE fcd.import_staging (
  batch_id   uuid NOT NULL REFERENCES fcd.import_batch(id),
  row_no     int NOT NULL,
  canonical  jsonb NOT NULL,
  preview    varchar(20),
  PRIMARY KEY (batch_id, row_no)
);

-- ---------------------------------------------------------------- 정규화 거래 원장
CREATE TABLE fcd.txn (
  id                     uuid PRIMARY KEY,
  seq                    bigserial NOT NULL UNIQUE,         -- 원장 생성 순서(같은 시각 정렬 안정 키)
  card_id                uuid NOT NULL REFERENCES fcd.card(id),
  employee_id            uuid,
  attribution            varchar(12) NOT NULL DEFAULT 'RESOLVED' CHECK (attribution IN ('RESOLVED','UNASSIGNED')),
  approval_no            varchar(20),
  approved_at            timestamp NOT NULL,                -- KST 현지시각
  approved_at_precision  varchar(6) NOT NULL CHECK (approved_at_precision IN ('SECOND','MINUTE','DATE')),
  amount_krw             numeric(15,0) NOT NULL,            -- 확정 원화(매입 우선, BR-FCD-008)
  approval_amount_krw    numeric(15,0),
  cancelled_amount_krw   numeric(15,0) NOT NULL DEFAULT 0,
  currency               char(3) NOT NULL DEFAULT 'KRW',
  amount_foreign         numeric(15,2),
  fx_rate                numeric(12,4),
  is_online              boolean,
  country                char(2),
  merchant_id            uuid REFERENCES fcd.merchant(id),
  merchant_name          text NOT NULL,
  biz_reg_no             varchar(20),
  category_hint          text,
  pg_unresolved          boolean NOT NULL DEFAULT false,    -- R-137 PG 경유 · 실공급자 미치환
  status                 varchar(25) NOT NULL CHECK (status IN ('APPROVED','PURCHASED','PARTIALLY_CANCELLED','CANCELLED',
                                                                'UNPURCHASED','BILLED')),
  natural_key            text,                              -- token|승인번호|yyyyMMddHHmm|금액 (승인번호 · 분 정밀도 있을 때)
  created_batch_id       uuid REFERENCES fcd.import_batch(id),
  risk_score             smallint,
  risk_grade             varchar(10),
  project_id             uuid,
  removed_at             timestamptz,                       -- 배치 롤백 · 수동 병합으로 원장에서 제외(채점 이력 보존 위해 삭제 대신)
  removed_reason         text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  version                bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_txn_card_time ON fcd.txn (card_id, approved_at);
CREATE INDEX ix_txn_emp_time ON fcd.txn (employee_id, approved_at);
CREATE INDEX ix_txn_appr ON fcd.txn (card_id, approval_no);
CREATE INDEX ix_txn_merchant ON fcd.txn (merchant_id, approved_at);

CREATE TABLE fcd.txn_source (
  id            uuid PRIMARY KEY,
  seq           bigserial NOT NULL UNIQUE,                 -- 부착 순서(재도출 시 '먼저 온 원천')
  txn_id        uuid REFERENCES fcd.txn(id),               -- 고아 취소는 NULL
  channel       varchar(10) NOT NULL CHECK (channel IN ('API','CSV','EXCEL')),
  kind          varchar(10) NOT NULL CHECK (kind IN ('APPROVAL','CANCEL','PURCHASE','BILLING')),
  batch_id      uuid REFERENCES fcd.import_batch(id),
  row_no        int,
  canonical     jsonb NOT NULL,                            -- 표준 레코드(PAN 없음, 원문은 마스킹)
  raw_hash      char(64) NOT NULL,
  match_method  varchar(15) NOT NULL,                      -- NEW | NATURAL_KEY | APPROVAL_NO | FUZZY | MANUAL | CANCEL | ORPHAN
  match_score   numeric(5,4),
  cancel_key    text,                                      -- 취소 식별키(금액|일자) — 채널 간 중복 취소 방지
  detached      boolean NOT NULL DEFAULT false,            -- 롤백 · 병합해제로 분리됨
  received_at   timestamptz NOT NULL DEFAULT now()
);
-- 채널 내 동일 행 재수신 멱등(분리된 원천은 재업로드 허용)
CREATE UNIQUE INDEX ux_source_hash ON fcd.txn_source (channel, kind, raw_hash) WHERE NOT detached;
CREATE INDEX ix_source_txn ON fcd.txn_source (txn_id);
CREATE INDEX ix_source_batch ON fcd.txn_source (batch_id);

CREATE TABLE fcd.dedup_candidate (
  id          uuid PRIMARY KEY,
  source_id   uuid NOT NULL REFERENCES fcd.txn_source(id),
  txn_id      uuid NOT NULL REFERENCES fcd.txn(id),          -- 병합 후보 대상
  new_txn_id  uuid REFERENCES fcd.txn(id),                   -- 확인 전 별도 생성된 거래
  score       numeric(5,4) NOT NULL,
  features    jsonb NOT NULL,
  decision    varchar(10) CHECK (decision IN ('MERGE','SEPARATE')),
  decided_by  uuid,
  decided_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 룰셋 (시행일 · 4-eyes)
CREATE TABLE fcd.rule_set (
  id              uuid PRIMARY KEY,
  name            varchar(30) NOT NULL,
  version         varchar(40) NOT NULL,
  effective_from  date NOT NULL,
  effective_to    date,
  status          varchar(20) NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','RETIRED')),
  authored_by     varchar(60) NOT NULL,
  approved_by     varchar(60),
  approved_at     timestamptz,
  yaml            text NOT NULL,                           -- Python 프로토타입과 같은 YAML 원문
  sha256          char(64) NOT NULL,
  source          varchar(20) NOT NULL DEFAULT 'SEED',     -- SEED(클래스패스) | API
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (name, version),
  CHECK (approved_by IS NULL OR approved_by <> authored_by),   -- 4-eyes, BR-FCD-009 · TC-FCD-024
  CHECK (status <> 'ACTIVE' OR approved_by IS NOT NULL),
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ---------------------------------------------------------------- 채점 (append-only)
CREATE TABLE fcd.risk_score (
  id                uuid PRIMARY KEY,
  txn_id            uuid NOT NULL REFERENCES fcd.txn(id),
  scored_at         timestamptz NOT NULL DEFAULT now(),
  trigger           varchar(20) NOT NULL,       -- IMPORT | RESCORE | CONTEXT_CHANGED | RULESET_SIM
  rule_set_version  varchar(40) NOT NULL,
  model_version     varchar(60),
  score             smallint NOT NULL CHECK (score BETWEEN 0 AND 100),
  grade             varchar(10) NOT NULL,
  r                 double precision NOT NULL,
  r_mitigated       double precision NOT NULL,
  floor_score       smallint NOT NULL DEFAULT 0,
  hits              jsonb NOT NULL,
  mitigations       jsonb NOT NULL,
  flags             jsonb NOT NULL,
  contributions     jsonb NOT NULL,
  reasons           jsonb NOT NULL,
  features          jsonb NOT NULL,             -- 채점 시점 tx·ctx·l3 스냅샷(재현성 NFR-FCD-007)
  l3_status         varchar(10) NOT NULL        -- OK | TIMEOUT | DISABLED | ERROR
);
CREATE INDEX ix_risk_txn ON fcd.risk_score (txn_id, scored_at DESC);
CREATE FUNCTION fcd.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'FCD-409-001 % 는 append-only 입니다', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_risk_append_only BEFORE UPDATE OR DELETE ON fcd.risk_score
  FOR EACH ROW EXECUTE FUNCTION fcd.forbid_mutation();

-- 월 예산 검토 큐 실행 기록(top-k / 1,000건)
CREATE TABLE fcd.review_run (
  id           uuid PRIMARY KEY,
  period_ym    char(7) NOT NULL,
  policy       varchar(20) NOT NULL,
  k_per_1000   numeric(6,2) NOT NULL,
  n_txn        int NOT NULL,
  k_selected   int NOT NULL,
  min_score    int NOT NULL,
  selected     jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  created_by   uuid NOT NULL
);

-- ---------------------------------------------------------------- 케이스 · 소명 · 증빙 · 피드백
CREATE SEQUENCE fcd.case_no_seq;
CREATE TABLE fcd.fcd_case (
  id                   uuid PRIMARY KEY,
  case_no              varchar(20) NOT NULL UNIQUE,
  status               varchar(25) NOT NULL CHECK (status IN ('OPEN','EXPLANATION_REQUESTED','OVERDUE','SUBMITTED',
                       'SUPPLEMENT_REQUESTED','ESCALATED','RESOLVED_LEGIT','RESOLVED_MISUSE','RECOVERY','CLOSED')),
  grade                varchar(10) NOT NULL,
  score                smallint NOT NULL,
  subject_employee_id  uuid,
  opened_reason        text NOT NULL,                 -- 판정 전 "부정" 단정 금지(F12)
  opened_at            timestamptz NOT NULL DEFAULT now(),
  due_on               date NOT NULL,
  audit_sample         boolean NOT NULL DEFAULT false,
  score_dropped        boolean NOT NULL DEFAULT false,
  suspension_candidate boolean NOT NULL DEFAULT false,
  assignee_id          uuid,
  label                varchar(20) CHECK (label IN ('LEGIT','FALSE_POSITIVE','MINOR_VIOLATION','MISUSE')),
  resolution           text,
  resolved_by          uuid,
  resolved_at          timestamptz,
  recovery_amount      numeric(15,0),
  version              bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_case_subject ON fcd.fcd_case (subject_employee_id, status);
CREATE TABLE fcd.case_txn (
  case_id uuid NOT NULL REFERENCES fcd.fcd_case(id),
  txn_id  uuid NOT NULL REFERENCES fcd.txn(id),
  PRIMARY KEY (case_id, txn_id)
);
CREATE UNIQUE INDEX ux_case_txn_once ON fcd.case_txn (txn_id);
CREATE TABLE fcd.case_event (
  id          uuid PRIMARY KEY,
  case_id     uuid NOT NULL REFERENCES fcd.fcd_case(id),
  from_status varchar(25),
  event       varchar(30) NOT NULL,
  to_status   varchar(25) NOT NULL,
  actor_id    uuid NOT NULL,
  note        text,
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_case_event_append_only BEFORE UPDATE OR DELETE ON fcd.case_event
  FOR EACH ROW EXECUTE FUNCTION fcd.forbid_mutation();
CREATE TABLE fcd.explanation (
  id            uuid PRIMARY KEY,
  case_id       uuid NOT NULL REFERENCES fcd.fcd_case(id),
  author_id     uuid NOT NULL,
  purpose_code  varchar(30) NOT NULL,
  text          text NOT NULL,
  attendees     jsonb,
  submitted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE fcd.evidence (
  id          uuid PRIMARY KEY,
  case_id     uuid REFERENCES fcd.fcd_case(id),
  txn_id      uuid REFERENCES fcd.txn(id),
  type        varchar(20) NOT NULL CHECK (type IN ('RECEIPT','ATTENDEE_LIST','APPROVAL_DOC','ETC')),
  object_key  text NOT NULL,
  sha256      char(64) NOT NULL,
  ocr         jsonb,
  ocr_match   jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid NOT NULL
);
CREATE UNIQUE INDEX ux_evidence_sha ON fcd.evidence (sha256, type);   -- 같은 영수증 이미지 재제출 탐지 보조
CREATE TABLE fcd.feedback (
  id            uuid PRIMARY KEY,
  txn_id        uuid NOT NULL REFERENCES fcd.txn(id),
  case_id       uuid REFERENCES fcd.fcd_case(id),
  label         varchar(20) NOT NULL CHECK (label IN ('LEGIT','FALSE_POSITIVE','MINOR_VIOLATION','MISUSE')),
  reviewer_id   uuid NOT NULL,
  reason        text,
  audit_sample  boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 경비 정산 · 전표(CST 연동)
CREATE TABLE fcd.expense_report (
  id            uuid PRIMARY KEY,
  employee_id   uuid NOT NULL,
  period        char(7) NOT NULL,                              -- YYYY-MM
  status        varchar(15) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','POSTED','REVERSED')),
  submitted_at  timestamptz,
  approved_by   uuid,
  approved_at   timestamptz,
  posted_at     timestamptz,
  version       bigint NOT NULL DEFAULT 0,
  UNIQUE (employee_id, period)
);
CREATE TABLE fcd.expense_line (
  id              uuid PRIMARY KEY,
  report_id       uuid NOT NULL REFERENCES fcd.expense_report(id),
  txn_id          uuid NOT NULL UNIQUE REFERENCES fcd.txn(id),
  account_code    varchar(30) NOT NULL,
  cost_element    varchar(20) NOT NULL,                         -- CST 원가요소(TRAVEL·LODGING·VEHICLE·OTHER …)
  purpose         varchar(30) NOT NULL,
  project_id      uuid,
  wbs_code        varchar(50),
  supply_amount   numeric(15,0) NOT NULL CHECK (supply_amount >= 0),
  vat_amount      numeric(15,0) NOT NULL DEFAULT 0 CHECK (vat_amount >= 0),
  vat_deductible  boolean NOT NULL,                             -- ⚠️ 세무 확인 필요
  attendees       int,
  sync_status     varchar(15) NOT NULL DEFAULT 'PENDING' CHECK (sync_status IN ('PENDING','POSTED','SYNCED','FAILED',
                                                                              'REVERSE_PENDING','REVERSED')),
  voucher_no      varchar(40),
  synced_at       timestamptz
);
