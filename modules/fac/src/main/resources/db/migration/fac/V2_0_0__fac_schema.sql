-- =====================================================================
-- V2_0_0 — fac 스키마 (설계 02 v0.3 B3, 02-IMPL Step 1)
--   * contrib 확장(ltree, pg_trgm, btree_gist)은 public 스키마에 설치 → 타입·연산자 클래스는 public. 으로 한정
--   * PostGIS geometry 컬럼(site.boundary, building.location)은 db/migration-ext/V2_99 (프로필 postgis)
--   * 공통 6컬럼의 version 은 core 와 같은 bigint (설계 B3 는 int)
--   * 컨슈머 멱등은 core.processed_event 재사용 (02-IMPL Step 1 의 fac.processed_event 대신)
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS fac;
CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE fac.site (
  id               uuid PRIMARY KEY,
  name             varchar(200) NOT NULL,
  owner_client_id  uuid REFERENCES core.client(id),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);

-- building = 시설물의 정본(canonical) 테이블. 다른 모듈은 building.id 로만 참조한다.
CREATE TABLE fac.building (
  id                uuid PRIMARY KEY,
  site_id           uuid NOT NULL REFERENCES fac.site(id),
  name              varchar(200) NOT NULL,
  dong_name         varchar(100) NOT NULL DEFAULT '',
  pnu               char(19),
  road_address      varchar(300),
  jibun_address     varchar(300),
  main_purpose_cd   varchar(10),
  main_purpose_nm   varchar(100),
  structure_cd      varchar(10),
  structure_nm      varchar(100),
  gross_floor_area  numeric(14,2) CHECK (gross_floor_area >= 0),
  floors_above      smallint CHECK (floors_above >= 0),
  floors_below      smallint CHECK (floors_below >= 0),
  height_m          numeric(7,2),
  approval_date     date,
  status            varchar(30) NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT','VERIFIED','CLASSIFIED','ACTIVE',
                                      'RECLASSIFY_REQUIRED','MERGED','DEMOLISHED','INACTIVE')),
  data_source       varchar(20) NOT NULL DEFAULT 'REGISTER'
                    CHECK (data_source IN ('REGISTER','MANUAL','MIGRATION')),
  merged_into_id    uuid REFERENCES fac.building(id),
  register_snapshot jsonb,
  register_synced_at timestamptz,
  use_restriction_flag boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);
-- BR-FAC-002: PNU + 동명칭은 활성 레코드 중 유일
CREATE UNIQUE INDEX ux_building_pnu_dong ON fac.building (pnu, dong_name)
  WHERE deleted_at IS NULL AND status <> 'MERGED';
CREATE INDEX ix_building_name_trgm ON fac.building USING gin (name public.gin_trgm_ops);
CREATE INDEX ix_building_site ON fac.building (site_id);

CREATE TABLE fac.building_parcel (
  building_id uuid NOT NULL REFERENCES fac.building(id),
  pnu         char(19) NOT NULL,
  is_primary  boolean NOT NULL DEFAULT false,
  PRIMARY KEY (building_id, pnu)
);

CREATE TABLE fac.location_node (
  id           uuid PRIMARY KEY,
  building_id  uuid NOT NULL REFERENCES fac.building(id),
  parent_id    uuid REFERENCES fac.location_node(id),
  node_type    varchar(10) NOT NULL CHECK (node_type IN ('FLOOR','ZONE','MEMBER')),
  member_type  varchar(10) CHECK (member_type IN ('COL','GIR','BEAM','SLAB','WALL','FDN','STAIR','ETC')),
  code         varchar(60) NOT NULL,
  name         varchar(200),
  floor_level  smallint,
  grid_ref     varchar(40),
  material     varchar(20),
  section_spec varchar(60),
  drawing_version_id uuid,
  drawing_xy   point,
  path         public.ltree NOT NULL,
  sort_order   int NOT NULL DEFAULT 0,
  replaced_by_id uuid REFERENCES fac.location_node(id),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);
CREATE UNIQUE INDEX ux_node_code ON fac.location_node (building_id, code) WHERE deleted_at IS NULL;
CREATE INDEX ix_node_path ON fac.location_node USING gist (path);
CREATE INDEX ix_node_parent ON fac.location_node (parent_id);

