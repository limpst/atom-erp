-- =====================================================================
-- SAL 수주·영업 스키마 (설계 01 v0.3 B3 / 01-IMPL Step 1)
--   * 01-IMPL 의 V9_0_0~V9_0_5 6개 파일 대신 V0015(스키마)·V0016(시드) 2개 — 다른 모듈과 같은 방식
--   * 내장 PostgreSQL 에 pg_trgm 이 없어 bid_notice 트라이그램 인덱스는 생략(tsvector 만)
--   * core.rule_set 은 PostGIS 확장 프로필(migration-ext)에만 있어 대가 규칙·적격심사 기준은 sal 전용
--     유효기간 테이블(fee_rule_set · qual_criteria)로 둔다(ACTIVE 기간 겹침은 EXCLUDE 로 거부)
--   * 공통 6컬럼 version 은 bigint (FAC·HRQ·EQP 와 동일)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS sal;

-- ---------------------------------------------------------------------
-- 1) 공고 · 저장 필터 · 개찰결과 · 수집 이력 (FR-SAL-001·002, BR-SAL-001·013)
-- ---------------------------------------------------------------------
CREATE TABLE sal.bid_notice (
  id               uuid PRIMARY KEY,
  source           varchar(10)  NOT NULL DEFAULT 'G2B',
  bid_no           varchar(40)  NOT NULL,          -- bidNtceNo
  bid_seq          varchar(5)   NOT NULL,          -- bidNtceOrd
  title            varchar(500) NOT NULL,
  org_name         varchar(200),
  demand_org_name  varchar(200),
  contract_method  varchar(50),
  award_method     varchar(100),
  notice_kind      varchar(30),                    -- 등록/정정/취소 (ntceKindNm) ⚠️ 명세 확인 필요
  est_price        numeric(15,0),
  base_price       numeric(15,0),
  region_limit     varchar(200),
  industry_codes   text[],
  notice_at        timestamptz,
  open_at          timestamptz,
  close_at         timestamptz,
  notice_url       varchar(500),
  raw_json         jsonb NOT NULL,                 -- 원본 응답 보존(명세 변경 시 재파싱)
  fit_score        smallint CHECK (fit_score BETWEEN 0 AND 100),
  fit_breakdown    jsonb,
  status           varchar(15) NOT NULL DEFAULT 'NEW'
                   CHECK (status IN ('NEW','WATCHING','IGNORED','CONVERTED','SUPERSEDED','CANCELLED')),
  superseded_by    uuid REFERENCES sal.bid_notice(id),
  title_tsv        tsvector GENERATED ALWAYS AS (to_tsvector('simple', title)) STORED,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ux_bid UNIQUE (source, bid_no, bid_seq)
);
CREATE INDEX ix_bid_close ON sal.bid_notice (close_at) WHERE status IN ('NEW','WATCHING');
CREATE INDEX ix_bid_tsv ON sal.bid_notice USING gin (title_tsv);

CREATE TABLE sal.bid_filter (
  id               uuid PRIMARY KEY,
  owner_id         uuid NOT NULL,
  name             varchar(100) NOT NULL,
  include_kw       text[] NOT NULL DEFAULT '{}',
  exclude_kw       text[] NOT NULL DEFAULT '{}',
  regions          text[],
  min_price        numeric(15,0),
  max_price        numeric(15,0),
  notify           boolean NOT NULL DEFAULT true,
  notify_min_score smallint NOT NULL DEFAULT 60
);

