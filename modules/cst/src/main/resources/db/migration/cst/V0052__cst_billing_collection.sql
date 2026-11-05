-- =====================================================================
-- V0052 — cst 청구 · 수금 · 통장 대사 (설계 08 Step 12·13 첫 단계, docs/CST_BILLING_COLLECTION_DESIGN.md)
--   * 전역 단조 번호(README 마이그레이션 규칙). 금액은 numeric(18,0) KRW 만(부동소수 금지, NFR-CST-02)
--   * invoice          : 기성 청구(세금계산서). 발행 뒤 금액 · 거래처 · 발행일 불변(BR-CST-BIL-001), 취소는 VOID
--   * bank_receipt     : 통장 입금 한 줄. INSERT 만(BR-CST-BIL-002). 계좌번호 원문은 저장하지 않고 별칭만 둔다
--   * receipt_allocation: 입금 ↔ 청구 배분(대사). INSERT 만, 취소는 음수 역분개 1회(BR-CST-BIL-003),
--                         제안자 ≠ 확정자(4-eyes, BR-CST-BIL-004), 청구 합계 · 입금액 초과 배분 금지(BR-CST-BIL-005)
--   * invoice_open_items(as_of) · receivable_aging(as_of): 기준일을 인자로 받는다(now() 를 쓰지 않아 숫자가 날마다 흔들리지 않음)
-- =====================================================================

CREATE TABLE cst.invoice (
  id              uuid PRIMARY KEY,
  invoice_no      varchar(30)  NOT NULL UNIQUE,             -- INV-2026-000123
  project_id      uuid         NOT NULL REFERENCES core.project(id),
  client_id       uuid         NOT NULL REFERENCES core.client(id),
  issue_date      date         NOT NULL,
  due_date        date,
  supply_krw      numeric(18,0) NOT NULL CHECK (supply_krw > 0),
  vat_krw         numeric(18,0) NOT NULL CHECK (vat_krw >= 0),
  total_krw       numeric(18,0) GENERATED ALWAYS AS (supply_krw + vat_krw) STORED,
  status          varchar(10)  NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED','VOID')),
  tax_invoice_ref varchar(40),                              -- 전자세금계산서 승인번호(선택)
  memo            varchar(300),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_invoice_due CHECK (due_date IS NULL OR due_date >= issue_date)
);
CREATE INDEX ix_invoice_project ON cst.invoice (project_id, issue_date);
CREATE INDEX ix_invoice_client  ON cst.invoice (client_id, issue_date);

CREATE TABLE cst.bank_receipt (
  id            uuid PRIMARY KEY,
  bank_account  varchar(40)  NOT NULL,                      -- 계좌 별칭(예: 'IBK-운영'). 계좌번호 원문 금지
  value_date    date         NOT NULL,
  amount_krw    numeric(18,0) NOT NULL CHECK (amount_krw > 0),
  payer_text    varchar(200) NOT NULL,                      -- 통장 거래내용(입금자 표기)
  statement_key varchar(80)  NOT NULL,                      -- 명세서 행 식별(파일 지문:행). 다시 올려도 한 번만
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ux_bank_receipt_line UNIQUE (bank_account, statement_key)
);
CREATE INDEX ix_bank_receipt_date ON cst.bank_receipt (value_date);

CREATE TABLE cst.receipt_allocation (
  id           uuid PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES cst.invoice(id),
  receipt_id   uuid NOT NULL REFERENCES cst.bank_receipt(id),
  amount_krw   numeric(18,0) NOT NULL CHECK (amount_krw <> 0),   -- 양수 = 배분, 음수 = 역분개
  reversal_of  uuid REFERENCES cst.receipt_allocation(id),
  match_score  smallint CHECK (match_score BETWEEN 0 AND 100),   -- 금액 50 + 이름 30 + 날짜 20 (MatchScorer)
  proposed_by  uuid NOT NULL,
  confirmed_by uuid NOT NULL,
  confirmed_at timestamptz NOT NULL DEFAULT now(),
  reason       varchar(300),
  CONSTRAINT ck_alloc_sign  CHECK ((reversal_of IS NULL AND amount_krw > 0) OR (reversal_of IS NOT NULL AND amount_krw < 0)),
  CONSTRAINT ck_alloc_4eyes CHECK (proposed_by <> confirmed_by),
  CONSTRAINT ux_alloc_reversal UNIQUE (reversal_of)               -- 한 배분은 한 번만 역분개
);
CREATE INDEX ix_alloc_invoice ON cst.receipt_allocation (invoice_id);
CREATE INDEX ix_alloc_receipt ON cst.receipt_allocation (receipt_id);

-- ---------------------------------------------------------------------
-- 불변 규칙 트리거
-- ---------------------------------------------------------------------
CREATE FUNCTION cst.invoice_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_net numeric;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'CST-409-020 invoice is immutable (op=DELETE)' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.invoice_no IS DISTINCT FROM OLD.invoice_no OR NEW.project_id IS DISTINCT FROM OLD.project_id
     OR NEW.client_id IS DISTINCT FROM OLD.client_id OR NEW.issue_date IS DISTINCT FROM OLD.issue_date
     OR NEW.supply_krw IS DISTINCT FROM OLD.supply_krw OR NEW.vat_krw IS DISTINCT FROM OLD.vat_krw THEN
    RAISE EXCEPTION 'CST-409-020 issued invoice amount/party/date is immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.status = 'VOID' AND NEW.status <> 'VOID' THEN
    RAISE EXCEPTION 'CST-409-021 void invoice % cannot be reopened', OLD.invoice_no USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.status = 'ISSUED' AND NEW.status = 'VOID' THEN
    SELECT coalesce(sum(amount_krw), 0) INTO v_net FROM cst.receipt_allocation WHERE invoice_id = OLD.id;
    IF v_net <> 0 THEN
      RAISE EXCEPTION 'CST-409-021 invoice % has allocated receipts (net %)', OLD.invoice_no, v_net
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_invoice_guard BEFORE UPDATE OR DELETE ON cst.invoice
  FOR EACH ROW EXECUTE FUNCTION cst.invoice_guard();

