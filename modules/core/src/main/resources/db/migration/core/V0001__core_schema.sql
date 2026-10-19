-- =====================================================================
-- V0001__core_schema.sql — core 스키마 (설계 00 v0.3 §7.2, 00-IMPL Step 3·5·6·7)
--   * 확장(PostGIS, pg_trgm, btree_gist) 없이 PostgreSQL 16 기본 기능만 사용한다.
--     → 공간 컬럼·트라이그램 인덱스는 db/migration-ext/V0004__core_postgis_ext.sql (프로필 postgis)
--   * UUID v7: 애플리케이션(UuidV7.java)이 기본 생성, DB 기본값은 core.uuid_generate_v7()
--   * 공통 6컬럼: created_at, created_by, updated_at, updated_by, version, deleted_at
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS core;

-- ---------------------------------------------------------------------
-- UUID v7 (RFC 9562) — PG16에는 내장 uuidv7()가 없으므로 gen_random_uuid()(PG13+) 기반으로 생성
-- ---------------------------------------------------------------------
CREATE FUNCTION core.uuid_generate_v7() RETURNS uuid
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
  ms bigint := floor(extract(epoch FROM clock_timestamp()) * 1000);
  b  bytea  := uuid_send(gen_random_uuid());   -- variant 비트(10)는 v4 그대로 유지
BEGIN
  b := overlay(b PLACING substring(int8send(ms) FROM 3 FOR 6) FROM 1 FOR 6);  -- 48비트 ms 타임스탬프
  b := set_byte(b, 6, (get_byte(b, 6) & 15) | 112);                            -- version 7
  RETURN encode(b, 'hex')::uuid;
END $$;

-- ---------------------------------------------------------------------
-- 기준정보: 고객 · 담당자 · 직원 · 프로젝트 · 투입
-- ---------------------------------------------------------------------
CREATE TABLE core.client (
  id              uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  client_no       varchar(20)  NOT NULL UNIQUE,    -- CL-000123
  name            varchar(200) NOT NULL,
  biz_reg_no      varchar(12),
  client_type     varchar(20)  NOT NULL
                  CHECK (client_type IN ('PUBLIC','PRIVATE','APT','OVERSEAS','INDIVIDUAL')),
  address         varchar(300),
  note            text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid        NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  updated_by      uuid        NOT NULL,
  version         bigint      NOT NULL DEFAULT 0,
  deleted_at      timestamptz
);
CREATE UNIQUE INDEX ux_client_bizno ON core.client (biz_reg_no)
  WHERE deleted_at IS NULL AND biz_reg_no IS NOT NULL;
CREATE INDEX ix_client_name ON core.client (name);

CREATE TABLE core.client_contact (
  id            uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  client_id     uuid NOT NULL REFERENCES core.client(id),
  name          varchar(50) NOT NULL,
  title         varchar(50),
  phone_enc     bytea,            -- 애플리케이션 AES-256-GCM (설계 14-1)
  email_enc     bytea,
  phone_hash    varchar(64),      -- 검색용 HMAC-SHA256
  is_primary    boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);