CREATE TABLE sal.bid_result (
  id             uuid PRIMARY KEY,
  bid_notice_id  uuid NOT NULL REFERENCES sal.bid_notice(id),
  rank           int,
  company_name   varchar(200) NOT NULL,
  biz_reg_no     varchar(12),
  bid_amount     numeric(15,0),
  bid_rate       numeric(7,4),
  is_winner      boolean NOT NULL DEFAULT false,
  is_self        boolean NOT NULL DEFAULT false,
  collected_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_bid_result_notice ON sal.bid_result (bid_notice_id);

-- BR-SAL-013: 다음 수집 구간 = 직전 성공 실행 range_to − 10분 ~ 현재 (실패 구간 자동 백필)
CREATE TABLE sal.collect_log (
  id             bigserial PRIMARY KEY,
  job_type       varchar(20) NOT NULL CHECK (job_type IN ('NOTICE','RESULT','BACKFILL')),
  range_from     timestamptz NOT NULL,
  range_to       timestamptz NOT NULL,
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  success        boolean,
  fetched_count  int NOT NULL DEFAULT 0,
  upserted_count int NOT NULL DEFAULT 0,
  new_count      int NOT NULL DEFAULT 0,
  error          text,
  CONSTRAINT ck_collect_range CHECK (range_to >= range_from)
);
CREATE INDEX ix_collect_log_last_success ON sal.collect_log (job_type, range_to DESC) WHERE success;

-- ---------------------------------------------------------------------
-- 2) 기회 · 단계 이력 · 활동 (FR-SAL-014–016, BR-SAL-007·010)
--    owner_id 는 LEAD(자동 리드 — 담당 미배정)·DROPPED 에서만 비울 수 있다(A6.1 "LEAD → QUALIFYING: 담당 배정")
-- ---------------------------------------------------------------------
CREATE TABLE sal.opportunity (
  id               uuid PRIMARY KEY,
  opp_no           varchar(20) NOT NULL UNIQUE,      -- OPP-2026-0001
  source           varchar(15) NOT NULL CHECK (source IN ('G2B','DUE_SOON','INQUIRY','REFERRAL','RENEWAL','PORTAL')),
  source_ref       varchar(80),                       -- PORTAL: serviceRequestId (멱등)
  bid_notice_id    uuid REFERENCES sal.bid_notice(id),
  client_id        uuid REFERENCES core.client(id),
  facility_id      uuid,                              -- fac.building (논리 FK, 모듈 경계)
  due_inspection_type varchar(30),
  due_date         date,
  service_type     varchar(30) NOT NULL,
  title            varchar(300) NOT NULL,
  stage            varchar(15) NOT NULL DEFAULT 'LEAD'
                   CHECK (stage IN ('LEAD','QUALIFYING','PROPOSING','NEGOTIATING','BIDDING',
                                    'BID_APPROVED','SUBMITTED','WON','LOST','DROPPED','CONTRACTED')),
  expected_amount  numeric(15,0),
  probability      smallint CHECK (probability BETWEEN 0 AND 100),
  expected_close   date,
  owner_id         uuid REFERENCES core.employee(id),
  lost_reason      varchar(20) CHECK (lost_reason IN ('PRICE','PERFORMANCE','STAFF','SCHEDULE','COMPETITOR','OTHER')),
  lost_note        text,
  criteria_changed boolean NOT NULL DEFAULT false,    -- A8-2 정정공고 "기준 변경됨" 배지
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ck_lost_reason CHECK (stage <> 'LOST' OR lost_reason IS NOT NULL),
  CONSTRAINT ck_opp_owner CHECK (owner_id IS NOT NULL OR stage IN ('LEAD','DROPPED'))
);
-- BR-SAL-007: 점검 도래 리드 중복 방지
CREATE UNIQUE INDEX ux_opp_due ON sal.opportunity (facility_id, due_inspection_type, due_date)
  WHERE source = 'DUE_SOON' AND deleted_at IS NULL;
