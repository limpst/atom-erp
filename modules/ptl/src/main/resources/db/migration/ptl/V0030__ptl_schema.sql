-- =====================================================================
-- 09 PTL 고객 포털 — 첫 단계 스키마 (설계 09 v0.3 B3 · 09-IMPL Step 1)
-- 버전: 전역 단조 번호 V0030 (README 마이그레이션 규칙)
--
-- 고객 격리(BR-PTL-001 · NFR-PTL-01): 모든 행에 customer_id(= core.client.id = 포털 토큰 cid 클레임)를 두고
-- PostgreSQL RLS(ENABLE + FORCE)로 이중 강제한다. 앱은 고객 요청 · 이벤트 처리마다 트랜잭션 안에서
--   set_config('app.customer_id', <cid>, true) + SET LOCAL ROLE ptl_customer
-- 로 들어간다(ptl.application.PortalRls). ptl_customer 는 NOBYPASSRLS 이고 ptl 스키마 외 권한이 없다.
-- 컨텍스트가 없으면 nullif('')::uuid = NULL → 비교 NULL → 0행(TC-PTL-02, fail-closed).
-- 설계의 tenant 표 대신 고객사(core.client) 자체를 tenant 키로 쓴다(포털 realm 클레임이 cid 하나뿐).
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS ptl;

-- 1) 납품 보고서 공개본(공개 데이터 프로젝션 — rpt.report.delivered 수신 시 복사, BR-PTL-004)
CREATE TABLE ptl.published_report (
  id                uuid PRIMARY KEY,
  customer_id       uuid NOT NULL REFERENCES core.client(id),
  report_id         uuid NOT NULL,                 -- rpt.report 논리 참조
  revision_id       uuid NOT NULL,                 -- rpt.report_revision 논리 참조
  rev_no            int  NOT NULL,
  project_id        uuid NOT NULL,
  facility_id       uuid NOT NULL,                 -- fac.building 논리 참조
  report_type       varchar(20) NOT NULL,
  title             varchar(300) NOT NULL,
  inspection_date   date NOT NULL,
  safety_grade      char(1) CHECK (safety_grade IN ('A','B','C','D','E')),
  pdf_sha256        char(64) NOT NULL,             -- RPT 발행본 해시(다운로드 때 원본 재검증)
  page_count        int,
  delivered_at      timestamptz NOT NULL,
  status            varchar(15) NOT NULL DEFAULT 'PUBLISHED'
                    CHECK (status IN ('PUBLISHED','SUPERSEDED','REVOKED')),
  superseded_by_revision_id uuid,
  superseded_reason text,
  superseded_at     timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  version           bigint NOT NULL DEFAULT 0,
  UNIQUE (customer_id, revision_id),
  CONSTRAINT ck_pr_superseded CHECK (status <> 'SUPERSEDED' OR superseded_at IS NOT NULL)
);
CREATE INDEX ix_pr_customer ON ptl.published_report (customer_id, delivered_at DESC);
CREATE INDEX ix_pr_report ON ptl.published_report (report_id);

-- 2) 다운로드 감사(워터마크 1건 = 다운로드 1건, append-only — NFR-PTL-09 · B7.1 4항)
CREATE TABLE ptl.report_download (
  id                  uuid PRIMARY KEY,            -- = 워터마크 download id (PDF 메타데이터 · 본문 문구에 찍힘)
  customer_id         uuid NOT NULL REFERENCES core.client(id),
  published_report_id uuid NOT NULL REFERENCES ptl.published_report(id),
  actor_id            uuid NOT NULL,               -- 포털 사용자(sub 유래 actorId)
  downloaded_at       timestamptz NOT NULL,
  watermark_text      varchar(500) NOT NULL,
  source_sha256       char(64) NOT NULL,           -- 워터마크 전 원본(= published_report.pdf_sha256)
  output_sha256       char(64) NOT NULL,           -- 고객에게 나간 파일 — 유출본 역추적
  client_ip           varchar(64)                  -- 앞 2옥텟만(마스킹)
);
CREATE INDEX ix_download_customer ON ptl.report_download (customer_id, downloaded_at DESC);

-- 3) 서비스 요청(견적 · 긴급 · 재점검 · 확인점검, A6.3) → ptl.service-request.submitted → SAL PORTAL 리드
CREATE TABLE ptl.service_request (
  id            uuid PRIMARY KEY,
  customer_id   uuid NOT NULL REFERENCES core.client(id),
  facility_id   uuid,                              -- 고객 소유 시설물(fac::api 로 검증)
  kind          varchar(15) NOT NULL CHECK (kind IN ('QUOTE','EMERGENCY','REINSPECTION','VERIFY_REPAIR')),
  service_type  varchar(30) NOT NULL,              -- SAL 용역 종류(REGULAR_INSP · PRECISE_INSP · PRECISE_DIAG …)
  reason_code   varchar(30) CHECK (reason_code IN ('EARTHQUAKE','FIRE','CRACK_GROWTH','LEAK','OTHER')),
  description   text NOT NULL,
  contact_name  varchar(50),
  status        varchar(15) NOT NULL DEFAULT 'SUBMITTED'
                CHECK (status IN ('SUBMITTED','ACKNOWLEDGED','ESCALATED','QUOTED','ACCEPTED','DECLINED','SCHEDULED','DONE')),
  sla_due_at    timestamptz,                       -- EMERGENCY = 접수 + 2시간(BR-PTL-007)
  submitted_by  uuid NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  version       bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_sr_emergency CHECK (kind <> 'EMERGENCY' OR (sla_due_at IS NOT NULL AND reason_code IS NOT NULL))
);
CREATE INDEX ix_sr_customer ON ptl.service_request (customer_id, created_at DESC);

