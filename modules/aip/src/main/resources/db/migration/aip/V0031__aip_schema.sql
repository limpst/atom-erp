-- =====================================================================
-- AIP AI 플랫폼 스키마 (설계 11 v0.3 B3 / 11-IMPL Step 1·2·8·10·12 일부)
--   설계의 V11_001~V11_007 대신 V0031 하나(전역 단조 번호, README 마이그레이션 규칙). 내장 PostgreSQL 16 기준:
--   · btree_gist 있음 → deployment 기간 겹침 EXCLUDE 는 설계 그대로
--   · pgvector · pg_bigm(kb_document · kb_chunk) → 이번 단계 미생성(워커의 키워드 대체 검색, 후속 Step 13)
--   · pg_partman(inference_audit 월 파티션) → aip.ensure_audit_partition() + 스케줄러(현재월 + 3개월 선생성)
-- =====================================================================

-- ① 모델 · 프롬프트 레지스트리 (MLflow 와 동기화되는 ERP 측 사본 — MLflow 연동은 후속)
CREATE TABLE aip.model_version (
  id              uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  name            varchar(60) NOT NULL,             -- crack(서빙 LoRA 이름), gemma-4-12b-it, crack-measure
  version         varchar(20) NOT NULL,
  base_model      varchar(80),
  artifact_uri    text NOT NULL,
  artifact_sha256 char(64) NOT NULL,
  stage           varchar(15) NOT NULL DEFAULT 'TRAINED'
                  CHECK (stage IN ('TRAINED','EVALUATED','REJECTED','SHADOW','CANARY','PRODUCTION','ROLLED_BACK','ARCHIVED')),
  metrics         jsonb,
  approved_by     uuid,                              -- QA(품질) 승인 — SHADOW 진입
  exec_approved_by uuid,                             -- EXEC(최종) 승인 — PRODUCTION 진입
  created_at      timestamptz NOT NULL DEFAULT now(),
  stage_changed_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (name, version)
);

CREATE TABLE aip.prompt_version (
  id              uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  prompt_key      varchar(60) NOT NULL,             -- vision.crack.score, report.draft.finding, report.review
  version         varchar(20) NOT NULL,
  template        text NOT NULL,
  output_schema   jsonb,
  template_sha256 char(64) NOT NULL,
  status          varchar(15) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','ACTIVE','RETIRED')),
  eval_summary    jsonb,
  approved_by     uuid,
  UNIQUE (prompt_key, version)
);

-- 라우팅 별칭(production / canary / shadow) — 같은 서비스 · 별칭의 활성 기간 겹침 금지(EXCLUDE)
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE TABLE aip.deployment (
  id               uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  service_code     varchar(30) NOT NULL,
  alias            varchar(15) NOT NULL CHECK (alias IN ('production','canary','shadow')),
  model_version_id uuid NOT NULL REFERENCES aip.model_version(id),
  prompt_version_id uuid REFERENCES aip.prompt_version(id),
  traffic_pct      smallint NOT NULL DEFAULT 0 CHECK (traffic_pct BETWEEN 0 AND 100),
  auto_rollback_rule jsonb,                          -- {"rejectRateDeltaPct":5,"minSamples":200}
  active_from      timestamptz NOT NULL DEFAULT now(),
  active_to        timestamptz,
  CHECK (active_to IS NULL OR active_to > active_from),
  CONSTRAINT ex_deployment_alias_period EXCLUDE USING gist (service_code WITH =, alias WITH =,
          tstzrange(active_from, coalesce(active_to, 'infinity')) WITH &&)
);
CREATE INDEX ix_deployment_active ON aip.deployment (service_code, alias) WHERE active_to IS NULL;

