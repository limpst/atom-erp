-- 시험 전용(MigrationUpgradePathTest): 기준선 최대 번호보다 낮은 번호로 뒤늦게 추가된 마이그레이션 — 거부되어야 한다.
CREATE TABLE core.zz_late_addition (id int PRIMARY KEY);
