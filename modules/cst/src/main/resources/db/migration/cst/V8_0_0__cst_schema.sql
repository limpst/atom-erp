-- =====================================================================
-- V8_0_0 — cst 스키마 (설계 08 v0.4 B3, 08-IMPL Step 1·3·6·11 일부)
--   * 버전 대역 V8_x = 모듈 08 (core V1, fac V2, hrq V3 …). 설계 IMPL 의 V300~V308 9개 파일 대신 1개 파일
--   * 금액은 numeric 만(부동소수 금지, NFR-CST-02). KRW 는 numeric(18,0), 외화 원금은 numeric(18,2)
--   * 회계월은 char(6) YYYYMM (hrq.timesheet_entry.posting_period 와 동일 형식)
--   * 시설물 참조는 fac.building(설계 v0.4 정본 테이블명) — core.project 에 facility_id 가 없어 cst.project_facility 로 연결
--   * 공통 6컬럼 version 은 core 와 같은 bigint
--   * 이번 단계 범위: 요율·실단가·WBS·실행예산·원장·회계기간·대사·진척·EVM·전표동기화 기록·수익성 MV
--     (경비 전표·기성·청구·수금·환율·공동도급 테이블은 Step 7~13 에서 추가)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS cst;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- 원가요소 (설계 B3)
CREATE TYPE cst.cost_element AS ENUM (
  'LABOR','TRAVEL','LODGING','VEHICLE','EQUIP_USE','MATERIAL_TEST',
  'DRONE','SUBCONTRACT','PRINTING','OVERHEAD','TECH_FEE','OTHER');