CREATE TABLE fac.drawing (
  id          uuid PRIMARY KEY,
  building_id uuid NOT NULL REFERENCES fac.building(id),
  floor_node_id uuid REFERENCES fac.location_node(id),
  drawing_kind varchar(20) NOT NULL CHECK (drawing_kind IN ('PLAN','ELEVATION','SECTION','STRUCT','ETC')),
  title       varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);

CREATE TABLE fac.drawing_version (
  id            uuid PRIMARY KEY,
  drawing_id    uuid NOT NULL REFERENCES fac.drawing(id),
  version_no    int NOT NULL,
  object_key    varchar(500) NOT NULL,
  sha256        char(64) NOT NULL,
  tile_prefix   varchar(500),
  tile_status   varchar(12) NOT NULL DEFAULT 'PENDING'
                CHECK (tile_status IN ('PENDING','RUNNING','DONE','FAILED')),
  width_px      int, height_px int,
  scale_text    varchar(20),
  affine_matrix jsonb,
  is_current    boolean NOT NULL DEFAULT false,
  archived      boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  UNIQUE (drawing_id, version_no)
);
CREATE UNIQUE INDEX ux_drawing_current ON fac.drawing_version (drawing_id) WHERE is_current;

CREATE TABLE fac.legal_classification_rule (
  id              uuid PRIMARY KEY,
  law_code        varchar(20) NOT NULL,
  result_class    varchar(10) NOT NULL,
  condition_expr  jsonb NOT NULL,
  priority        int NOT NULL DEFAULT 100,
  effective_from  date NOT NULL,
  effective_to    date,
  legal_reference varchar(300) NOT NULL,
  approved_by     uuid
);

