-- V0054 — cst 배분 제안 · 청구 번호 시퀀스 (docs/CST_BILLING_COLLECTION_DESIGN.md §7 2단계)
--   allocation_proposal: 재무가 "이 입금은 이 청구" 라고 제안한 줄. PENDING → CONFIRMED(배분 1줄 INSERT) | REJECTED.
--   상태 전이만 UPDATE 로 허용(금액 · 당사자 열은 불변, CST-409-030). 같은 청구 · 입금의 PENDING 은 하나만.
CREATE SEQUENCE cst.invoice_no_seq START 1;

CREATE TABLE cst.allocation_proposal (
  id            uuid PRIMARY KEY,
  invoice_id    uuid NOT NULL REFERENCES cst.invoice(id),
  receipt_id    uuid NOT NULL REFERENCES cst.bank_receipt(id),
  amount_krw    numeric(18,0) NOT NULL CHECK (amount_krw > 0),
  match_score   smallint NOT NULL CHECK (match_score BETWEEN 0 AND 100),
  status        varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','REJECTED')),
  proposed_by   uuid NOT NULL,
  proposed_at   timestamptz NOT NULL DEFAULT now(),
  decided_by    uuid,
  decided_at    timestamptz,
  reject_reason varchar(300),
  allocation_id uuid REFERENCES cst.receipt_allocation(id),
  CONSTRAINT ck_proposal_decided CHECK (
    (status = 'PENDING' AND decided_by IS NULL AND allocation_id IS NULL)
    OR (status = 'CONFIRMED' AND decided_by IS NOT NULL AND allocation_id IS NOT NULL)
    OR (status = 'REJECTED'  AND decided_by IS NOT NULL AND reject_reason IS NOT NULL AND allocation_id IS NULL)),
  CONSTRAINT ck_proposal_4eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);
CREATE UNIQUE INDEX ux_proposal_pending ON cst.allocation_proposal (invoice_id, receipt_id) WHERE status = 'PENDING';
CREATE INDEX ix_proposal_status ON cst.allocation_proposal (status, proposed_at);

CREATE FUNCTION cst.proposal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'CST-409-030 allocation_proposal is append-only (op=DELETE)' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.invoice_id IS DISTINCT FROM OLD.invoice_id OR NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
     OR NEW.amount_krw IS DISTINCT FROM OLD.amount_krw OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
     OR NEW.match_score IS DISTINCT FROM OLD.match_score THEN
    RAISE EXCEPTION 'CST-409-030 proposal amount/parties are immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'CST-409-027 proposal already % ', OLD.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_proposal_guard BEFORE UPDATE OR DELETE ON cst.allocation_proposal
  FOR EACH ROW EXECUTE FUNCTION cst.proposal_guard();
