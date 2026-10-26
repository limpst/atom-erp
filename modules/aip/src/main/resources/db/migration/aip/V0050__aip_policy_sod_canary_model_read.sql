-- =====================================================================
-- 보안 검토 2026-10-19 정책 결정(권장안 적용) — AIP
--  AIP-01b : QA 품질 승인자 ≠ EXEC 최종 승인자(4-eyes) — 서비스 검사 + DB CHECK
--  AIP-08  : 라벨 판정자 ≠ 라벨러 — label_task.labeled_by(라벨 제출자, Label Studio 연동 시 기록) 추가,
--            판정 INSERT 트리거가 제출자 · 원천 피드백 결정자(aip.feedback.decided_by)와 같으면 거부
--  웹 3차  : SYSADMIN 이 모델 레지스트리 목록을 못 보던 문제 → aip:model:read(ALL) 신설, 지금 모델을 읽던 역할
--            (EXEC · PM · ENG · QA, 기존 aip:inference-job:read) + SYSADMIN(승격 담당)
-- =====================================================================

ALTER TABLE aip.model_version
  ADD CONSTRAINT ck_model_version_four_eyes
  CHECK (approved_by IS NULL OR exec_approved_by IS NULL OR approved_by <> exec_approved_by);

ALTER TABLE aip.label_task ADD COLUMN labeled_by uuid;   -- 라벨 제출자(라벨 제출 API · Label Studio 연동 시 기록)

CREATE FUNCTION aip.fn_adjudication_sod_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM aip.label_task t
               LEFT JOIN aip.feedback f ON f.id = t.feedback_id
              WHERE t.id = NEW.task_id
                AND (t.labeled_by = NEW.adjudicator_id OR f.decided_by = NEW.adjudicator_id)) THEN
    RAISE EXCEPTION 'AIP_ADJUDICATOR_IS_LABELER: 라벨 판정자는 라벨러와 달라야 합니다 (AIP-08)';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_adjudication_sod BEFORE INSERT OR UPDATE ON aip.adjudication
  FOR EACH ROW EXECUTE FUNCTION aip.fn_adjudication_sod_guard();

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC', 'aip:model:read', 'ALL'), ('PM', 'aip:model:read', 'ALL'), ('ENG', 'aip:model:read', 'ALL'),
 ('QA', 'aip:model:read', 'ALL'), ('SYSADMIN', 'aip:model:read', 'ALL');