CREATE UNIQUE INDEX ux_opp_portal ON sal.opportunity (source_ref) WHERE source = 'PORTAL' AND deleted_at IS NULL;
CREATE INDEX ix_opp_stage_owner ON sal.opportunity (stage, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_opp_facility ON sal.opportunity (facility_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_opp_notice ON sal.opportunity (bid_notice_id) WHERE bid_notice_id IS NOT NULL;

CREATE TABLE sal.opportunity_stage_history (
  id              bigserial PRIMARY KEY,
  opportunity_id  uuid NOT NULL REFERENCES sal.opportunity(id),
  from_stage      varchar(15),
  to_stage        varchar(15) NOT NULL,
  changed_by      uuid NOT NULL,
  changed_at      timestamptz NOT NULL DEFAULT now(),
  note            text
);
CREATE INDEX ix_stage_history_opp ON sal.opportunity_stage_history (opportunity_id, id);

CREATE TABLE sal.activity (
  id              uuid PRIMARY KEY,
  opportunity_id  uuid REFERENCES sal.opportunity(id),
  client_id       uuid REFERENCES core.client(id),
  activity_type   varchar(10) NOT NULL CHECK (activity_type IN ('CALL','VISIT','EMAIL','MEETING','NOTE')),
  occurred_at     timestamptz NOT NULL,
  summary         text NOT NULL,
  next_action     varchar(300),
  next_action_at  date,
  owner_id        uuid NOT NULL
);
CREATE INDEX ix_activity_opp ON sal.activity (opportunity_id, occurred_at);

-- 영업팀장 그룹 (BR-SAL-006 SALES_LEAD 한도 10%) — 12역할 체계에 없는 "그룹"이라 역할 대신 소속표로 둔다
CREATE TABLE sal.sales_group_member (
  employee_id  uuid NOT NULL,
  group_code   varchar(20) NOT NULL CHECK (group_code IN ('SALES_LEAD')),
  PRIMARY KEY (employee_id, group_code)
);

-- ---------------------------------------------------------------------
-- 3) 대가 규칙(FEE_STANDARD) · 적격심사 기준(QUALIFICATION_CRITERIA) — 유효기간 관리 (FR-SAL-010, BR-SAL-002)
--    ACTIVE 행은 기간이 겹칠 수 없다. 운영 활성화 전 verified_by(검증자 서명) 필수 — 시드는 ⚠️ 예시라 NULL
-- ---------------------------------------------------------------------
CREATE TABLE sal.fee_rule_set (
  id           uuid PRIMARY KEY,
  rule_version varchar(20) NOT NULL UNIQUE,
  valid_from   date NOT NULL,
  valid_to     date,
  status       varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  body         jsonb NOT NULL,
  legal_basis  text NOT NULL,
  verified_by  uuid,
  verified_at  timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_fee_rule_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT ex_fee_rule_active EXCLUDE USING gist (
    daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]') WITH &&) WHERE (status = 'ACTIVE')
);

CREATE TABLE sal.qual_criteria (
  id            uuid PRIMARY KEY,
  criteria_code varchar(50) NOT NULL,
  valid_from    date NOT NULL,
  valid_to      date,
  status        varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  body          jsonb NOT NULL,
  verified_by   uuid,
  verified_at   timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ux_qual_criteria UNIQUE (criteria_code, valid_from)
);

-- ---------------------------------------------------------------------
-- 4) 적격심사 시뮬레이션 · 입찰 품의 (FR-SAL-006·007, BR-SAL-014)
-- ---------------------------------------------------------------------
CREATE TABLE sal.qual_simulation (
  id                uuid PRIMARY KEY,
  opportunity_id    uuid NOT NULL REFERENCES sal.opportunity(id),
  criteria_id       uuid NOT NULL REFERENCES sal.qual_criteria(id),
  inputs            jsonb NOT NULL,        -- 실적(performance_record 만)·인력·신용 원천값 스냅샷
  capability_scores jsonb NOT NULL,
  scenarios         jsonb NOT NULL,        -- [{bidRate, priceScore, total, pass}]
  selected_bid_rate numeric(7,4),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL
);

CREATE TABLE sal.bid_decision (
  id                uuid PRIMARY KEY,
  opportunity_id    uuid NOT NULL REFERENCES sal.opportunity(id),
  simulation_id     uuid REFERENCES sal.qual_simulation(id),
  expected_cost     numeric(15,0),
  expected_margin   numeric(7,2),
  status            varchar(12) NOT NULL DEFAULT 'SUBMITTED'
                    CHECK (status IN ('SUBMITTED','APPROVED','CONDITIONAL','REJECTED')),
  approver_id       uuid,
  decided_at        timestamptz,
  comment           text,
  submitted_bid_amount numeric(15,0),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_bid_after_approval CHECK (submitted_bid_amount IS NULL OR status IN ('APPROVED','CONDITIONAL'))  -- BR-SAL-009
);

