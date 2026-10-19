-- =====================================================================
-- AIP 시드 (⚠️ 예시) — 로컬 개발 서버(vLLM :8080)에 맞춘 레지스트리 초기값 · 프롬프트 · 세부 권한
--   artifact_sha256 은 실제 가중치 해시가 아니라 "미측정" 표시(0 64자) — MLflow 반입 절차(11-IMPL Step 0) 후 교체
-- =====================================================================
INSERT INTO aip.model_version(id, name, version, base_model, artifact_uri, artifact_sha256, stage, metrics) VALUES
 ('01920000-0000-7000-8000-00000000a101', 'crack', '1.0.0', 'gemma-4-12b-it', 'vllm://127.0.0.1:8080/crack',
  repeat('0', 64), 'PRODUCTION', '{"note":"프로토타입 v1 어댑터(공개 데이터 학습) — 예시"}'),
 ('01920000-0000-7000-8000-00000000a102', 'gemma-4-12b-it', '1.0.0', NULL, 'vllm://127.0.0.1:8080/gemma-4-12b-it',
  repeat('0', 64), 'PRODUCTION', NULL),
 ('01920000-0000-7000-8000-00000000a103', 'crack-measure', '0.1.0', NULL, 'pypi-local://atomeng-aip/vision.measure',
  repeat('0', 64), 'PRODUCTION', '{"algorithm":"zhang-skeleton+edt+spur-prune-10px"}');

INSERT INTO aip.prompt_version(id, prompt_key, version, template, template_sha256, status) VALUES
 ('01920000-0000-7000-8000-00000000b101', 'vision.crack.score', '1.0.0',
  'Does this image show a crack? Answer with exactly one word: crack or normal.',
  encode(sha256(convert_to('Does this image show a crack? Answer with exactly one word: crack or normal.', 'UTF8')), 'hex'),
  'ACTIVE'),
 ('01920000-0000-7000-8000-00000000b102', 'vision.defect.finding', '1.0.0', 'atomeng_aip.vision.crack.FINDING_PROMPT',
  encode(sha256(convert_to('atomeng_aip.vision.crack.FINDING_PROMPT@1.0.0', 'UTF8')), 'hex'), 'ACTIVE'),
 ('01920000-0000-7000-8000-00000000b103', 'report.draft.finding', '1.2.0', 'atomeng_aip.llm.draft.PROMPT',
  encode(sha256(convert_to('atomeng_aip.llm.draft.PROMPT@1.2.0', 'UTF8')), 'hex'), 'ACTIVE'),
 ('01920000-0000-7000-8000-00000000b104', 'report.review', '1.0.0', 'atomeng_aip.llm.review.SYSTEM',
  encode(sha256(convert_to('atomeng_aip.llm.review.SYSTEM@1.0.0', 'UTF8')), 'hex'), 'ACTIVE');

INSERT INTO aip.deployment(service_code, alias, model_version_id, prompt_version_id, traffic_pct, active_from) VALUES
 ('CRACK_DETECT',  'production', '01920000-0000-7000-8000-00000000a101', '01920000-0000-7000-8000-00000000b101', 100, '2026-01-01'),
 ('CRACK_MEASURE', 'production', '01920000-0000-7000-8000-00000000a103', NULL, 100, '2026-01-01'),
 ('REPORT_DRAFT',  'production', '01920000-0000-7000-8000-00000000a102', '01920000-0000-7000-8000-00000000b103', 100, '2026-01-01'),
 ('REPORT_REVIEW', 'production', '01920000-0000-7000-8000-00000000a102', '01920000-0000-7000-8000-00000000b104', 100, '2026-01-01'),
 ('KB_SEARCH',     'production', '01920000-0000-7000-8000-00000000a103', NULL, 100, '2026-01-01');

-- 세부 권한(설계 A2 — 8.2 매트릭스 리소스 inference-job · label-task · model · audit-log 밖의 것만)
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ENG', 'aip:suggestion:read', 'PROJECT'), ('ENG', 'aip:suggestion:update', 'PROJECT'),
 ('PM', 'aip:suggestion:read', 'PROJECT'), ('PM', 'aip:suggestion:update', 'PROJECT'),
 ('QA', 'aip:suggestion:read', 'ALL'), ('SYSADMIN', 'aip:suggestion:read', 'ALL'),
 ('QA', 'aip:adjudication:create', 'ALL'), ('SYSADMIN', 'aip:adjudication:create', 'ALL'),
 ('SYSADMIN', 'aip:dataset:create', 'ALL'), ('SYSADMIN', 'aip:consent:update', 'ALL'), ('ADMIN', 'aip:consent:update', 'ALL'),
 ('QA', 'aip:promotion:approve', 'ALL'), ('EXEC', 'aip:promotion:approve', 'ALL'),
 ('SYSADMIN', 'aip:job:operate', 'ALL');
