-- =====================================================================
-- V0008 — FAC PostGIS 컬럼 (프로필 postgis 전용 — 설치 시점에 프로필 결정, out-of-order 금지)
--   설계 02 B3: site.boundary, building.location + GiST 인덱스, 중복 탐지 반경 50m(B7.2)는 이 컬럼 필요
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS postgis;
ALTER TABLE fac.site     ADD COLUMN boundary geometry(MultiPolygon, 4326);
ALTER TABLE fac.building ADD COLUMN location geometry(Point, 4326);
CREATE INDEX ix_building_location ON fac.building USING gist (location);