-- ---------------------------------------------------------------------
-- 5) 견적 · 산출내역 (FR-SAL-009–012, BR-SAL-002·005·006, NFR-SAL-05)
-- ---------------------------------------------------------------------
CREATE TABLE sal.estimate (
  id               uuid PRIMARY KEY,
  estimate_no      varchar(20) NOT NULL,              -- EST-2026-0087 (기회당 1개, 버전 증가)
  version_no       int NOT NULL DEFAULT 1,
  opportunity_id   uuid NOT NULL REFERENCES sal.opportunity(id),
  service_type     varchar(30) NOT NULL,
  as_of_date       date NOT NULL,
  fee_rule_set_id  uuid NOT NULL REFERENCES sal.fee_rule_set(id),
  rule_version     varchar(20) NOT NULL,
  rule_snapshot    jsonb NOT NULL,                    -- BR-SAL-002 스냅샷
  facility_snapshot jsonb NOT NULL,                   -- 산정 시점 시설물 규모
  calc_trace       jsonb NOT NULL DEFAULT '{}',       -- explanations · warnings
  direct_labor     numeric(15,0) NOT NULL,
  direct_expense   numeric(15,0) NOT NULL,
  overhead_rate    numeric(6,4) NOT NULL,
  overhead         numeric(15,0) NOT NULL,
  tech_fee_rate    numeric(6,4) NOT NULL,
  tech_fee         numeric(15,0) NOT NULL,
  subtotal         numeric(15,0) NOT NULL,            -- 절사 후 법정 기준가
  discount_rate    numeric(6,4) NOT NULL DEFAULT 0,
  offer_amount     numeric(15,0) NOT NULL,
  vat              numeric(15,0) NOT NULL,
  valid_until      date NOT NULL,
  status           varchar(18) NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT','PENDING_APPROVAL','ISSUED','SENT','ACCEPTED',
                                     'REJECTED','SUPERSEDED','EXPIRED')),
  discount_limit   numeric(6,4),                      -- :issue 시 적용된 역할별 한도 (설정 atom.sal.discount-limits)
  document_id      uuid,                              -- core.attachment (HWPX/PDF) — RPT 렌더 포트 연동 후
  approved_by      uuid,
  approved_at      timestamptz,
  approval_comment text,
  issued_at        timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ux_estimate_ver UNIQUE (estimate_no, version_no),
  CONSTRAINT ck_discount CHECK (discount_rate >= 0 AND discount_rate < 0.5),   -- BR-SAL-006 최종 상한(50% 미만)
  CONSTRAINT ck_offer CHECK (offer_amount <= subtotal)
);
CREATE INDEX ix_estimate_opp ON sal.estimate (opportunity_id, version_no DESC);

CREATE TABLE sal.estimate_line (
  id            uuid PRIMARY KEY,
  estimate_id   uuid NOT NULL REFERENCES sal.estimate(id) ON DELETE CASCADE,
  seq           int NOT NULL,
  line_type     varchar(10) NOT NULL CHECK (line_type IN ('LABOR','EXPENSE')),
  work_item     varchar(50) NOT NULL,      -- DATA·FIELD·TEST·ANALYSIS·REPORT / 경비 항목
  grade         varchar(15),               -- SPECIAL/ADVANCED/INTERMEDIATE/BEGINNER
  base_man_days numeric(8,2),
  adj_factor    numeric(10,6),
  man_days      numeric(8,2),
  unit_price    numeric(15,0),
  quantity      numeric(12,2),
  amount        numeric(15,0) NOT NULL,
  note          varchar(300),
  CONSTRAINT ux_estimate_line UNIQUE (estimate_id, seq)
);

