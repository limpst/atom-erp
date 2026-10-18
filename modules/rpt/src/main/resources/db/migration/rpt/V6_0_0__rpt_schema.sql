-- =====================================================================
-- RPT 보고서 엔진 스키마 (설계 06 v0.2 B3 / 06-IMPL Step 1, Flyway 대역 V6_x)
--   * 06-IMPL 의 V0601~V0612 대신 V6_0_0(스키마)·V6_0_1(템플릿·권한 시드)·V6_0_2(규칙 시드, 생성기 출력)
--   * 공통 6컬럼 version 은 bigint (FAC·HRQ·EQP·SAL 과 동일). rule_set 낙관적 잠금은 version_lock(06-IMPL 팁)
--   * 내장 PostgreSQL 에 btree_gist 가 없어 rule_set 유효기간 겹침 금지는 EXCLUDE 대신 트리거
--   * 섹션 본문은 ProseMirror JSON 한 덩어리 대신 블록 행(rpt.block) — 블록 id 가 anchors.json · QAR 코멘트의 기준
--   * 발행 불변(BR-RPT-008): 리비전이 PUBLISHED·DELIVERED·SUPERSEDED 이면 본문·등급·렌더·앵커 변경과 삭제를 트리거가 거부
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS rpt;

-- ---------------------------------------------------------------------
-- 1) 템플릿 (FR-RPT-013, BR-RPT-010) — 패키지 원본은 package_ref(classpath:/MinIO 키)
-- ---------------------------------------------------------------------
CREATE TABLE rpt.template (
  id            uuid PRIMARY KEY,
  report_type   varchar(20)  NOT NULL,          -- RT_COMMON(공통) · RT_REG · RT_PRC …
  locale        varchar(5)   NOT NULL DEFAULT 'ko',
  name          varchar(200) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  UNIQUE (report_type, locale, name)
);

CREATE TABLE rpt.template_version (
  id             uuid PRIMARY KEY,
  template_id    uuid NOT NULL REFERENCES rpt.template(id),
  semver         varchar(20) NOT NULL,
  package_ref    varchar(500) NOT NULL,          -- classpath:rpt/templates/RT_COMMON/2026.1.0 (MinIO rpt-templates/… 예정)
  manifest       jsonb NOT NULL,                 -- placeholder ↔ 바인딩 경로
  effective_from date NOT NULL,
  effective_to   date,
  status         varchar(20) NOT NULL CHECK (status IN ('DRAFT','APPROVED','DEPLOYED','RETIRED')),
  approved_by    uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0, deleted_at timestamptz,
  UNIQUE (template_id, semver),
  CONSTRAINT ck_tv_period CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ---------------------------------------------------------------------
-- 2) 보고서 · 스냅샷 · 리비전 (FR-RPT-001·002·014·016, A6 상태)
-- ---------------------------------------------------------------------
CREATE TABLE rpt.report (
  id              uuid PRIMARY KEY,
  project_id      uuid NOT NULL REFERENCES core.project(id),
  facility_id     uuid NOT NULL,                 -- fac.building(id) 논리 참조(대표 시설물)
  report_type     varchar(20) NOT NULL CHECK (report_type IN ('RT_REG','RT_PRC','RT_PSD','RT_EMG','RT_BLD','RT_SEI',
                                                               'RT_ADJ_PRE','RT_ADJ_POST','RT_DEM')),
  template_version_id uuid NOT NULL REFERENCES rpt.template_version(id),
  facility_class  varchar(30) NOT NULL DEFAULT 'BLD_RC',
  locale          varchar(5) NOT NULL DEFAULT 'ko',
  title           varchar(300) NOT NULL,
  inspection_date date NOT NULL,                 -- 점검 수행일(현장조사 완료일) — rule 고정 기준일 · FAC performedOn
  status          varchar(20) NOT NULL,          -- 현재 리비전 상태(A6)
  current_rev     int NOT NULL DEFAULT 1,
  paired_report_id uuid REFERENCES rpt.report(id),
  due_date        date,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0, deleted_at timestamptz
);
CREATE INDEX ix_report_project ON rpt.report(project_id);
CREATE INDEX ix_report_status  ON rpt.report(status) WHERE deleted_at IS NULL;

