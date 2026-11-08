-- V0056 — 독촉(dunning) 1단계 (docs/CST_BILLING_COLLECTION_DESIGN.md §9-1)
--   dunning_notice: 청구 1건 · 단계 1개에 한 번만(ux). 초안(DRAFT) → APPROVED → SENT 는 사람이 상태를 올린다. 자동 발송은 없다.
--   L0 = 31일 이상 알림 · L1 = 61일 이상 담당자 안내 · L2 = 91일 이상 공문(임원 결재). 전액 입금되면 남은 초안은 CANCELLED.
--   dunning_hold: 분쟁 · 하자 협의 등으로 독촉을 멈춘 청구(사유 필수, 해제도 기록) — 보류 중에는 새 초안을 만들지 않는다.
CREATE TABLE cst.dunning_notice (
  id           uuid PRIMARY KEY,
  invoice_id   uuid NOT NULL REFERENCES cst.invoice(id),
  level        varchar(2) NOT NULL CHECK (level IN ('L0','L1','L2')),
  as_of        date NOT NULL,                              -- 초안을 만든 기준일
  age_days     integer NOT NULL CHECK (age_days >= 0),
  open_krw     numeric(18,0) NOT NULL CHECK (open_krw > 0),
  status       varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','SENT','CANCELLED')),
  body_text    text NOT NULL,                              -- 초안 본문(합성 서식). 개인 연락처는 넣지 않는다
  created_at   timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  approved_by  uuid, approved_at timestamptz,
  sent_by      uuid, sent_at timestamptz, sent_channel varchar(20),
  cancelled_at timestamptz, cancel_reason varchar(300),
  CONSTRAINT ux_dunning_invoice_level UNIQUE (invoice_id, level),
  CONSTRAINT ck_dunning_4eyes CHECK (approved_by IS NULL OR approved_by <> created_by)
);
CREATE INDEX ix_dunning_status ON cst.dunning_notice (status, as_of);

CREATE TABLE cst.dunning_hold (
  id          uuid PRIMARY KEY,
  invoice_id  uuid NOT NULL REFERENCES cst.invoice(id),
  reason      varchar(300) NOT NULL,
  held_by     uuid NOT NULL, held_at timestamptz NOT NULL DEFAULT now(),
  released_by uuid, released_at timestamptz, release_reason varchar(300)
);
CREATE UNIQUE INDEX ux_dunning_hold_active ON cst.dunning_hold (invoice_id) WHERE released_at IS NULL;

-- 상태 전이만 UPDATE 허용(DRAFT→APPROVED→SENT, 어느 단계에서든 →CANCELLED). 본문 · 금액 · 청구는 불변. DELETE 금지 (CST-409-031)
CREATE FUNCTION cst.dunning_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'CST-409-031 dunning_notice is append-only (op=DELETE)' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.invoice_id IS DISTINCT FROM OLD.invoice_id OR NEW.level IS DISTINCT FROM OLD.level OR NEW.as_of IS DISTINCT FROM OLD.as_of
     OR NEW.open_krw IS DISTINCT FROM OLD.open_krw OR NEW.body_text IS DISTINCT FROM OLD.body_text OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
    RAISE EXCEPTION 'CST-409-031 dunning_notice content is immutable' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('APPROVED','CANCELLED'))
       OR (OLD.status = 'APPROVED' AND NEW.status IN ('SENT','CANCELLED'))
       OR (OLD.status = NEW.status)) THEN
    RAISE EXCEPTION 'CST-409-032 dunning transition % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_dunning_guard BEFORE UPDATE OR DELETE ON cst.dunning_notice
  FOR EACH ROW EXECUTE FUNCTION cst.dunning_guard();

-- 권한: 재무가 초안을 만들고 발송 기록, 임원 · 경영지원이 L2 결재, 감사 · PM(자기 프로젝트) 조회
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('FIN', 'cst:dunning:write', 'ALL'),
 ('EXEC', 'cst:dunning:approve', 'ALL'), ('ADMIN', 'cst:dunning:approve', 'ALL'),
 ('EXEC', 'cst:dunning:read', 'ALL'), ('ADMIN', 'cst:dunning:read', 'ALL'), ('FIN', 'cst:dunning:read', 'ALL'),
 ('AUDITOR', 'cst:dunning:read', 'ALL');