-- ② Job 큐 (A6.1 상태 · 우선순위 P1–P4 · 마감 · 재시도 · lease)
CREATE TABLE aip.job (
  id                uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  service_code      varchar(30) NOT NULL,
  mode              varchar(5)  NOT NULL DEFAULT 'ASYNC' CHECK (mode IN ('ASYNC','SYNC')),
  priority          smallint NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 4),  -- 1 대화형, 2 보고서, 3 드론 대량, 4 백필
  status            varchar(12) NOT NULL DEFAULT 'QUEUED'
                    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','DEAD','PREEMPTED','CANCELLED')),
  idempotency_key   varchar(100) UNIQUE,
  requested_by      uuid NOT NULL,
  requester_module  varchar(4),                       -- RPT · QAR · FLD … (동기 호출 출처)
  project_id        uuid REFERENCES core.project(id),
  input_refs        jsonb NOT NULL DEFAULT '[]',      -- [{"attachmentId":..,"sha256":..}]
  input_sha256      char(64) NOT NULL,                -- 정렬된 입력 sha256 + 정규 params 의 해시
  params            jsonb NOT NULL DEFAULT '{}',
  model_version_id  uuid REFERENCES aip.model_version(id),
  prompt_version_id uuid REFERENCES aip.prompt_version(id),
  route_alias       varchar(15),
  vram_budget_mb    int NOT NULL CHECK (vram_budget_mb >= 0),
  attempts          smallint NOT NULL DEFAULT 0,
  max_attempts      smallint NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
  not_before        timestamptz NOT NULL DEFAULT now(),
  deadline_at       timestamptz,                      -- 지나면 실행하지 않고 DEAD(DEADLINE_EXCEEDED)
  lease_until       timestamptz,
  worker_id         varchar(60),
  cancel_requested  boolean NOT NULL DEFAULT false,   -- RUNNING 협조적 취소
  progress_pct      smallint NOT NULL DEFAULT 0 CHECK (progress_pct BETWEEN 0 AND 100),
  result_ref        jsonb,
  error             jsonb,
  queued_at         timestamptz NOT NULL DEFAULT now(),
  started_at        timestamptz,
  finished_at       timestamptz,
  CHECK (status <> 'SUCCEEDED' OR result_ref IS NOT NULL)
);
CREATE INDEX ix_job_dequeue ON aip.job (priority, queued_at) WHERE status = 'QUEUED';
CREATE INDEX ix_job_lease ON aip.job (lease_until) WHERE status = 'RUNNING';
CREATE INDEX ix_job_project ON aip.job (project_id);

-- 진행률 알림(SSE 용, 11-IMPL Step 1) — LISTEN aip_job_progress
CREATE FUNCTION aip.notify_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('aip_job_progress',
    json_build_object('id', NEW.id, 'status', NEW.status, 'progress', NEW.progress_pct)::text);
  RETURN NEW;
END $$;
CREATE TRIGGER trg_job_notify AFTER UPDATE OF status, progress_pct ON aip.job
  FOR EACH ROW EXECUTE FUNCTION aip.notify_job();

CREATE TABLE aip.job_event (
  id       bigserial PRIMARY KEY,
  job_id   uuid NOT NULL REFERENCES aip.job(id),
  at       timestamptz NOT NULL DEFAULT now(),
  kind     varchar(30) NOT NULL CHECK (kind IN ('STATE','PROGRESS','LOG','METRIC')),
  payload  jsonb
);
CREATE INDEX ix_job_event_job ON aip.job_event (job_id, id);