-- 4) 고객 알림함(인앱, FR-PTL-014 일부) — 구독 이벤트별 1건(dedupe_key)
CREATE TABLE ptl.notification (
  id           uuid PRIMARY KEY,
  customer_id  uuid NOT NULL REFERENCES core.client(id),
  kind         varchar(30) NOT NULL CHECK (kind IN ('REPORT_DELIVERED','REPORT_SUPERSEDED','GRADE_RECORDED',
                                                   'INSPECTION_DUE_APPROACHING','INSPECTION_OVERDUE',
                                                   'ESTIMATE_ISSUED','CONTRACT_READY_TO_SIGN')),
  category     varchar(10) NOT NULL DEFAULT 'INFO' CHECK (category IN ('INFO','MARKETING')),
  title        varchar(200) NOT NULL,
  body         text NOT NULL,
  ref_type     varchar(30),
  ref_id       uuid,
  dedupe_key   varchar(200) NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  read_at      timestamptz,
  UNIQUE (customer_id, dedupe_key)
);
CREATE INDEX ix_noti_customer ON ptl.notification (customer_id, created_at DESC);

-- 5) 고객 공유 영업 문서(sal.estimate.issued · sal.contract.ready-to-sign) — 서명 봉투(Step 11) 전 단계
CREATE TABLE ptl.sales_document (
  id             uuid PRIMARY KEY,
  customer_id    uuid NOT NULL REFERENCES core.client(id),
  doc_kind       varchar(10) NOT NULL CHECK (doc_kind IN ('ESTIMATE','CONTRACT')),
  sal_ref_id     uuid NOT NULL,                    -- estimateId · contractId
  doc_no         varchar(40),
  version_no     int,
  amount_krw     numeric(18,0),
  vat_krw        numeric(18,0),
  sign_deadline  date,
  signers        jsonb,
  status         varchar(20) NOT NULL CHECK (status IN ('ISSUED','AWAITING_SIGNATURE')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (customer_id, doc_kind, sal_ref_id)
);

-- 6) 고객 접근 로그(FR-PTL-019 · TC-PTL-01 "시도 기록") — VIEW · DOWNLOAD · DENIED, append-only
CREATE TABLE ptl.access_log (
  id           uuid PRIMARY KEY,
  customer_id  uuid NOT NULL REFERENCES core.client(id),
  actor_id     uuid NOT NULL,
  action       varchar(10) NOT NULL CHECK (action IN ('VIEW','DOWNLOAD','DENIED')),
  target_type  varchar(30) NOT NULL,
  target_id    uuid,
  detail       varchar(200),
  occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_access_customer ON ptl.access_log (customer_id, occurred_at DESC);

-- append-only: 다운로드 · 접근 로그는 수정 · 삭제 불가(소유자 포함, TRUNCATE 도 금지)
CREATE OR REPLACE FUNCTION ptl.forbid_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'PTL_APPEND_ONLY: % is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_download_append_only BEFORE UPDATE OR DELETE ON ptl.report_download
  FOR EACH ROW EXECUTE FUNCTION ptl.forbid_mutation();
CREATE TRIGGER trg_download_no_truncate BEFORE TRUNCATE ON ptl.report_download
  FOR EACH STATEMENT EXECUTE FUNCTION ptl.forbid_mutation();
CREATE TRIGGER trg_access_append_only BEFORE UPDATE OR DELETE ON ptl.access_log
  FOR EACH ROW EXECUTE FUNCTION ptl.forbid_mutation();
CREATE TRIGGER trg_access_no_truncate BEFORE TRUNCATE ON ptl.access_log
  FOR EACH STATEMENT EXECUTE FUNCTION ptl.forbid_mutation();

-- ===== Row-Level Security (모든 ptl 테이블) =====
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['published_report','report_download','service_request','notification','sales_document',
                           'access_log']
  LOOP
    EXECUTE format('ALTER TABLE ptl.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE ptl.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY customer_isolation ON ptl.%I
       USING (customer_id = nullif(current_setting('app.customer_id', true), '')::uuid)
       WITH CHECK (customer_id = nullif(current_setting('app.customer_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;

-- ===== 포털 DB 역할: 최소 권한 · BYPASSRLS 금지 (클러스터 전역 객체라 없을 때만 생성) =====
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ptl_customer') THEN
    CREATE ROLE ptl_customer NOLOGIN NOBYPASSRLS NOSUPERUSER NOINHERIT;
  END IF;
  -- 앱 접속 계정이 SET ROLE 할 수 있게(운영 계정이 슈퍼유저가 아니어도) — 마이그레이션 계정 = 앱 계정 가정
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper) THEN
    EXECUTE format('GRANT ptl_customer TO %I', current_user);
  END IF;
END $$;
GRANT USAGE ON SCHEMA ptl TO ptl_customer;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ptl TO ptl_customer;
GRANT UPDATE ON ptl.published_report, ptl.service_request, ptl.notification TO ptl_customer;
-- DELETE 는 주지 않는다. core · fac · rpt · sal 등 내부 스키마 권한도 없다(기본값 — 명시 REVOKE 는 운영 DBA 스크립트).
