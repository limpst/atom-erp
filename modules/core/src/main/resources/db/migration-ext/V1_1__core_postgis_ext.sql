-- =====================================================================
-- V1_1 — 확장 의존 DDL (프로필 postgis 에서만 Flyway location 에 포함)
--   embedded PostgreSQL(zonky) 바이너리에는 PostGIS 가 없어 테스트 기본 경로에서 제외한다.
--   운영/로컬(docker postgis/postgis:16-3.4, CloudNativePG + PostGIS 이미지)에서는 항상 활성.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE core.client     ADD COLUMN geom geometry(Point, 4326);
ALTER TABLE core.attachment ADD COLUMN captured_geom geometry(Point, 4326);

CREATE INDEX ix_client_name_trgm ON core.client USING gin (name gin_trgm_ops);
CREATE INDEX ix_client_geom      ON core.client USING gist (geom);

-- 설계 7.3 규칙 테이블 (Step 9 대상 — 스키마만 선반영)
CREATE TABLE core.rule_set (
  id             uuid PRIMARY KEY DEFAULT core.uuid_generate_v7(),
  rule_type      varchar(60) NOT NULL,
  legal_basis    varchar(300) NOT NULL,
  effective_from date NOT NULL,
  effective_to   date NOT NULL DEFAULT DATE '9999-12-31',
  body           jsonb NOT NULL,
  verified_by    uuid,
  verified_at    timestamptz,
  status         varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
  EXCLUDE USING gist (rule_type WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
    WHERE (status = 'ACTIVE')
);