-- ③ 제안 · 피드백 (A6.2, BR-AIP-001 · 005 · 012)
CREATE TABLE aip.suggestion (
  id                  uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  job_id              uuid NOT NULL REFERENCES aip.job(id),
  project_id          uuid REFERENCES core.project(id),
  photo_attachment_id uuid NOT NULL REFERENCES core.attachment(id),
  defect_type         varchar(20) NOT NULL CHECK (defect_type IN ('CRACK','SPALL','SCALING','EFFLORESCENCE','REBAR','LEAK')),
  crack_pattern       varchar(20),
  geometry            jsonb NOT NULL,
  mask_ref            text,
  width_max_mm        numeric(6,3),
  width_mean_mm       numeric(6,3),
  width_p95_mm        numeric(6,3),
  width_uncert_mm     numeric(6,3),
  length_m            numeric(8,3),
  area_m2             numeric(10,4),
  mm_per_px           numeric(10,6),
  scale_source        varchar(15) CHECK (scale_source IN ('ARUCO','LASER','GAUGE','MANUAL','NONE')),
  confidence          numeric(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  description         text,
  flags               text[] NOT NULL DEFAULT '{}',
  status              varchar(12) NOT NULL DEFAULT 'SUGGESTED'
                      CHECK (status IN ('SUGGESTED','ACCEPTED','MODIFIED','REJECTED','EXPIRED')),
  confirmed_defect_id uuid,
  created_at          timestamptz NOT NULL DEFAULT now(),
  -- BR-AIP-002: 스케일이 없으면 폭 수치 금지
  CONSTRAINT ck_width_needs_scale CHECK (width_max_mm IS NULL OR (scale_source IS NOT NULL AND scale_source <> 'NONE'))
);
CREATE INDEX ix_sugg_photo ON aip.suggestion (photo_attachment_id);
CREATE INDEX ix_sugg_open ON aip.suggestion (created_at) WHERE status = 'SUGGESTED';

-- 동의 · 블러 플래그 (BR-AIP-005 (b)(c))
CREATE TABLE aip.project_consent (
  project_id        uuid PRIMARY KEY REFERENCES core.project(id),
  training_consent  boolean NOT NULL,                 -- 고객 계약상 비식별 학습 활용 동의
  source            varchar(30) NOT NULL DEFAULT 'MANUAL',   -- SAL_CONTRACT · MANUAL
  recorded_by       uuid NOT NULL,
  recorded_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE aip.input_privacy (
  attachment_id     uuid PRIMARY KEY REFERENCES core.attachment(id),
  blur_done         boolean NOT NULL DEFAULT false,   -- 얼굴 · 번호판 블러 완료
  faces_blurred     int NOT NULL DEFAULT 0,
  plates_blurred    int NOT NULL DEFAULT 0,
  checked_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE aip.feedback (
  id                uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  suggestion_id     uuid NOT NULL UNIQUE REFERENCES aip.suggestion(id),
  decision          varchar(10) NOT NULL CHECK (decision IN ('ACCEPTED','MODIFIED','REJECTED')),
  reason_code       varchar(30),                      -- FALSE_POSITIVE, WRONG_TYPE, WIDTH_OFF, GEOMETRY_OFF
  diff              jsonb,
  decided_by        uuid NOT NULL,
  decided_at        timestamptz NOT NULL DEFAULT now(),
  label_queued      boolean NOT NULL DEFAULT false,   -- (b) 동의 ∧ (c) 블러 → 라벨링 큐 후보 등록 여부
  training_eligible boolean NOT NULL DEFAULT false,   -- BR-AIP-012: 결정 시점 항상 false, 데이터셋 커밋 때만 true
  committed_dataset_id uuid,
  CHECK (decision <> 'REJECTED' OR reason_code IS NOT NULL)
);

-- ④ 라벨링 (Label Studio 연동 전 최소 형태)
CREATE TABLE aip.label_task (
  id                  uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  source              varchar(15) NOT NULL CHECK (source IN ('FEEDBACK','ACTIVE_LEARNING','NEW_COLLECTION')),
  feedback_id         uuid UNIQUE REFERENCES aip.feedback(id),
  project_id          uuid REFERENCES core.project(id),
  image_attachment_id uuid NOT NULL REFERENCES core.attachment(id),
  image_sha256        char(64) NOT NULL,
  priority_score      numeric(6,3) NOT NULL,
  guideline_version   varchar(10) NOT NULL,
  dual_label          boolean NOT NULL DEFAULT false,
  status              varchar(15) NOT NULL DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN','IN_PROGRESS','SUBMITTED','AGREED','ADJUDICATION','DONE','DISCARDED')),
  ls_task_id          bigint,
  created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE aip.adjudication (
  task_id           uuid PRIMARY KEY REFERENCES aip.label_task(id),
  approved          boolean NOT NULL,                 -- (a) 검수 승인
  agreement_iou     numeric(4,3),
  agreement_kappa   numeric(4,3),
  final_result      jsonb NOT NULL,
  adjudicator_id    uuid NOT NULL,
  feedback_card     text,
  decided_at        timestamptz NOT NULL DEFAULT now()
);

-- ⑤ 데이터셋 (LakeFS 연동 전: 매니페스트 해시를 커밋 id 로 기록)
CREATE TABLE aip.dataset_version (
  id               uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  name             varchar(60) NOT NULL,
  lakefs_repo      varchar(60) NOT NULL,
  lakefs_commit    char(64) NOT NULL,
  tag              varchar(40) NOT NULL,
  split_manifest   jsonb NOT NULL,
  data_card        jsonb NOT NULL,
  committed_by     uuid NOT NULL,
  committed_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (name, tag)
);

CREATE TABLE aip.dataset_item (
  dataset_version_id uuid NOT NULL REFERENCES aip.dataset_version(id),
  feedback_id        uuid NOT NULL REFERENCES aip.feedback(id),
  label_task_id      uuid NOT NULL REFERENCES aip.label_task(id),
  image_sha256       char(64) NOT NULL,
  split              varchar(6) NOT NULL CHECK (split IN ('train','val')),
  PRIMARY KEY (dataset_version_id, feedback_id)
);
ALTER TABLE aip.feedback ADD CONSTRAINT fk_feedback_dataset
  FOREIGN KEY (committed_dataset_id) REFERENCES aip.dataset_version(id);

-- BR-AIP-012: training_eligible 은 INSERT 때 false, true 전환은 데이터셋 커밋(dataset_item 존재)일 때만, true → false 금지
CREATE FUNCTION aip.fn_feedback_training_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.training_eligible THEN
      RAISE EXCEPTION 'AIP_TRAINING_ELIGIBLE_AT_DECISION: 결정 시점 training_eligible 은 false (BR-AIP-012)';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.training_eligible AND NOT NEW.training_eligible THEN
    RAISE EXCEPTION 'AIP_TRAINING_ELIGIBLE_REVOKE: 커밋된 학습 적격은 되돌릴 수 없음';
  END IF;
  IF NEW.training_eligible AND NOT OLD.training_eligible AND NOT EXISTS (
       SELECT 1 FROM aip.dataset_item i WHERE i.feedback_id = NEW.id AND i.dataset_version_id = NEW.committed_dataset_id) THEN
    RAISE EXCEPTION 'AIP_TRAINING_ELIGIBLE_WITHOUT_COMMIT: 데이터셋 커밋 없이 학습 적격 불가 (BR-AIP-012)';
  END IF;
  IF OLD.decision <> NEW.decision OR OLD.suggestion_id <> NEW.suggestion_id OR OLD.decided_by <> NEW.decided_by THEN
    RAISE EXCEPTION 'AIP_FEEDBACK_IMMUTABLE: 결정 내용은 바꿀 수 없음';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_feedback_training_guard BEFORE INSERT OR UPDATE ON aip.feedback
  FOR EACH ROW EXECUTE FUNCTION aip.fn_feedback_training_guard();

-- ⑥ 추론 감사 (BR-AIP-009, 3년 보존) — v0.2: 월 파티션 PK (job_id, created_at), aip.job FK 는 두지 않고
--    애플리케이션 + 누락 탐지 배치로 보장(TC-AIP-017). append-only.
CREATE TABLE aip.inference_audit (
  job_id              uuid NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  service_code        varchar(30) NOT NULL,
  model_name          varchar(60), model_version varchar(20),
  adapter             varchar(60), prompt_key varchar(60), prompt_version varchar(20),
  input_sha256        char(64) NOT NULL,
  output_sha256       char(64),
  params              jsonb,
  provenance          jsonb,                          -- atomeng_common.vlm.Provenance (서빙 모델 · LoRA · 해시)
  retrieved_chunk_ids text[],
  latency_ms          int,
  gpu_ms              int,
  PRIMARY KEY (job_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE FUNCTION aip.ensure_audit_partition(p_month date) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE
  s date := date_trunc('month', p_month);
  e date := (date_trunc('month', p_month) + interval '1 month')::date;
  n text := format('inference_audit_%s', to_char(s, 'YYYYMM'));
BEGIN
  IF to_regclass('aip.' || n) IS NOT NULL THEN
    RETURN false;
  END IF;
  EXECUTE format('CREATE TABLE aip.%I PARTITION OF aip.inference_audit FOR VALUES FROM (%L) TO (%L)', n, s, e);
  RETURN true;
END $$;
-- 현재월 + 3개월 선생성(설계 B3 p_premake => 3)
SELECT aip.ensure_audit_partition((now() + make_interval(months => m))::date) FROM generate_series(0, 3) AS m;
CREATE INDEX ix_audit_job ON aip.inference_audit (job_id);

CREATE FUNCTION aip.fn_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'AIP_APPEND_ONLY: % 는 추가만 가능 (%)', TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON aip.inference_audit
  FOR EACH ROW EXECUTE FUNCTION aip.fn_append_only();
CREATE TRIGGER trg_audit_no_truncate BEFORE TRUNCATE ON aip.inference_audit
  FOR EACH STATEMENT EXECUTE FUNCTION aip.fn_append_only();
CREATE TRIGGER trg_job_event_append_only BEFORE UPDATE OR DELETE ON aip.job_event
  FOR EACH ROW EXECUTE FUNCTION aip.fn_append_only();

-- 감사 누락 탐지 기록(TC-AIP-017 "누락 탐지 배치가 알림 발생")
CREATE TABLE aip.audit_gap_alert (
  job_id       uuid PRIMARY KEY REFERENCES aip.job(id),
  detected_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at  timestamptz
);
