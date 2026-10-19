-- 시험 전용(MigrationUpgradePathTest): 기준선 이후 "새 마이그레이션"을 흉내 내는 탐침. 운영 위치에 포함되지 않는다.
CREATE TABLE core.zz_upgrade_probe (
  id    int PRIMARY KEY,
  label varchar(40) NOT NULL CHECK (label <> '')
);
CREATE INDEX ix_zz_upgrade_probe_label ON core.zz_upgrade_probe (label);
INSERT INTO core.zz_upgrade_probe(id, label) VALUES (1, '합성-탐침');