CREATE TABLE core.employee (
  id                 uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  emp_no             varchar(20) NOT NULL UNIQUE,
  keycloak_user_id   varchar(64) UNIQUE,
  name               varchar(50) NOT NULL,
  dept               varchar(50),
  position           varchar(30),
  hire_date          date NOT NULL,
  retire_date        date,
  is_disabled_worker boolean NOT NULL DEFAULT false,   -- 민감정보: 별도 권한 + VIEW_PII 감사
  status             varchar(10) NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE','LEAVE','RETIRED')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);

CREATE TABLE core.project (
  id               uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  project_no       varchar(20) NOT NULL UNIQUE,      -- PRJ-2026-0001
  name             varchar(300) NOT NULL,
  service_type     varchar(30) NOT NULL,             -- code group SERVICE_TYPE
  client_id        uuid NOT NULL REFERENCES core.client(id),
  contract_id      uuid,                             -- sal.contract.id (논리 FK)
  pm_id            uuid REFERENCES core.employee(id),
  status           varchar(20) NOT NULL DEFAULT 'PLANNED'
                   CHECK (status IN ('PLANNED','IN_PROGRESS','FIELD_DONE','REPORTING',
                                     'IN_REVIEW','DELIVERED','CLOSED','CANCELLED')),
  start_date       date,
  end_date         date,
  contract_amount  numeric(15,0),
  currency         char(3) NOT NULL DEFAULT 'KRW',
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  CONSTRAINT ck_project_period CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);
CREATE INDEX ix_project_client ON core.project (client_id);
CREATE INDEX ix_project_status ON core.project (status) WHERE deleted_at IS NULL;

CREATE TABLE core.project_member (
  id               uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  project_id       uuid NOT NULL REFERENCES core.project(id),
  employee_id      uuid NOT NULL REFERENCES core.employee(id),
  role_in_project  varchar(10) NOT NULL CHECK (role_in_project IN ('RESP','PART','QA','SUPPORT')),
  from_date        date NOT NULL,
  to_date          date NOT NULL,
  CONSTRAINT ux_member UNIQUE (project_id, employee_id, role_in_project),
  CONSTRAINT ck_member_period CHECK (to_date >= from_date)
);
CREATE INDEX ix_member_employee ON core.project_member (employee_id, from_date, to_date);

-- ---------------------------------------------------------------------
-- 공통 코드 (유효기간 관리, 설계 AP-03)
-- ---------------------------------------------------------------------
CREATE TABLE core.code_group (
  group_code  varchar(40) PRIMARY KEY,
  name        varchar(100) NOT NULL,
  description text
);
CREATE TABLE core.code (
  group_code     varchar(40) NOT NULL REFERENCES core.code_group(group_code),
  code           varchar(40) NOT NULL,
  name           varchar(100) NOT NULL,
  name_en        varchar(100),
  sort_order     int NOT NULL DEFAULT 0,
  attrs          jsonb NOT NULL DEFAULT '{}',
  effective_from date NOT NULL DEFAULT DATE '1900-01-01',
  effective_to   date NOT NULL DEFAULT DATE '9999-12-31',
  PRIMARY KEY (group_code, code, effective_from)
);

-- ---------------------------------------------------------------------
-- 문서 번호 채번 (PRJ-yyyy-nnnn, CL-nnnnnn) — 동시성 안전 (00-IMPL Step 3)
-- ---------------------------------------------------------------------
CREATE TABLE core.doc_sequence (
  seq_key   varchar(30) PRIMARY KEY,     -- 'PRJ-2026', 'CL'
  last_no   int NOT NULL
);
CREATE FUNCTION core.next_doc_no(p_prefix text, p_year int, p_width int) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  k text := CASE WHEN p_year IS NULL THEN p_prefix ELSE p_prefix || '-' || p_year END;
  n int;
BEGIN
  INSERT INTO core.doc_sequence(seq_key, last_no) VALUES (k, 1)
  ON CONFLICT (seq_key) DO UPDATE SET last_no = core.doc_sequence.last_no + 1
  RETURNING last_no INTO n;
  RETURN k || '-' || lpad(n::text, p_width, '0');
END $$;

-- ---------------------------------------------------------------------
-- 첨부 + 해시 체인 (설계 7.2, 11.2) — append-only
-- ---------------------------------------------------------------------
CREATE TABLE core.attachment (
  id            uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  bucket        varchar(63)  NOT NULL,
  object_key    varchar(500) NOT NULL,
  file_name     varchar(255) NOT NULL,
  mime_type     varchar(100) NOT NULL,
  size_bytes    bigint NOT NULL CHECK (size_bytes >= 0),
  sha256        char(64) NOT NULL,
  prev_hash     char(64),                 -- 동일 chain_scope 의 직전 chain_hash
  chain_hash    char(64) NOT NULL,        -- sha256(prev_hash || sha256 || captured_at || owner_id)
  owner_module  varchar(4)  NOT NULL,   -- 'CORE' 수용 위해 4로 확장
  owner_type    varchar(40) NOT NULL,
  owner_id      uuid NOT NULL,
  chain_scope   uuid NOT NULL,            -- 체인 단위(점검회차 surveyId 등)
  captured_at   timestamptz,
  exif          jsonb,
  legal_hold    boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  deleted_at timestamptz,
  CONSTRAINT ux_attachment_obj UNIQUE (bucket, object_key)
);
CREATE INDEX ix_attachment_owner ON core.attachment (owner_module, owner_type, owner_id);
CREATE INDEX ix_attachment_sha   ON core.attachment (sha256);
CREATE INDEX ix_attachment_chain ON core.attachment (chain_scope, created_at);
CREATE UNIQUE INDEX ux_attachment_chain_prev ON core.attachment (chain_scope, coalesce(prev_hash, 'GENESIS'));

CREATE TABLE core.hash_anchor (
  anchor_date  date PRIMARY KEY,
  root_hash    char(64) NOT NULL,
  leaf_count   int NOT NULL,
  tsa_token    bytea,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION core.attachment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'attachment is append-only';
  END IF;
  IF (NEW.sha256, NEW.chain_hash, NEW.prev_hash, NEW.object_key, NEW.bucket, NEW.chain_scope)
     IS DISTINCT FROM (OLD.sha256, OLD.chain_hash, OLD.prev_hash, OLD.object_key, OLD.bucket, OLD.chain_scope) THEN
    RAISE EXCEPTION 'immutable columns of attachment cannot change';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_attachment_guard BEFORE UPDATE OR DELETE ON core.attachment
  FOR EACH ROW EXECUTE FUNCTION core.attachment_guard();

-- ---------------------------------------------------------------------
-- 감사 로그 + 해시 체인 (설계 7.2, NFR-CORE-07, ATOM-LIB-001 C2)
--   row_hash = sha256(prev_hash | occurred_at(UTC,µs) | actor | ip | module | action | target | before | after | trace)
--   행 추가는 core.audit_append() 로만(어드바이저리 락으로 직렬화), UPDATE/DELETE 는 트리거로 차단
-- ---------------------------------------------------------------------
CREATE TABLE core.audit_log (
  id           bigserial,
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  actor_id     uuid,
  actor_ip     inet,
  module       varchar(4) NOT NULL,      -- v0.3 varchar(3)에서 확장: 'CORE' 수용
  action       varchar(40) NOT NULL,    -- CREATE/UPDATE/DELETE/VIEW_PII/EXPORT/APPROVE/LOGIN
  target_type  varchar(40),
  target_id    uuid,
  before_json  jsonb,
  after_json   jsonb,
  trace_id     varchar(32),
  prev_hash    char(64),                -- 직전 행의 row_hash (첫 행은 NULL)
  row_hash     char(64) NOT NULL,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE FUNCTION core.ensure_audit_partition(p_month date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  s date := date_trunc('month', p_month);
  e date := (date_trunc('month', p_month) + interval '1 month')::date;
  n text := format('audit_log_%s', to_char(s, 'YYYYMM'));
BEGIN
  EXECUTE format('CREATE TABLE IF NOT EXISTS core.%I PARTITION OF core.audit_log FOR VALUES FROM (%L) TO (%L)', n, s, e);
END $$;
SELECT core.ensure_audit_partition(now()::date);
SELECT core.ensure_audit_partition((now() + interval '1 month')::date);
-- 안전망: 월 파티션 배치가 누락돼도 기록이 실패하지 않게 기본 파티션을 둔다(운영 알림 대상).
CREATE TABLE core.audit_log_default PARTITION OF core.audit_log DEFAULT;

CREATE FUNCTION core.audit_row_hash(
  p_prev text, p_occurred_at timestamptz, p_actor_id uuid, p_actor_ip inet, p_module text, p_action text,
  p_target_type text, p_target_id uuid, p_before jsonb, p_after jsonb, p_trace_id text
) RETURNS char(64) LANGUAGE sql IMMUTABLE AS $$
  SELECT encode(sha256(convert_to(concat_ws('|',
           coalesce(p_prev, 'GENESIS'),
           to_char(p_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           coalesce(p_actor_id::text, ''), coalesce(host(p_actor_ip), ''),
           p_module, p_action,
           coalesce(p_target_type, ''), coalesce(p_target_id::text, ''),
           coalesce(p_before::text, ''), coalesce(p_after::text, ''), coalesce(p_trace_id, '')
         ), 'UTF8')), 'hex')
$$;

CREATE FUNCTION core.audit_append(
  p_actor_id uuid, p_actor_ip inet, p_module text, p_action text, p_target_type text, p_target_id uuid,
  p_before jsonb, p_after jsonb, p_trace_id text
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  v_prev text;
  v_at   timestamptz := date_trunc('microseconds', clock_timestamp());
  v_id   bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('core.audit_log.chain'));   -- 체인 직렬화(트랜잭션 종료 시 해제)
  SELECT row_hash INTO v_prev FROM core.audit_log ORDER BY id DESC LIMIT 1;
  INSERT INTO core.audit_log(occurred_at, actor_id, actor_ip, module, action, target_type, target_id,
                             before_json, after_json, trace_id, prev_hash, row_hash)
  VALUES (v_at, p_actor_id, p_actor_ip, p_module, p_action, p_target_type, p_target_id,
          p_before, p_after, p_trace_id, v_prev,
          core.audit_row_hash(v_prev, v_at, p_actor_id, p_actor_ip, p_module, p_action,
                              p_target_type, p_target_id, p_before, p_after, p_trace_id))
  RETURNING id INTO v_id;
  RETURN v_id;
END $$;

-- 체인 검증: 깨진 첫 행 id 반환(정상이면 NULL)
CREATE FUNCTION core.audit_verify_chain() RETURNS bigint LANGUAGE plpgsql STABLE AS $$
DECLARE
  r record;
  v_prev text := NULL;
BEGIN
  FOR r IN SELECT * FROM core.audit_log ORDER BY id LOOP
    IF r.prev_hash IS DISTINCT FROM v_prev
       OR r.row_hash <> core.audit_row_hash(r.prev_hash, r.occurred_at, r.actor_id, r.actor_ip, r.module,
                                            r.action, r.target_type, r.target_id, r.before_json,
                                            r.after_json, r.trace_id) THEN
      RETURN r.id;
    END IF;
    v_prev := r.row_hash;
  END LOOP;
  RETURN NULL;
END $$;

CREATE FUNCTION core.audit_log_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only (%)', TG_OP;
END $$;
CREATE TRIGGER trg_audit_log_guard BEFORE UPDATE OR DELETE ON core.audit_log
  FOR EACH ROW EXECUTE FUNCTION core.audit_log_guard();

-- ---------------------------------------------------------------------
-- Transactional Outbox (설계 7.2·10.2·10.3, ADR-004)
-- ---------------------------------------------------------------------
CREATE TABLE core.outbox_event (
  id              uuid PRIMARY KEY,
  aggregate_type  varchar(60) NOT NULL,
  aggregate_id    uuid NOT NULL,
  event_type      varchar(100) NOT NULL
                  CHECK (event_type ~ '^[a-z]{3,4}\.[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$'),  -- {mod}.{aggregate}.{과거형}
  event_version   smallint NOT NULL DEFAULT 1,
  payload         jsonb NOT NULL,
  headers         jsonb NOT NULL DEFAULT '{}',  -- traceId, actorId
  occurred_at     timestamptz NOT NULL DEFAULT now(),
  published_at    timestamptz,
  attempts        int NOT NULL DEFAULT 0,
  last_error      text,
  next_attempt_at timestamptz NOT NULL DEFAULT now()   -- 실패 n회 후 + 5·2^n 초 (10→20→40→80)
);
CREATE INDEX ix_outbox_unpublished ON core.outbox_event (next_attempt_at) WHERE published_at IS NULL;

-- DLQ: 5회째 실패 이벤트를 이동(원본 행 삭제). 운영자가 원인 조치 후 재투입(attempts=0)
CREATE TABLE core.outbox_dead (
  LIKE core.outbox_event INCLUDING ALL,
  dead_at timestamptz NOT NULL DEFAULT now()
);

-- 컨슈머 멱등 처리: 업무 처리와 같은 트랜잭션에서 INSERT … ON CONFLICT DO NOTHING
CREATE TABLE core.processed_event (
  consumer     varchar(80) NOT NULL,   -- 예: cst.budget-drafter
  event_id     uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

-- ---------------------------------------------------------------------
-- 멱등 키 (설계 9.4) — 사용자(actor)별 네임스페이스, 24시간 보관
--   response_code IS NULL = 처리 중(락) 상태. Redis SET NX 를 대체(로컬·테스트 환경)
-- ---------------------------------------------------------------------
CREATE TABLE core.idempotency_key (
  key           varchar(80) NOT NULL,
  actor_id      uuid NOT NULL,
  request_hash  char(64) NOT NULL,
  response_code int,
  response_body jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_id, key)
);
CREATE INDEX ix_idempotency_created ON core.idempotency_key (created_at);

-- ---------------------------------------------------------------------
-- 역할 → 권한 (설계 8.3-1). 토큰에는 역할만, 권한은 DB에서 해석
-- ---------------------------------------------------------------------
CREATE TABLE core.role_permission (
  role        varchar(20) NOT NULL
              CHECK (role IN ('EXEC','SALES','PM','ENG','QA','ADMIN','LABELER','CUSTOMER','SYSADMIN',
                              'EMPLOYEE','FIN','AUDITOR')),
  permission  varchar(80) NOT NULL CHECK (permission ~ '^[a-z]{3,4}:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$'),
  scope       varchar(10) NOT NULL DEFAULT 'ALL' CHECK (scope IN ('ALL','PROJECT','SELF','CLIENT')),
  PRIMARY KEY (role, permission)
);

-- core 모듈 시드 (설계 8.2 매트릭스 중 core 리소스 부분, 합성 기준)
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC',    'core:client:read',    'ALL'),
 ('EXEC',    'core:project:read',   'ALL'),
 ('SALES',   'core:client:create',  'ALL'),
 ('SALES',   'core:client:read',    'ALL'),
 ('SALES',   'core:client:update',  'ALL'),
 ('SALES',   'core:project:create', 'ALL'),
 ('SALES',   'core:project:read',   'PROJECT'),
 ('PM',      'core:client:read',    'ALL'),
 ('PM',      'core:project:create', 'ALL'),
 ('PM',      'core:project:read',   'PROJECT'),
 ('PM',      'core:project:update', 'PROJECT'),
 ('ENG',     'core:project:read',   'PROJECT'),
 ('QA',      'core:project:read',   'ALL'),
 ('ADMIN',   'core:client:create',  'ALL'),
 ('ADMIN',   'core:client:read',    'ALL'),
 ('ADMIN',   'core:client:update',  'ALL'),
 ('ADMIN',   'core:project:read',   'ALL'),
 ('FIN',     'core:client:read',    'ALL'),
 ('FIN',     'core:project:read',   'ALL'),
 ('AUDITOR', 'core:client:read',    'ALL'),
 ('AUDITOR', 'core:project:read',   'ALL'),
 ('AUDITOR', 'core:audit-log:read', 'ALL'),
 ('EMPLOYEE','core:employee:read',  'SELF'),
 ('CUSTOMER','core:project:read',   'CLIENT'),
 ('SYSADMIN','core:code:update',    'ALL');