CREATE TABLE rpt.report_revision (
  id              uuid PRIMARY KEY,
  report_id       uuid NOT NULL REFERENCES rpt.report(id),
  rev_no          int  NOT NULL,
  snapshot_id     uuid,                          -- 동결 전 NULL (FK 는 아래 ALTER)
  status          varchar(20) NOT NULL CHECK (status IN ('DRAFT','SNAPSHOT_FROZEN','COMPOSING','IN_REVIEW','APPROVED',
                                                         'SIGNING','PUBLISHED','DELIVERED','SUPERSEDED')),
  change_note     text,
  content_hash    char(64),                      -- 섹션·블록·등급 정규 해시(렌더 최신성 판정)
  drafted_at      timestamptz,                   -- 최초 compose(rpt.report.drafted 1회)
  review_id       uuid,                          -- QAR 검토 id(qar.review.approved)
  approved_at     timestamptz,
  pdf_object_key  varchar(500),
  hwpx_object_key varchar(500),
  anchors_object_key varchar(500),
  pdf_sha256      char(64),
  hwpx_sha256     char(64),
  page_count      int,
  published_at    timestamptz,
  published_by    uuid,
  delivered_at    timestamptz,
  superseded_by   uuid REFERENCES rpt.report_revision(id),
  superseded_at   timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0,
  UNIQUE (report_id, rev_no),
  CONSTRAINT ck_rev_published CHECK (status NOT IN ('PUBLISHED','DELIVERED','SUPERSEDED')
                                     OR (pdf_sha256 IS NOT NULL AND published_at IS NOT NULL)),
  CONSTRAINT ck_rev_superseded CHECK (status <> 'SUPERSEDED' OR superseded_by IS NOT NULL)
);

CREATE TABLE rpt.snapshot (
  id                uuid PRIMARY KEY,
  report_id         uuid NOT NULL REFERENCES rpt.report(id),
  revision_id       uuid NOT NULL REFERENCES rpt.report_revision(id),
  snapshot_ver      int  NOT NULL,
  object_key        varchar(500) NOT NULL,       -- rpt-snapshots/{report}/{ver}.json
  sha256            char(64) NOT NULL,           -- 정규 JSON 해시
  document          jsonb NOT NULL,              -- 동결 문서(FLD 결함 · 부재 · 동 · 측정 장비)
  source_watermarks jsonb NOT NULL DEFAULT '{}',
  rule_set_versions jsonb NOT NULL,              -- {"COND_RATING":"BLD_RC@2026.1", …} (BR-RPT-002)
  defect_count      int NOT NULL,
  blocking_count    int NOT NULL DEFAULT 0,
  frozen_at         timestamptz NOT NULL DEFAULT now(),
  frozen_by         uuid NOT NULL,
  UNIQUE (report_id, snapshot_ver)
);
ALTER TABLE rpt.report_revision ADD CONSTRAINT fk_rev_snapshot FOREIGN KEY (snapshot_id) REFERENCES rpt.snapshot(id);