-- ---------------------------------------------------------------------
-- 기준 요율 (BR-CST-005, 유효기간 [valid_from, valid_to)) ⚠️ 법령 최신본 확인 필요 — 시드 없음(ADMIN 화면 입력)
--   STD_LABOR  : 등급별 표준 노임 일 단가(KRW/인·일)
--   EQUIP_DAILY: 장비 일 손료(KRW/일), OVERHEAD_PCT·TECHFEE_PCT: 요율(%)
-- ---------------------------------------------------------------------
CREATE TABLE cst.rate_table (
  id          uuid PRIMARY KEY,
  rate_kind   varchar(30) NOT NULL CHECK (rate_kind IN ('STD_LABOR','OVERHEAD_PCT','TECHFEE_PCT','EQUIP_DAILY')),
  key_code    varchar(50) NOT NULL,
  value       numeric(18,4) NOT NULL CHECK (value >= 0),
  valid_from  date NOT NULL,
  valid_to    date,
  source_ref  text NOT NULL,                 -- 근거 고시명·공표일 (필수, 08-IMPL Step 3)
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_rate_period CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT rate_table_no_overlap EXCLUDE USING gist
    (rate_kind WITH =, key_code WITH =, daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[)') WITH &&)
);

-- 실단가(개인별 일 원가, 더존 급여 월 수신) — ADMIN·EXEC 전용 (BR-CST-004, NFR-CST-04)
-- ⚠️ pgcrypto 컬럼 암호화는 Step 14 — 현재는 평문 numeric + API 열 단위 마스킹
CREATE TABLE cst.employee_cost_rate (
  employee_id    uuid NOT NULL REFERENCES core.employee(id),
  period_ym      char(6) NOT NULL CHECK (period_ym ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  daily_cost_krw numeric(14,0) NOT NULL CHECK (daily_cost_krw >= 0),
  source         varchar(20) NOT NULL DEFAULT 'DOUZONE_PAYROLL',
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  PRIMARY KEY (employee_id, period_ym)
);

-- 프로젝트 ↔ 시설물(fac.building) 연결 — core.project 에 facility_id 가 없어 CST 쪽 연결표로 둔다
CREATE TABLE cst.project_facility (
  project_id  uuid NOT NULL REFERENCES core.project(id),
  building_id uuid NOT NULL REFERENCES fac.building(id),
  is_primary  boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  PRIMARY KEY (project_id, building_id)
);
CREATE UNIQUE INDEX ux_project_facility_primary ON cst.project_facility (project_id) WHERE is_primary;

-- ---------------------------------------------------------------------
-- WBS · 실행예산 (BR-CST-001: 프로젝트당 APPROVED 1개)
-- ---------------------------------------------------------------------
CREATE TABLE cst.wbs_node (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES core.project(id),
  parent_id       uuid REFERENCES cst.wbs_node(id),
  code            varchar(30) NOT NULL,
  name            varchar(100) NOT NULL,
  weight_pct      numeric(5,2) NOT NULL CHECK (weight_pct BETWEEN 0 AND 100),
  milestone_event varchar(80),
  sort_order      int NOT NULL,
  UNIQUE (project_id, code)
);

CREATE TABLE cst.budget_revision (
  id          uuid PRIMARY KEY,
  project_id  uuid NOT NULL REFERENCES core.project(id),
  rev_no      int NOT NULL CHECK (rev_no >= 0),
  status      varchar(15) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','REVIEWED','APPROVED','SUPERSEDED','CLOSED')),
  bac_krw     numeric(18,0) NOT NULL DEFAULT 0,
  contract_amount_krw numeric(18,0) NOT NULL,
  target_margin_pct   numeric(6,2) NOT NULL DEFAULT 15,
  expected_margin_pct numeric(6,2) GENERATED ALWAYS AS
     (CASE WHEN contract_amount_krw = 0 THEN 0
           ELSE round((contract_amount_krw - bac_krw) * 100.0 / contract_amount_krw, 2) END) STORED,
  change_reason text,
  approved_by uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  UNIQUE (project_id, rev_no)
);
CREATE UNIQUE INDEX ux_budget_one_approved ON cst.budget_revision (project_id) WHERE status = 'APPROVED';

CREATE TABLE cst.budget_line (
  id            uuid PRIMARY KEY,
  revision_id   uuid NOT NULL REFERENCES cst.budget_revision(id) ON DELETE CASCADE,
  wbs_id        uuid NOT NULL REFERENCES cst.wbs_node(id),
  cost_element  cst.cost_element NOT NULL,
  grade_code    varchar(10),
  qty           numeric(10,2) NOT NULL,
  unit_cost_krw numeric(14,0) NOT NULL,
  amount_krw    numeric(18,0) GENERATED ALWAYS AS (round(qty * unit_cost_krw)) STORED,
  planned_start date,
  planned_end   date,
  pv_curve      varchar(12) NOT NULL DEFAULT 'LINEAR' CHECK (pv_curve IN ('LINEAR','FRONT_LOADED')),
  memo          text,
  CONSTRAINT ck_budget_line_period CHECK (planned_end IS NULL OR planned_start IS NULL OR planned_end >= planned_start)
);
CREATE INDEX ix_budget_line_rev ON cst.budget_line (revision_id);

-- ---------------------------------------------------------------------
-- 회계기간 (BR-CST-012). hrq_* = HRQ 월 마감(hrq.manday.closed) 수신 기록 — 잠금 확인·대사 전용
-- ---------------------------------------------------------------------
CREATE TABLE cst.accounting_period (
  period_ym     char(6) PRIMARY KEY CHECK (period_ym ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  status        varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSING','CLOSED','REOPENED')),
  closed_by     uuid, closed_at timestamptz,
  reopened_by   uuid, reopened_at timestamptz, reopen_reason text,
  hrq_closed_at timestamptz, hrq_close_event_id uuid
);

-- ---------------------------------------------------------------------
-- 원가 원장: append-only, 월 LIST 파티션 (BR-CST-003, NFR-CST-05)
--   멱등: UNIQUE NULLS NOT DISTINCT (source_type, source_id, period_ym, reversal_of) — reversal_of NULL 행도 중복 차단(PG15+)
--   설계 B3 대비 추가 컬럼: grade_code·work_type·hours(인·일 대사 TC-HRQ-010), flags, source_ref(업무 멱등키)
-- ---------------------------------------------------------------------
CREATE TABLE cst.cost_ledger_entry (
  id             uuid NOT NULL,
  project_id     uuid NOT NULL,
  wbs_id         uuid,
  posting_date   date NOT NULL,
  period_ym      char(6) NOT NULL CHECK (period_ym ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
  source_type    varchar(20) NOT NULL
                 CHECK (source_type IN ('TIMESHEET','EXPENSE','EQUIP','SUBCON','ADJUST','REVERSAL')),
  source_id      uuid NOT NULL,
  cost_element   cst.cost_element NOT NULL,
  employee_id    uuid,
  grade_code     varchar(10),
  work_type      varchar(15),
  hours          numeric(6,2),
  qty            numeric(12,4),
  amount_krw     numeric(18,0) NOT NULL,       -- 실단가 기준
  amount_std_krw numeric(18,0) NOT NULL,       -- 표준단가 기준
  orig_currency  char(3) NOT NULL DEFAULT 'KRW',
  orig_amount    numeric(18,2) NOT NULL,
  fx_rate        numeric(14,6) NOT NULL DEFAULT 1,
  reversal_of    uuid,
  source_ref     varchar(120),
  flags          text[] NOT NULL DEFAULT '{}',
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     uuid NOT NULL,
  PRIMARY KEY (id, period_ym),
  CONSTRAINT ck_ledger_reversal CHECK ((source_type = 'REVERSAL') = (reversal_of IS NOT NULL)),
  CONSTRAINT ux_ledger_idem UNIQUE NULLS NOT DISTINCT (source_type, source_id, period_ym, reversal_of)
) PARTITION BY LIST (period_ym);
CREATE INDEX ix_ledger_proj ON cst.cost_ledger_entry (project_id, posting_date);
CREATE INDEX ix_ledger_source ON cst.cost_ledger_entry (source_id, source_type);
CREATE INDEX ix_ledger_reversal ON cst.cost_ledger_entry (reversal_of) WHERE reversal_of IS NOT NULL;
CREATE INDEX ix_ledger_emp ON cst.cost_ledger_entry (employee_id, posting_date) WHERE employee_id IS NOT NULL;
-- 안전망: 사전 생성 범위 밖 월(이관 등). 앱은 전기 전 ensure_ledger_partition 으로 월 파티션을 보장한다
CREATE TABLE cst.cost_ledger_entry_default PARTITION OF cst.cost_ledger_entry DEFAULT;

-- 1) 수정·삭제 금지 (BR-CST-003, TC-CST-04)
CREATE FUNCTION cst.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'CST-409-001 cost_ledger_entry is append-only (op=%)', TG_OP
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_ledger_immutable BEFORE UPDATE OR DELETE ON cst.cost_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION cst.forbid_mutation();

-- 2) 마감 월 INSERT 차단 (NFR-CST-05, BR-CST-012)
CREATE FUNCTION cst.forbid_closed_period() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st varchar(10);
BEGIN
  SELECT status INTO st FROM cst.accounting_period WHERE period_ym = NEW.period_ym;
  IF st = 'CLOSED' THEN
    RAISE EXCEPTION 'CST-409-002 period % is closed', NEW.period_ym USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_ledger_period_guard BEFORE INSERT ON cst.cost_ledger_entry
  FOR EACH ROW EXECUTE FUNCTION cst.forbid_closed_period();

-- 월 파티션 + 회계기간 보장 (08-IMPL R__cst_partitions 를 함수로)
CREATE FUNCTION cst.ensure_ledger_partition(p_ym char(6)) RETURNS void LANGUAGE plpgsql AS $$
DECLARE part text := format('cost_ledger_entry_%s', p_ym);
BEGIN
  IF p_ym !~ '^[0-9]{4}(0[1-9]|1[0-2])$' THEN
    RAISE EXCEPTION 'CST-422-001 invalid period %', p_ym USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'cst' AND c.relname = part) THEN
    EXECUTE format('CREATE TABLE cst.%I PARTITION OF cst.cost_ledger_entry FOR VALUES IN (%L)', part, p_ym);
  END IF;
  INSERT INTO cst.accounting_period(period_ym, status) VALUES (p_ym, 'OPEN') ON CONFLICT (period_ym) DO NOTHING;
END $$;

DO $$
DECLARE d date := date_trunc('month', now() - interval '36 months');
BEGIN
  WHILE d <= date_trunc('month', now() + interval '24 months') LOOP
    PERFORM cst.ensure_ledger_partition(to_char(d, 'YYYYMM')::char(6));
    d := d + interval '1 month';
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- HRQ 월 마감 대사 결과 (BR-HRQ-013: 원장 재기록 금지, 비교·기록만) — TC-HRQ-010
-- ---------------------------------------------------------------------
CREATE TABLE cst.manday_reconciliation (
  id                 uuid PRIMARY KEY,
  period_ym          char(6) NOT NULL,
  source_event_id    uuid NOT NULL UNIQUE,
  source_hash        char(64) NOT NULL,             -- sha256(payload) — 같은 마감 내용의 중복 수신(새 eventId) 멱등
  hrq_total_mandays  numeric(12,2) NOT NULL,
  cst_total_mandays  numeric(12,2) NOT NULL,
  diff_mandays       numeric(12,2) NOT NULL,
  status             varchar(10) NOT NULL CHECK (status IN ('MATCHED','MISMATCH')),
  detail             jsonb NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_recon_payload UNIQUE (period_ym, source_hash)
);
CREATE INDEX ix_recon_period ON cst.manday_reconciliation (period_ym, created_at);

-- ---------------------------------------------------------------------
-- 진척 · EVM 스냅샷 (FR-CST-009·010)
-- ---------------------------------------------------------------------
CREATE TABLE cst.progress_record (
  id           uuid PRIMARY KEY,
  project_id   uuid NOT NULL REFERENCES core.project(id),
  wbs_id       uuid NOT NULL REFERENCES cst.wbs_node(id),
  as_of        date NOT NULL,
  pct_complete numeric(5,2) NOT NULL CHECK (pct_complete BETWEEN 0 AND 100),
  source       varchar(10) NOT NULL CHECK (source IN ('EVENT','MANUAL')),
  source_event varchar(80), reason text,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_progress_manual_reason CHECK (source <> 'MANUAL' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);
CREATE INDEX ix_progress_wbs ON cst.progress_record (wbs_id, as_of DESC, created_at DESC);

CREATE TABLE cst.evm_snapshot (
  project_id uuid NOT NULL REFERENCES core.project(id), as_of date NOT NULL,
  bac numeric(18,0), pv numeric(18,0), ev numeric(18,0), ac numeric(18,0), ac_std numeric(18,0),
  cv numeric(18,0), sv numeric(18,0), cpi numeric(6,3), spi numeric(6,3),
  eac numeric(18,0), etc numeric(18,0), vac numeric(18,0), tcpi numeric(6,3),
  alert_level varchar(6) CHECK (alert_level IN ('GREEN','YELLOW','RED')),
  alerts jsonb NOT NULL DEFAULT '[]',
  computed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, as_of)
);

-- ---------------------------------------------------------------------
-- 전표 동기화 기록 (FCD 경비 → CST 원장 → 더존 전표, cst.voucher.synced 발행 근거) — 더존 Adapter 는 스텁
-- ---------------------------------------------------------------------
CREATE TABLE cst.voucher_sync (
  id                uuid PRIMARY KEY,
  expense_report_id uuid NOT NULL,
  line_ids          uuid[] NOT NULL,
  kind              varchar(10) NOT NULL CHECK (kind IN ('POSTED','REVERSED')),
  period_ym         char(6) NOT NULL,
  douzone_doc_no    varchar(40),
  status            varchar(10) NOT NULL CHECK (status IN ('SYNCED','FAILED')),
  error             text,
  created_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 수익성 읽기 모델 (CQRS, 설계 B3 v0.4: fac.building 참조). 청구(billed)는 invoice 테이블(Step 8) 이후 추가
-- ---------------------------------------------------------------------
CREATE MATERIALIZED VIEW cst.mv_project_profitability AS
SELECT p.id AS project_id, p.project_no, p.client_id, p.service_type,
       b.main_purpose_nm AS facility_type,
       br.contract_amount_krw, br.bac_krw,
       coalesce(l.ac_krw, 0)    AS ac_krw,
       coalesce(l.ac_std_krw, 0) AS ac_std_krw,
       coalesce(l.labor_krw, 0) AS labor_krw,
       coalesce(l.mandays, 0)   AS mandays
  FROM core.project p
  JOIN LATERAL (SELECT r.contract_amount_krw, r.bac_krw FROM cst.budget_revision r
                 WHERE r.project_id = p.id AND r.status IN ('APPROVED','CLOSED') AND r.deleted_at IS NULL
                 ORDER BY r.rev_no DESC LIMIT 1) br ON true
  LEFT JOIN cst.project_facility pf ON pf.project_id = p.id AND pf.is_primary
  LEFT JOIN fac.building b ON b.id = pf.building_id
  LEFT JOIN (SELECT project_id, sum(amount_krw) AS ac_krw, sum(amount_std_krw) AS ac_std_krw,
                    sum(amount_krw) FILTER (WHERE cost_element = 'LABOR') AS labor_krw,
                    sum(qty) FILTER (WHERE cost_element = 'LABOR') AS mandays
               FROM cst.cost_ledger_entry GROUP BY project_id) l ON l.project_id = p.id;
CREATE UNIQUE INDEX ux_mv_profitability ON cst.mv_project_profitability (project_id);
