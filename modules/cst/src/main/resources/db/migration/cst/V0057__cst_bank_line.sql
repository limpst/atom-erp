-- V0057 — 통장 줄 원장 cst.bank_line (docs/CST_BILLING_COLLECTION_DESIGN.md §9-2)
--   업로드한 명세의 모든 줄(입금 · 출금)을 한 줄씩 보관한다. 입금 줄은 bank_receipt(receipt_id) 와 이어진다.
--   suggested_acct = 규칙이 제안한 계정(BankLineClassifier), confirmed_acct = 사람이 확정한 계정. '대체'(자기 계좌 간 이동)만 자동 확정.
--   줄 자체(날짜 · 금액 · 내용 · 키)는 불변, 확정 열 3개만 바뀐다. DELETE 금지 (CST-409-034).
CREATE TABLE cst.bank_line (
  id             uuid PRIMARY KEY,
  bank_account   varchar(40)  NOT NULL,                     -- 계좌 별칭. 계좌번호 원문 금지
  value_date     date         NOT NULL,
  direction      varchar(3)   NOT NULL CHECK (direction IN ('IN','OUT')),
  amount_krw     numeric(18,0) NOT NULL CHECK (amount_krw > 0),
  memo_text      varchar(200) NOT NULL,
  statement_key  varchar(80)  NOT NULL,                     -- 파일 지문:줄번호
  receipt_id     uuid REFERENCES cst.bank_receipt(id),      -- 입금 줄만
  suggested_acct varchar(40),
  rule_code      varchar(60),
  confirmed_acct varchar(40),
  confirmed_by   uuid, confirmed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  CONSTRAINT ux_bank_line UNIQUE (bank_account, statement_key),
  CONSTRAINT ck_bank_line_confirm CHECK ((confirmed_acct IS NULL) = (confirmed_by IS NULL) AND (confirmed_acct IS NULL) = (confirmed_at IS NULL))
);
CREATE INDEX ix_bank_line_date ON cst.bank_line (value_date);
CREATE INDEX ix_bank_line_unclassified ON cst.bank_line (direction, value_date) WHERE confirmed_acct IS NULL;

CREATE FUNCTION cst.bank_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'CST-409-034 bank_line is append-only (op=DELETE)' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.bank_account IS DISTINCT FROM OLD.bank_account OR NEW.value_date IS DISTINCT FROM OLD.value_date
     OR NEW.direction IS DISTINCT FROM OLD.direction OR NEW.amount_krw IS DISTINCT FROM OLD.amount_krw
     OR NEW.memo_text IS DISTINCT FROM OLD.memo_text OR NEW.statement_key IS DISTINCT FROM OLD.statement_key
     OR NEW.receipt_id IS DISTINCT FROM OLD.receipt_id OR NEW.suggested_acct IS DISTINCT FROM OLD.suggested_acct
     OR NEW.rule_code IS DISTINCT FROM OLD.rule_code OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'CST-409-034 bank_line content is immutable (only confirmed_* may change)' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_bank_line_guard BEFORE UPDATE OR DELETE ON cst.bank_line FOR EACH ROW EXECUTE FUNCTION cst.bank_line_guard();

-- 계정별 월 합계(출금 · 입금), 미확정은 '미분류' 로 — 보고용
CREATE VIEW cst.bank_line_monthly AS
SELECT bank_account, to_char(value_date, 'YYYY-MM') AS ym, direction,
       coalesce(confirmed_acct, '미분류') AS acct,
       count(*) AS line_count, sum(amount_krw) AS amount_krw
  FROM cst.bank_line
 GROUP BY bank_account, to_char(value_date, 'YYYY-MM'), direction, coalesce(confirmed_acct, '미분류');

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('FIN', 'cst:bankline:write', 'ALL'),
 ('FIN', 'cst:bankline:read', 'ALL'), ('EXEC', 'cst:bankline:read', 'ALL'), ('ADMIN', 'cst:bankline:read', 'ALL'),
 ('AUDITOR', 'cst:bankline:read', 'ALL');