-- NFR-SAL-05 / TC-SAL-014: ISSUED 이후(및 EXEC 할인 승인 이후) 금액·요율·스냅샷 변경, 라인 변경·삭제 금지 → SAL-409-010
CREATE FUNCTION sal.estimate_immutable_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE frozen text[] := ARRAY['ISSUED','SENT','ACCEPTED','REJECTED','SUPERSEDED','EXPIRED'];
BEGIN
  IF TG_TABLE_NAME = 'estimate' THEN
    IF TG_OP = 'DELETE' THEN
      IF OLD.status = ANY(frozen) THEN
        RAISE EXCEPTION 'SAL-409-010: issued estimate % v% cannot be deleted', OLD.estimate_no, OLD.version_no
          USING ERRCODE = 'restrict_violation';
      END IF;
      RETURN OLD;
    END IF;
    IF OLD.status = ANY(frozen) OR OLD.approved_by IS NOT NULL THEN
      -- 허용: 상태 전이 · document_id · issued_at · updated_* · version (승인 후 할인·금액 변경은 새 버전으로만)
      IF (NEW.direct_labor, NEW.direct_expense, NEW.overhead_rate, NEW.overhead, NEW.tech_fee_rate, NEW.tech_fee,
          NEW.subtotal, NEW.discount_rate, NEW.offer_amount, NEW.vat, NEW.rule_snapshot, NEW.facility_snapshot,
          NEW.as_of_date, NEW.fee_rule_set_id, NEW.estimate_no, NEW.version_no, NEW.valid_until)
         IS DISTINCT FROM
         (OLD.direct_labor, OLD.direct_expense, OLD.overhead_rate, OLD.overhead, OLD.tech_fee_rate, OLD.tech_fee,
          OLD.subtotal, OLD.discount_rate, OLD.offer_amount, OLD.vat, OLD.rule_snapshot, OLD.facility_snapshot,
          OLD.as_of_date, OLD.fee_rule_set_id, OLD.estimate_no, OLD.version_no, OLD.valid_until)
      THEN
        RAISE EXCEPTION 'SAL-409-010: issued estimate % v% is immutable', OLD.estimate_no, OLD.version_no
          USING ERRCODE = 'restrict_violation';
      END IF;
    END IF;
    IF OLD.status = ANY(frozen) AND OLD.approved_by IS DISTINCT FROM NEW.approved_by THEN
      RAISE EXCEPTION 'SAL-409-010: approval of issued estimate % v% is immutable', OLD.estimate_no, OLD.version_no
        USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
  END IF;
  -- estimate_line
  IF EXISTS (SELECT 1 FROM sal.estimate e WHERE e.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.estimate_id ELSE NEW.estimate_id END
               AND (e.status = ANY(frozen) OR e.approved_by IS NOT NULL)) THEN
    RAISE EXCEPTION 'SAL-409-010: lines of issued estimate are immutable' USING ERRCODE = 'restrict_violation';
  END IF;
  IF TG_OP = 'UPDATE' AND EXISTS (SELECT 1 FROM sal.estimate e WHERE e.id = OLD.estimate_id
               AND (e.status = ANY(frozen) OR e.approved_by IS NOT NULL)) THEN
    RAISE EXCEPTION 'SAL-409-010: lines of issued estimate are immutable' USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER trg_estimate_guard BEFORE UPDATE OR DELETE ON sal.estimate
  FOR EACH ROW EXECUTE FUNCTION sal.estimate_immutable_guard();
CREATE TRIGGER trg_estimate_line_guard BEFORE INSERT OR UPDATE OR DELETE ON sal.estimate_line
  FOR EACH ROW EXECUTE FUNCTION sal.estimate_immutable_guard();
CREATE FUNCTION sal.forbid_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'SAL-409-010: % cannot be truncated', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_estimate_truncate_guard BEFORE TRUNCATE ON sal.estimate
  FOR EACH STATEMENT EXECUTE FUNCTION sal.forbid_truncate();
CREATE TRIGGER trg_estimate_line_truncate_guard BEFORE TRUNCATE ON sal.estimate_line
  FOR EACH STATEMENT EXECUTE FUNCTION sal.forbid_truncate();