CREATE FUNCTION cst.billing_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% % is append-only (op=%)', TG_ARGV[0], TG_TABLE_NAME, TG_OP USING ERRCODE = 'check_violation';
END $$;
CREATE TRIGGER trg_bank_receipt_immutable BEFORE UPDATE OR DELETE ON cst.bank_receipt
  FOR EACH ROW EXECUTE FUNCTION cst.billing_append_only('CST-409-022');
CREATE TRIGGER trg_alloc_immutable BEFORE UPDATE OR DELETE ON cst.receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION cst.billing_append_only('CST-409-023');

-- 배분 입력 관문: 청구 상태 · 역분개 거울 조건 · 초과 배분(청구 합계 / 입금액) 검사. 같은 청구 · 입금에 대한 동시 배분은 행 잠금으로 줄 세운다
CREATE FUNCTION cst.allocation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_inv  cst.invoice%ROWTYPE;
  v_rcp  cst.bank_receipt%ROWTYPE;
  v_orig cst.receipt_allocation%ROWTYPE;
  v_sum  numeric;
BEGIN
  SELECT * INTO v_inv FROM cst.invoice WHERE id = NEW.invoice_id FOR UPDATE;
  SELECT * INTO v_rcp FROM cst.bank_receipt WHERE id = NEW.receipt_id FOR SHARE;
  IF NEW.reversal_of IS NULL THEN
    IF v_inv.status <> 'ISSUED' THEN
      RAISE EXCEPTION 'CST-409-024 invoice % is % (allocation not allowed)', v_inv.invoice_no, v_inv.status
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    SELECT * INTO v_orig FROM cst.receipt_allocation WHERE id = NEW.reversal_of;
    IF NOT FOUND OR v_orig.reversal_of IS NOT NULL OR v_orig.invoice_id <> NEW.invoice_id
       OR v_orig.receipt_id <> NEW.receipt_id OR v_orig.amount_krw <> -NEW.amount_krw THEN
      RAISE EXCEPTION 'CST-422-020 reversal must mirror the original allocation' USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  SELECT coalesce(sum(amount_krw), 0) + NEW.amount_krw INTO v_sum FROM cst.receipt_allocation WHERE invoice_id = NEW.invoice_id;
  IF v_sum > v_inv.total_krw THEN
    RAISE EXCEPTION 'CST-409-025 allocation exceeds invoice % total (% > %)', v_inv.invoice_no, v_sum, v_inv.total_krw
      USING ERRCODE = 'check_violation';
  END IF;
  SELECT coalesce(sum(amount_krw), 0) + NEW.amount_krw INTO v_sum FROM cst.receipt_allocation WHERE receipt_id = NEW.receipt_id;
  IF v_sum > v_rcp.amount_krw THEN
    RAISE EXCEPTION 'CST-409-026 allocation exceeds receipt amount (% > %)', v_sum, v_rcp.amount_krw
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_alloc_guard BEFORE INSERT ON cst.receipt_allocation
  FOR EACH ROW EXECUTE FUNCTION cst.allocation_guard();

-- ---------------------------------------------------------------------
-- 미수(open item) · aging — 기준일 인자. 구간: A 0~30 · B 31~60 · C 61~90 · D 91~180 · E 181일 이상
--   배분은 입금일(value_date)이 기준일 이하인 것만 센다. 발행일이 기준일보다 뒤인 청구 · VOID 청구는 제외
-- ---------------------------------------------------------------------
CREATE FUNCTION cst.invoice_open_items(p_as_of date)
RETURNS TABLE (invoice_id uuid, invoice_no varchar, project_id uuid, client_id uuid, issue_date date,
               total_krw numeric, allocated_krw numeric, open_krw numeric, age_days integer, bucket text)
LANGUAGE sql STABLE AS $$
  SELECT i.id, i.invoice_no, i.project_id, i.client_id, i.issue_date, i.total_krw,
         coalesce(a.alloc, 0), i.total_krw - coalesce(a.alloc, 0), (p_as_of - i.issue_date),
         CASE WHEN p_as_of - i.issue_date > 180 THEN 'E_181_PLUS'
              WHEN p_as_of - i.issue_date > 90  THEN 'D_91_180'
              WHEN p_as_of - i.issue_date > 60  THEN 'C_61_90'
              WHEN p_as_of - i.issue_date > 30  THEN 'B_31_60'
              ELSE 'A_0_30' END
    FROM cst.invoice i
    LEFT JOIN LATERAL (
      SELECT sum(ra.amount_krw) AS alloc
        FROM cst.receipt_allocation ra JOIN cst.bank_receipt r ON r.id = ra.receipt_id
       WHERE ra.invoice_id = i.id AND r.value_date <= p_as_of) a ON true
   WHERE i.status = 'ISSUED' AND i.issue_date <= p_as_of AND i.total_krw - coalesce(a.alloc, 0) > 0
$$;

CREATE FUNCTION cst.receivable_aging(p_as_of date)
RETURNS TABLE (bucket text, invoice_count bigint, open_krw numeric)
LANGUAGE sql STABLE AS $$
  SELECT o.bucket, count(*), sum(o.open_krw) FROM cst.invoice_open_items(p_as_of) o GROUP BY o.bucket ORDER BY o.bucket
$$;