CREATE TABLE fac.legal_classification (
  id             uuid PRIMARY KEY,
  building_id    uuid NOT NULL REFERENCES fac.building(id),
  sitbeop_class  varchar(10) CHECK (sitbeop_class IN ('CLASS1','CLASS2','CLASS3','NONE')),
  bma_target     boolean NOT NULL,
  rule_ids       uuid[] NOT NULL,
  evidence       jsonb NOT NULL,
  is_current     boolean NOT NULL DEFAULT true,
  confirmed_by   uuid NOT NULL,
  confirmed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_classification_current ON fac.legal_classification (building_id) WHERE is_current;

-- ---------------------------------------------------------------------
-- 안전등급 이력 — append-only (BR-FAC-004, 설계 v0.2: RULE 대신 예외 트리거)
-- ---------------------------------------------------------------------
CREATE TABLE fac.grade_history (
  id              uuid PRIMARY KEY,
  building_id     uuid NOT NULL REFERENCES fac.building(id),
  grade           char(1) NOT NULL CHECK (grade IN ('A','B','C','D','E')),
  inspection_type varchar(20) NOT NULL
                  CHECK (inspection_type IN ('REGULAR','DETAILED','PRECISION_DIAG','EMERGENCY','BMA_CHECK')),
  performed_on    date NOT NULL,
  report_id       uuid,
  performer       varchar(20) NOT NULL DEFAULT 'ATOM' CHECK (performer IN ('ATOM','EXTERNAL')),
  source          varchar(20) NOT NULL CHECK (source IN ('REPORT','MIGRATION','EXTERNAL')),
  correction_of   uuid REFERENCES fac.grade_history(id),
  note            text,
  recorded_by     uuid NOT NULL,
  recorded_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_grade_building_date ON fac.grade_history (building_id, performed_on DESC);
-- 같은 보고서·동 조합은 1건 (이벤트 재전달 방어의 2중 안전장치)
CREATE UNIQUE INDEX ux_grade_report_building ON fac.grade_history (report_id, building_id)
  WHERE report_id IS NOT NULL AND correction_of IS NULL;

CREATE FUNCTION fac.forbid_grade_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'fac.grade_history is append-only (% blocked, BR-FAC-004)', TG_OP
    USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_grade_history_immutable
  BEFORE UPDATE OR DELETE ON fac.grade_history
  FOR EACH ROW EXECUTE FUNCTION fac.forbid_grade_mutation();
-- TRUNCATE 도 차단 (행 트리거는 TRUNCATE 를 잡지 못함)
CREATE TRIGGER trg_grade_history_no_truncate
  BEFORE TRUNCATE ON fac.grade_history
  FOR EACH STATEMENT EXECUTE FUNCTION fac.forbid_grade_mutation();

-- ---------------------------------------------------------------------
-- 점검주기 규칙 (BR-FAC-006: 법령값은 데이터, 시행일 기반) — 기간 중복 금지
-- ---------------------------------------------------------------------
CREATE TABLE fac.inspection_cycle_rule (
  id              uuid PRIMARY KEY,
  rule_code       varchar(40) NOT NULL UNIQUE,
  law_code        varchar(20) NOT NULL CHECK (law_code IN ('SITBEOP','BMA') OR law_code LIKE 'TEST%'),
  facility_class  varchar(10) NOT NULL,
  grade           char(1) CHECK (grade IN ('A','B','C','D','E')),
  inspection_type varchar(20) NOT NULL
                  CHECK (inspection_type IN ('REGULAR','DETAILED','PRECISION_DIAG','BMA_CHECK')),
  cycle_months    smallint NOT NULL CHECK (cycle_months > 0),
  first_due_basis varchar(30) CHECK (first_due_basis IN ('APPROVAL_DATE','LAST_PERFORMED')),
  first_due_months smallint,
  substitutes     varchar(20)[] NOT NULL DEFAULT '{}',
  effective_from  date NOT NULL,
  effective_to    date,
  legal_reference varchar(300) NOT NULL,
  approved_by     uuid,
  CONSTRAINT ck_rule_period CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT inspection_cycle_rule_excl EXCLUDE USING gist (
    law_code WITH =, facility_class WITH =, (coalesce(grade, '*')) WITH =, inspection_type WITH =,
    daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') WITH &&)
);

CREATE TABLE fac.inspection_due (
  id              uuid PRIMARY KEY,
  building_id     uuid NOT NULL REFERENCES fac.building(id),
  inspection_type varchar(20) NOT NULL,
  base_date       date,                         -- 최초 점검(이력 없음)이면 NULL, 사용승인일 기준
  due_date        date NOT NULL,
  rule_id         uuid NOT NULL REFERENCES fac.inspection_cycle_rule(id),
  status          varchar(20) NOT NULL DEFAULT 'SCHEDULED'
                  CHECK (status IN ('SCHEDULED','APPROACHING_180','APPROACHING_90','APPROACHING_30',
                                    'OVERDUE','CONTRACTED','DONE','LOST','CANCELLED')),
  sal_lead_id     uuid,
  last_notified_threshold smallint,
  calc_trace      jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_due_active ON fac.inspection_due (building_id, inspection_type)
  WHERE status NOT IN ('DONE','LOST','CANCELLED');
CREATE INDEX ix_due_date ON fac.inspection_due (due_date) WHERE status NOT IN ('DONE','LOST','CANCELLED');

-- 도래 알림 멱등키 (BR-FAC-008, 02-IMPL Step 1 V2_0_2): {buildingId}:{type}:{dueDate}:{threshold}
CREATE TABLE fac.due_notification (
  idempotency_key varchar(120) PRIMARY KEY,
  due_id          uuid NOT NULL REFERENCES fac.inspection_due(id),
  threshold_days  smallint NOT NULL,
  emitted_at      timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 보수보강 (P2 — 스키마만)
-- ---------------------------------------------------------------------
CREATE TABLE fac.repair_history (
  id            uuid PRIMARY KEY,
  building_id   uuid NOT NULL REFERENCES fac.building(id),
  method        varchar(100) NOT NULL,
  quantity      numeric(12,2), unit varchar(10),
  contractor    varchar(200),
  started_on    date, completed_on date,
  cost_krw      bigint,
  note          text,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);
-- v0.2: node_id/defect_id 가 NULL 허용이라 복합 PK 불가 → 대리키 + 유니크 인덱스
CREATE TABLE fac.repair_target (
  id         uuid PRIMARY KEY,
  repair_id  uuid NOT NULL REFERENCES fac.repair_history(id),
  node_id    uuid REFERENCES fac.location_node(id),
  defect_id  uuid,                                   -- fld.defect (모듈 간 FK 미설정, 이벤트로 정합)
  CHECK (node_id IS NOT NULL OR defect_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_repair_target ON fac.repair_target
  (repair_id, coalesce(node_id, '00000000-0000-0000-0000-000000000000'),
   coalesce(defect_id, '00000000-0000-0000-0000-000000000000'));