-- ---------------------------------------------------------------------
-- 6) 계약 · 변경계약 · 기성 조건 (FR-SAL-018–020, BR-SAL-008·011·012)
--    상태: DRAFT → PENDING(사내 EXEC 결재 완료 · 고객 서명 대기 = sal.contract.ready-to-sign)
--          → APPROVED(고객 서명 기록 · 프로젝트 생성 = sal.contract.signed) → ACTIVE → COMPLETED / TERMINATED
-- ---------------------------------------------------------------------
CREATE TABLE sal.contract (
  id               uuid PRIMARY KEY,
  contract_no      varchar(30) NOT NULL UNIQUE,        -- CTR-2026-0042
  opportunity_id   uuid REFERENCES sal.opportunity(id),
  estimate_id      uuid REFERENCES sal.estimate(id),
  client_id        uuid NOT NULL REFERENCES core.client(id),
  title            varchar(300) NOT NULL,
  service_type     varchar(30) NOT NULL,
  contract_method  varchar(25) NOT NULL
                   CHECK (contract_method IN ('COMPETITIVE','PRIVATE_NEGOTIATED','PRIVATE_SECTOR','OVERSEAS')),
  negotiated_reason varchar(30),
  joint_share      numeric(5,2) NOT NULL DEFAULT 100.00 CHECK (joint_share > 0 AND joint_share <= 100),
  amount           numeric(18,2) NOT NULL CHECK (amount >= 0),
  currency         char(3) NOT NULL DEFAULT 'KRW',
  fx_rate          numeric(12,4),
  amount_krw       numeric(15,0) NOT NULL,
  vat_included     boolean NOT NULL DEFAULT false,
  amount_diff_reason text,                              -- A8-8 최종 견적 대비 1% 이상 차이 사유
  sign_date        date NOT NULL,
  start_date       date NOT NULL,
  end_date         date NOT NULL,
  warranty_months  smallint,
  sign_deadline    date,
  signers          jsonb NOT NULL DEFAULT '[]',
  project_id       uuid REFERENCES core.project(id),
  status           varchar(12) NOT NULL DEFAULT 'DRAFT'
                   CHECK (status IN ('DRAFT','PENDING','APPROVED','ACTIVE','COMPLETED','TERMINATED')),
  scan_attachment_id uuid,
  approved_by      uuid,
  approved_at      timestamptz,
  signed_recorded_by uuid,
  signed_at        timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ck_contract_period CHECK (end_date >= start_date),
  CONSTRAINT ck_negotiated CHECK (contract_method <> 'PRIVATE_NEGOTIATED' OR negotiated_reason IS NOT NULL),
  CONSTRAINT ck_fx CHECK (currency = 'KRW' OR fx_rate IS NOT NULL),
  CONSTRAINT ck_project_when_signed CHECK (status IN ('DRAFT','PENDING','TERMINATED') OR project_id IS NOT NULL)
);
CREATE INDEX ix_contract_opp ON sal.contract (opportunity_id);

CREATE TABLE sal.contract_change (
  id             uuid PRIMARY KEY,
  contract_id    uuid NOT NULL REFERENCES sal.contract(id),
  change_seq     int NOT NULL,
  change_date    date NOT NULL,
  amount_before  numeric(18,2) NOT NULL,
  amount_after   numeric(18,2) NOT NULL,
  end_date_before date NOT NULL,
  end_date_after  date NOT NULL,
  reason         text NOT NULL,
  CONSTRAINT ux_contract_change UNIQUE (contract_id, change_seq)
);

CREATE TABLE sal.payment_term (
  id           uuid PRIMARY KEY,
  contract_id  uuid NOT NULL REFERENCES sal.contract(id),
  term_type    varchar(12) NOT NULL CHECK (term_type IN ('ADVANCE','PROGRESS','FINAL')),
  seq          int NOT NULL,
  rate         numeric(5,2),
  amount       numeric(15,0) NOT NULL,
  milestone    varchar(100),
  due_date     date,
  CONSTRAINT ux_payment_term UNIQUE (contract_id, seq)
);

-- ---------------------------------------------------------------------
-- 7) 적격심사 실적 원천 (BR-SAL-014) — 과거 1,200건 MIGRATED, 이후 준공 계약 SYSTEM. cert_no 유일
-- ---------------------------------------------------------------------
CREATE TABLE sal.performance_record (
  id               uuid PRIMARY KEY,
  cert_no          varchar(40) UNIQUE,
  project_id       uuid REFERENCES core.project(id),
  facility_id      uuid,
  service_type     varchar(30) NOT NULL,
  client_name      varchar(200) NOT NULL,
  amount_krw       numeric(15,0) NOT NULL CHECK (amount_krw >= 0),
  joint_share      numeric(5,2) NOT NULL DEFAULT 100 CHECK (joint_share > 0 AND joint_share <= 100),
  start_date       date NOT NULL,
  end_date         date NOT NULL,
  source           varchar(10) NOT NULL CHECK (source IN ('MIGRATED','SYSTEM')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ck_perf_period CHECK (end_date >= start_date)
);
CREATE INDEX ix_perf_service_end ON sal.performance_record (service_type, end_date);
