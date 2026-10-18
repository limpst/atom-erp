-- V3_99 — HRQ PostGIS 컬럼 (프로필 postgis 전용, out-of-order): 추천 거리(동의자), 현장 체크인 좌표
CREATE EXTENSION IF NOT EXISTS postgis;
ALTER TABLE hrq.employee        ADD COLUMN home_location geometry(Point, 4326);
ALTER TABLE hrq.timesheet_entry ADD COLUMN checkin_point geometry(Point, 4326);
