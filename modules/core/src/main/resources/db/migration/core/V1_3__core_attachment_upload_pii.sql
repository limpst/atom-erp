-- =====================================================================
-- V1_3 — 첨부 업로드 세션(presign → PUT → :complete, 설계 11.2) + 첨부·PII 권한 시드
-- =====================================================================
CREATE TABLE core.attachment_upload (
  id              uuid PRIMARY KEY,
  temp_bucket     varchar(63)  NOT NULL,
  temp_key        varchar(500) NOT NULL,
  file_name       varchar(255) NOT NULL,
  mime_type       varchar(100) NOT NULL,
  size_bytes      bigint NOT NULL CHECK (size_bytes >= 0),
  claimed_sha256  char(64) NOT NULL,          -- 기기에서 먼저 계산한 해시 (11.2-1)
  owner_module    varchar(4)  NOT NULL,
  owner_type      varchar(40) NOT NULL,
  owner_id        uuid NOT NULL,
  chain_scope     uuid NOT NULL,
  captured_at     timestamptz,
  device_id       varchar(80),
  status          varchar(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','COMPLETED','REJECTED','EXPIRED')),
  reject_reason   varchar(200),
  attachment_id   uuid REFERENCES core.attachment(id),
  expires_at      timestamptz NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      uuid NOT NULL,
  completed_at    timestamptz,
  CONSTRAINT ux_upload_temp UNIQUE (temp_bucket, temp_key)
);
CREATE INDEX ix_upload_pending ON core.attachment_upload (expires_at) WHERE status = 'PENDING';

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ENG',      'core:attachment:create',         'ALL'),
 ('ENG',      'core:attachment:read',           'ALL'),
 ('PM',       'core:attachment:create',         'ALL'),
 ('PM',       'core:attachment:read',           'ALL'),
 ('QA',       'core:attachment:read',           'ALL'),
 ('AUDITOR',  'core:attachment:read',           'ALL'),
 ('ADMIN',    'core:employee-sensitive:read',   'ALL'),
 ('AUDITOR',  'core:employee-sensitive:read',   'ALL');