CREATE TABLE rpt.validation_issue (
  id           uuid PRIMARY KEY,
  revision_id  uuid NOT NULL REFERENCES rpt.report_revision(id),
  snapshot_id  uuid NOT NULL REFERENCES rpt.snapshot(id),
  rule_code    varchar(50) NOT NULL,             -- VAL-CALIBRATION-EXPIRED …
  severity     varchar(10) NOT NULL CHECK (severity IN ('BLOCKING','WARNING','INFO')),
  target_ref   jsonb NOT NULL,                   -- {"module":"fld","type":"defect","id":"D-0001"}
  message      text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_issue_revision ON rpt.validation_issue(revision_id, snapshot_id);

-- ---------------------------------------------------------------------
-- 3) 섹션 · 블록 · 편집 이력 (FR-RPT-011, BR-RPT-001) — 블록 id = anchors.json · QAR 코멘트 앵커
-- ---------------------------------------------------------------------
CREATE TABLE rpt.section (
  id            uuid PRIMARY KEY,
  revision_id   uuid NOT NULL REFERENCES rpt.report_revision(id),
  section_key   varchar(100) NOT NULL,           -- 'ch4.quantities'
  order_no      int NOT NULL,
  title         varchar(300) NOT NULL,
  source        varchar(10) NOT NULL CHECK (source IN ('AUTO','AI','HUMAN')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version bigint NOT NULL DEFAULT 0,
  UNIQUE (revision_id, section_key)
);

CREATE TABLE rpt.block (
  id            uuid PRIMARY KEY,                -- blockId
  revision_id   uuid NOT NULL REFERENCES rpt.report_revision(id),
  section_id    uuid NOT NULL REFERENCES rpt.section(id),
  section_key   varchar(100) NOT NULL,
  order_no      int NOT NULL,
  kind          varchar(12) NOT NULL CHECK (kind IN ('HEADING','PARAGRAPH','TABLE','IMAGE')),
  text          text NOT NULL DEFAULT '',        -- 표시 텍스트(표 = 행 직렬화, 그림 = 캡션)
  data          jsonb,                           -- 표 열·행 / 그림 키 / 바인딩 경로
  locked        boolean NOT NULL DEFAULT false,  -- 스냅샷 파생(표·그림·바인딩 문장) — 편집 불가(BR-RPT-001)
  text_hash     char(64) NOT NULL,
  source        varchar(10) NOT NULL CHECK (source IN ('AUTO','AI','HUMAN')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid,
  UNIQUE (section_id, order_no) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ix_block_revision ON rpt.block(revision_id, section_key, order_no);

-- 편집 이력(append-only) — QAR 자기 검토 금지 판정용 sectionEditors(revisionId)
CREATE TABLE rpt.block_edit (
  id           bigserial PRIMARY KEY,
  revision_id  uuid NOT NULL REFERENCES rpt.report_revision(id),
  section_key  varchar(100) NOT NULL,
  block_id     uuid NOT NULL,
  action       varchar(10) NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE')),
  source       varchar(10) NOT NULL CHECK (source IN ('AUTO','AI','HUMAN')),
  editor_id    uuid NOT NULL,
  text_hash    char(64),
  edited_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_block_edit_rev ON rpt.block_edit(revision_id, section_key);

-- ---------------------------------------------------------------------
-- 4) 상태평가 · 등급 (FR-RPT-006·007, BR-RPT-005)
-- ---------------------------------------------------------------------
CREATE TABLE rpt.grade_result (
  id              uuid PRIMARY KEY,
  revision_id     uuid NOT NULL UNIQUE REFERENCES rpt.report_revision(id),
  rule_set_versions jsonb NOT NULL,
  scope_tree      jsonb NOT NULL,                -- 층/동 점수 · 적용 하한 규칙
  weighted_score  numeric(6,3) NOT NULL,
  computed_grade  char(1) NOT NULL CHECK (computed_grade IN ('A','B','C','D','E')),
  final_grade     char(1) CHECK (final_grade IN ('A','B','C','D','E')),
  override_reason text,
  override_evidence text[],                      -- 근거 결함 표시번호(D-0001 …)
  result_sha256   char(64) NOT NULL,             -- 정규 JSON 해시(NFR-RPT-03 재현성)
  decided_by      uuid, decided_at timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_override CHECK (final_grade IS NULL OR final_grade = computed_grade
                                OR (length(override_reason) >= 100 AND cardinality(override_evidence) >= 1))
);

CREATE TABLE rpt.member_rating (
  id              uuid PRIMARY KEY,
  grade_result_id uuid NOT NULL REFERENCES rpt.grade_result(id),
  building_code   varchar(20) NOT NULL,
  floor_code      varchar(10) NOT NULL,
  member_type     varchar(20) NOT NULL,
  member_count    int NOT NULL CHECK (member_count > 0),
  rating          char(1) NOT NULL CHECK (rating IN ('a','b','c','d','e')),
  score           numeric(4,2) NOT NULL,
  weight          numeric(4,3) NOT NULL,
  evidence        jsonb NOT NULL
);
CREATE INDEX ix_member_rating_result ON rpt.member_rating(grade_result_id);

-- ---------------------------------------------------------------------
-- 5) rule table (유효기간 관리, BR-RPT-002)  ⚠️ 법령 최신본 확인 필요
-- ---------------------------------------------------------------------
CREATE TABLE rpt.rule_set (
  id             uuid PRIMARY KEY,
  rule_kind      varchar(30) NOT NULL CHECK (rule_kind IN ('COND_RATING','GRADE_WEIGHT','GRADE_BAND','REPAIR_MAP',
                                                           'UNIT_PRICE','FMS_MAP','VALIDATION')),
  facility_class varchar(30) NOT NULL,
  version        varchar(20) NOT NULL,
  effective_from date NOT NULL,
  effective_to   date,
  legal_basis    text,
  status         varchar(20) NOT NULL CHECK (status IN ('DRAFT','APPROVED','DEPLOYED','RETIRED')),
  approved_by    uuid, approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL,
  updated_at timestamptz, updated_by uuid, version_lock bigint NOT NULL DEFAULT 0,
  UNIQUE (rule_kind, facility_class, version),
  CONSTRAINT ck_rule_period CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE rpt.rule_item (
  id          uuid PRIMARY KEY,
  rule_set_id uuid NOT NULL REFERENCES rpt.rule_set(id) ON DELETE CASCADE,
  item_key    varchar(100) NOT NULL,             -- 'COLUMN.crack_width_mm' · '_extent' · 'weights' …
  expr        jsonb NOT NULL,
  UNIQUE (rule_set_id, item_key)
);

-- 같은 종류 · 시설물 분류에서 DEPLOYED 유효기간 겹침 금지 (EXCLUDE 대체 — btree_gist 없음)
CREATE FUNCTION rpt.rule_set_overlap_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status = 'DEPLOYED' AND EXISTS (
       SELECT 1 FROM rpt.rule_set r
        WHERE r.id <> NEW.id AND r.status = 'DEPLOYED'
          AND r.rule_kind = NEW.rule_kind AND r.facility_class = NEW.facility_class
          AND daterange(r.effective_from, coalesce(r.effective_to, 'infinity'::date), '[)')
              && daterange(NEW.effective_from, coalesce(NEW.effective_to, 'infinity'::date), '[)')) THEN
    RAISE EXCEPTION 'RPT-409-011: DEPLOYED rule_set period overlaps (% / %)', NEW.rule_kind, NEW.facility_class
      USING ERRCODE = 'exclusion_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_rule_set_overlap BEFORE INSERT OR UPDATE ON rpt.rule_set
  FOR EACH ROW EXECUTE FUNCTION rpt.rule_set_overlap_guard();

-- ---------------------------------------------------------------------
-- 6) 망도 · 렌더 · 앵커 · 납품 확인 (FR-RPT-004·012·017, v0.2 납품 확인)
-- ---------------------------------------------------------------------
CREATE TABLE rpt.drawing_sheet (
  id             uuid PRIMARY KEY,
  revision_id    uuid NOT NULL REFERENCES rpt.report_revision(id),
  building_code  varchar(20) NOT NULL,
  floor_code     varchar(10) NOT NULL,
  dxf_object_key varchar(500) NOT NULL,
  dxf_sha256     char(64) NOT NULL,
  png_object_key varchar(500) NOT NULL,
  entity_count   int NOT NULL,
  pin_layout     jsonb NOT NULL DEFAULT '[]',
  manual_adjusted boolean NOT NULL DEFAULT false,
  UNIQUE (revision_id, building_code, floor_code)
);

CREATE TABLE rpt.render_job (
  id           uuid PRIMARY KEY,
  revision_id  uuid NOT NULL REFERENCES rpt.report_revision(id),
  kind         varchar(10) NOT NULL CHECK (kind IN ('HWPX','PDF','PACKAGE','FMS')),
  status       varchar(15) NOT NULL CHECK (status IN ('QUEUED','RUNNING','DONE','FAILED','DLQ')),
  progress     smallint NOT NULL DEFAULT 0,
  attempts     smallint NOT NULL DEFAULT 0,
  error        jsonb,
  content_hash char(64),
  converter    varchar(30),                      -- GOTENBERG · STUB
  hwpx_object_key varchar(500), hwpx_sha256 char(64),
  pdf_object_key  varchar(500), pdf_sha256  char(64),
  page_count   int,
  started_at timestamptz, finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), created_by uuid NOT NULL
);
CREATE INDEX ix_render_job_rev ON rpt.render_job(revision_id, created_at DESC);

-- 리비전별 anchors.json — 블록 → 페이지 · 좌표(rect) · 텍스트 해시 (QAR 코멘트 위치 고정)
CREATE TABLE rpt.revision_anchor (
  revision_id    uuid PRIMARY KEY REFERENCES rpt.report_revision(id),
  render_job_id  uuid NOT NULL REFERENCES rpt.render_job(id),
  anchors        jsonb NOT NULL,                 -- anchors.json 원문
  anchors_sha256 char(64) NOT NULL,
  page_count     int NOT NULL,
  source         varchar(12) NOT NULL CHECK (source IN ('ESTIMATED','PDF_TEXT')),
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE rpt.delivery_confirmation (
  id                    uuid PRIMARY KEY,
  revision_id           uuid NOT NULL UNIQUE REFERENCES rpt.report_revision(id),
  delivered_at          timestamptz NOT NULL,
  recipient_name        varchar(100) NOT NULL,
  recipient_org         varchar(200),
  receipt_attachment_id uuid,                    -- core.attachment(수령증) 논리 참조
  package_id            uuid,                    -- 납품 패키지(Step 14) — 아직 NULL
  note                  text,
  created_at            timestamptz NOT NULL DEFAULT now(),
  created_by            uuid NOT NULL
);

-- ---------------------------------------------------------------------
-- 7) 불변 트리거 (BR-RPT-007·008, TC-RPT-007 DB 계층)
-- ---------------------------------------------------------------------
CREATE FUNCTION rpt.revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE frozen text[] := ARRAY['PUBLISHED','DELIVERED','SUPERSEDED'];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = ANY(frozen) THEN
      RAISE EXCEPTION 'RPT-409-010: published revision % (rev %) cannot be deleted', OLD.id, OLD.rev_no
        USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status = ANY(frozen) THEN
    -- 허용: PUBLISHED → DELIVERED / SUPERSEDED, DELIVERED → SUPERSEDED 와 그 표시 컬럼(delivered_at · superseded_*)
    IF (NEW.report_id, NEW.rev_no, NEW.snapshot_id, NEW.content_hash, NEW.pdf_object_key, NEW.pdf_sha256,
        NEW.hwpx_object_key, NEW.hwpx_sha256, NEW.anchors_object_key, NEW.page_count, NEW.published_at,
        NEW.published_by, NEW.change_note, NEW.drafted_at, NEW.review_id, NEW.approved_at)
       IS DISTINCT FROM
       (OLD.report_id, OLD.rev_no, OLD.snapshot_id, OLD.content_hash, OLD.pdf_object_key, OLD.pdf_sha256,
        OLD.hwpx_object_key, OLD.hwpx_sha256, OLD.anchors_object_key, OLD.page_count, OLD.published_at,
        OLD.published_by, OLD.change_note, OLD.drafted_at, OLD.review_id, OLD.approved_at)
       OR NOT ((OLD.status = NEW.status)
               OR (OLD.status = 'PUBLISHED' AND NEW.status IN ('DELIVERED','SUPERSEDED'))
               OR (OLD.status = 'DELIVERED' AND NEW.status = 'SUPERSEDED'))
       OR (OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at)
       OR (OLD.superseded_by IS NOT NULL AND NEW.superseded_by IS DISTINCT FROM OLD.superseded_by) THEN
      RAISE EXCEPTION 'RPT-409-010: published revision % (rev %) is immutable', OLD.id, OLD.rev_no
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_revision_guard BEFORE UPDATE OR DELETE ON rpt.report_revision
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_guard();

-- 리비전 하위 행(본문 · 등급 · 렌더 · 앵커 · 검증 · 망도) — 발행 이후 INSERT/UPDATE/DELETE 거부
CREATE FUNCTION rpt.revision_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rid uuid; st text;
BEGIN
  IF TG_TABLE_NAME = 'member_rating' THEN
    SELECT g.revision_id INTO rid FROM rpt.grade_result g
     WHERE g.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.grade_result_id ELSE NEW.grade_result_id END;
  ELSE
    rid := CASE WHEN TG_OP = 'DELETE' THEN OLD.revision_id ELSE NEW.revision_id END;
  END IF;
  SELECT status INTO st FROM rpt.report_revision WHERE id = rid;
  IF st IN ('PUBLISHED','DELIVERED','SUPERSEDED') THEN
    RAISE EXCEPTION 'RPT-409-010: % of published revision % is immutable', TG_TABLE_NAME, rid
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER trg_section_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.section
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_block_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.block
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_grade_result_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.grade_result
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_member_rating_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.member_rating
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_drawing_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.drawing_sheet
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_render_job_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.render_job
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_anchor_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.revision_anchor
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();
CREATE TRIGGER trg_issue_guard BEFORE INSERT OR UPDATE OR DELETE ON rpt.validation_issue
  FOR EACH ROW EXECUTE FUNCTION rpt.revision_child_guard();

-- append-only: 스냅샷 · 편집 이력 · 납품 확인
CREATE FUNCTION rpt.append_only_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'RPT-409-010: % is append-only (%)', TG_TABLE_NAME, TG_OP USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER trg_snapshot_append_only BEFORE UPDATE OR DELETE ON rpt.snapshot
  FOR EACH ROW EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_block_edit_append_only BEFORE UPDATE OR DELETE ON rpt.block_edit
  FOR EACH ROW EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_delivery_append_only BEFORE UPDATE OR DELETE ON rpt.delivery_confirmation
  FOR EACH ROW EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_revision_truncate BEFORE TRUNCATE ON rpt.report_revision
  FOR EACH STATEMENT EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_snapshot_truncate BEFORE TRUNCATE ON rpt.snapshot
  FOR EACH STATEMENT EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_delivery_truncate BEFORE TRUNCATE ON rpt.delivery_confirmation
  FOR EACH STATEMENT EXECUTE FUNCTION rpt.append_only_guard();
CREATE TRIGGER trg_block_edit_truncate BEFORE TRUNCATE ON rpt.block_edit
  FOR EACH STATEMENT EXECUTE FUNCTION rpt.append_only_guard();
