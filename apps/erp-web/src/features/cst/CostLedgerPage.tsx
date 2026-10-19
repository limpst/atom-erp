import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColDef, RowClassParams } from 'ag-grid-community';
import {
  CORRECTION_ROLE_LABEL, coreApi, correctionGroups, cstApi, type CorrectionRole, type CostElement, type EvmSnapshot, type LedgerRow,
} from '@/api/core';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { LineChart, type ChartSeries } from '@/components/LineChart';
import { Badge, Banner, Card, ErrorBox, Field, fmtMoney, fmtNum, Kpi } from '@/components/ui';
import { useEmployees } from '@/features/admin/common';

const SOURCE_LABEL: Record<string, string> = {
  TIMESHEET: '타임시트', EXPENSE: '경비(FCD)', EQUIP: '장비 사용', SUBCON: '외주', ADJUST: '조정', REVERSAL: '역분개',
};
const ELEMENT_LABEL: Partial<Record<CostElement, string>> = {
  LABOR: '인건비', TRAVEL: '여비', LODGING: '숙박', VEHICLE: '차량', EQUIP_USE: '장비', MATERIAL_TEST: '재료시험', DRONE: '드론',
  SUBCONTRACT: '외주', PRINTING: '인쇄', OVERHEAD: '제경비', TECH_FEE: '기술료', OTHER: '기타',
};

type Row = LedgerRow & { pair: number | null; role: CorrectionRole | null };

export interface LedgerFilter { sourceType: string; costElement: string; employeeId: string; periodYm: string; pairsOnly: boolean }
export function filterLedger(rows: Row[], f: LedgerFilter): Row[] {
  return rows.filter((r) =>
    (!f.sourceType || r.sourceType === f.sourceType)
    && (!f.costElement || r.costElement === f.costElement)
    && (!f.employeeId || r.employeeId === f.employeeId)
    && (!f.periodYm || r.periodYm === f.periodYm)
    && (!f.pairsOnly || r.pair !== null));
}

const fmtAxisWon = (v: number) =>
  Math.abs(v) >= 1e6 ? `${(v / 1e6).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}백만` : v.toLocaleString('ko-KR');

/** EVM 추이 차트 — PV · EV · AC(권한에 따라 실원가 또는 표준원가) + 오른쪽 축 CPI · SPI */
function EvmTrend({ snaps }: { snaps: EvmSnapshot[] }) {
  const real = snaps.some((s) => s.acBasis === 'REAL');
  const x = useMemo(() => snaps.map((s) => s.asOf), [snaps]);
  const series = useMemo<ChartSeries[]>(
    () => [
      { label: 'PV 계획가치', values: snaps.map((s) => s.pv), color: '--text-2', dash: true },
      { label: 'EV 획득가치', values: snaps.map((s) => s.ev), color: '--ok' },
      { label: real ? 'AC 실원가' : 'AC 표준원가', values: snaps.map((s) => (real ? s.ac : s.acStd)), color: '--danger' },
      { label: real ? 'CPI' : 'CPI(표준)', values: snaps.map((s) => (real ? s.cpi : s.cpiStd)), color: '--primary', rightAxis: true },
      { label: 'SPI', values: snaps.map((s) => s.spi), color: '--warn', rightAxis: true },
    ],
    [snaps, real],
  );
  const last = snaps[snaps.length - 1];
  return (
    <Card title={<>EVM 추이 <Badge tone="info">{real ? '실원가(AC)' : '표준단가 AC — 실단가 열람 권한 없음'}</Badge></>}>
      <LineChart testId="evm-trend" ariaLabel="EVM 추이" x={x} series={series} yLabel="원" y2Label="지수" height={280}
        valueFormat={fmtAxisWon} />
      {last && (
        <div className="kpis" style={{ marginTop: 8 }}>
          <Kpi label={`기준일 ${last.asOf}`} value={`스냅샷 ${snaps.length}개`} />
          <Kpi label="BAC" value={fmtMoney(last.bac)} />
          <Kpi label="SPI" value={fmtNum(last.spi, 3)} tone={last.spi !== null && last.spi < 0.9 ? 'warn' : undefined} />
          <Kpi label={real ? 'CPI' : 'CPI(표준)'} value={fmtNum(real ? last.cpi : last.cpiStd, 3)} />
          <Kpi label="경보" value={last.alertLevel ?? '—'} tone={last.alertLevel === 'RED' ? 'danger' : last.alertLevel === 'YELLOW' ? 'warn' : 'ok'} />
        </div>
      )}
      <p className="muted" style={{ fontSize: 12 }}>스냅샷은 EVM 조회(GET …/evm?asOf=) 때마다 기준일별로 저장됩니다.</p>
    </Card>
  );
}

/** G3 › 원가 원장 — 필터(기간 · 원천 · 원가요소 · 기술자 · 귀속월), 정정 짝(원 행 ↔ 역분개) 강조, EVM 추이 */
export function CostLedgerPage() {
  const { hasRole } = useAuth();
  const { label, employees } = useEmployees();
  const [params, setParams] = useSearchParams();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects() });
  const projectId = params.get('projectId') ?? '';
  useEffect(() => {
    if (!projectId && projects.data?.length) {
      const p = projects.data.find((x) => x.serviceType !== 'DEMOLITION_SUPERVISION') ?? projects.data[0];
      setParams({ projectId: p.id }, { replace: true });
    }
  }, [projects.data, projectId, setParams]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [f, setF] = useState<LedgerFilter>({ sourceType: '', costElement: '', employeeId: '', periodYm: '', pairsOnly: false });
  const ledger = useQuery({
    queryKey: ['cst', projectId, 'ledger', from, to], queryFn: () => cstApi.ledger(projectId, from || undefined, to || undefined),
    enabled: !!projectId,
  });
  const canEvm = hasRole('EXEC', 'PM', 'ADMIN', 'FIN');
  const snaps = useQuery({ queryKey: ['cst', projectId, 'evm-snapshots'], queryFn: () => cstApi.evmSnapshots(projectId), enabled: !!projectId && canEvm });

  const all: Row[] = useMemo(() => {
    const groups = correctionGroups(ledger.data ?? []);
    return (ledger.data ?? []).map((r) => ({ ...r, pair: groups.get(r.id)?.pair ?? null, role: groups.get(r.id)?.role ?? null }));
  }, [ledger.data]);
  const rows = useMemo(() => filterLedger(all, f), [all, f]);
  const periods = useMemo(() => [...new Set(all.map((r) => r.periodYm))].sort().reverse(), [all]);
  const masked = all.some((r) => r.amountKrwMasked);
  const pairCount = new Set(all.filter((r) => r.role === 'REVERSAL').map((r) => r.pair)).size;
  const sumStd = rows.reduce((a, r) => a + Number(r.amountStdKrw ?? 0), 0);

  const cols = useMemo<ColDef<Row>[]>(
    () => [
      {
        headerName: '정정 짝', width: 130, valueGetter: (p) => (p.data!.pair ? `#${p.data!.pair}` : ''),
        cellRenderer: (p: { data: Row }) => (p.data.pair && p.data.role ? (
          <Badge tone={p.data.role === 'REVERSAL' ? 'danger' : p.data.role === 'ORIGINAL' ? 'warn' : 'ok'}>
            #{p.data.pair} {CORRECTION_ROLE_LABEL[p.data.role]}
          </Badge>
        ) : null),
      },
      { field: 'periodYm', headerName: '귀속월', width: 90 },
      { field: 'postingDate', headerName: '전기일', width: 110 },
      { field: 'sourceType', headerName: '원천', width: 110, valueFormatter: (p) => SOURCE_LABEL[p.value] ?? p.value },
      { field: 'costElement', headerName: '원가요소', width: 100, valueFormatter: (p) => ELEMENT_LABEL[p.value as CostElement] ?? p.value },
      { headerName: '기술자', width: 140, valueGetter: (p) => (p.data!.employeeId ? label(p.data!.employeeId) : '—') },
      { field: 'gradeCode', headerName: '등급', width: 90 },
      { field: 'hours', headerName: '시간', width: 80, type: 'numericColumn' },
      { field: 'amountStdKrw', headerName: '표준원가', width: 130, type: 'numericColumn', valueFormatter: (p) => fmtMoney(p.value) },
      {
        field: 'amountKrw', headerName: '실원가', width: 130, type: 'numericColumn',
        valueFormatter: (p) => (p.data?.amountKrwMasked ? '•••• (권한 없음)' : fmtMoney(p.value)),
      },
      { field: 'sourceRef', headerName: '업무 멱등키', flex: 1, minWidth: 160 },
    ],
    [label],
  );
  const rowClassRules = useMemo(
    () => ({
      'row-reversal': (p: RowClassParams<Row>) => p.data?.sourceType === 'REVERSAL',
      'row-reversed': (p: RowClassParams<Row>) => p.data?.role === 'ORIGINAL',
      'row-replacement': (p: RowClassParams<Row>) => p.data?.role === 'REPLACEMENT',
    }),
    [],
  );

  return (
    <>
      <h1>원가 원장 · EVM</h1>
      <Card>
        <div className="form">
          <Field label="프로젝트">
            <select value={projectId} onChange={(e) => setParams({ projectId: e.target.value })} aria-label="프로젝트 선택">
              {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.projectNo} {p.name}</option>)}
            </select>
          </Field>
          <Field label="전기일 from(서버)">
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="전기일 시작" />
          </Field>
          <Field label="전기일 to(서버)">
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="전기일 끝" />
          </Field>
          <Field label="귀속월">
            <select value={f.periodYm} onChange={(e) => setF({ ...f, periodYm: e.target.value })} aria-label="귀속월 필터">
              <option value="">전체</option>
              {periods.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </Field>
          <Field label="원천">
            <select value={f.sourceType} onChange={(e) => setF({ ...f, sourceType: e.target.value })} aria-label="원천 필터">
              <option value="">전체</option>
              {Object.entries(SOURCE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </Field>
          <Field label="원가요소">
            <select value={f.costElement} onChange={(e) => setF({ ...f, costElement: e.target.value })} aria-label="원가요소 필터">
              <option value="">전체</option>
              {Object.entries(ELEMENT_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </Field>
          <Field label="기술자">
            <select value={f.employeeId} onChange={(e) => setF({ ...f, employeeId: e.target.value })} aria-label="기술자 필터">
              <option value="">전체</option>
              {employees.map((e) => <option key={e.id} value={e.id}>{e.empNo} {e.name}</option>)}
            </select>
          </Field>
          <label className="row" style={{ gap: 6 }}>
            <input type="checkbox" checked={f.pairsOnly} onChange={(e) => setF({ ...f, pairsOnly: e.target.checked })} aria-label="정정 짝만" />
            정정 짝만 보기
          </label>
        </div>
      </Card>
      {masked && <Banner tone="info">실원가 열은 실단가 열람 권한(cst:cost-real:read — EXEC · ADMIN)이 없어 가려집니다. 표준원가만 봅니다.</Banner>}
      <Card title={<>원장 <span className="muted" data-testid="ledger-count">{rows.length}행 / 전체 {all.length}행 · 정정 짝 {pairCount}쌍 · 표준원가 합계 {fmtMoney(sumStd)}</span></>}>
        {ledger.error && <ErrorBox error={ledger.error} />}
        <div className="legend" style={{ marginBottom: 6 }}>
          <span><i style={{ background: 'color-mix(in srgb, var(--warn) 25%, transparent)' }} />정정된 원 행</span>
          <span><i style={{ background: 'color-mix(in srgb, var(--danger) 22%, transparent)' }} />역분개(REVERSAL, 음수)</span>
          <span><i style={{ background: 'color-mix(in srgb, var(--ok) 20%, transparent)' }} />정정 후 새 행(같은 업무 멱등키)</span>
        </div>
        <DataGrid<Row> testId="cost-ledger-grid" rowData={rows} columnDefs={cols} rowClassRules={rowClassRules} height={360} getRowId={(p) => p.data.id} />
      </Card>
      {canEvm && (snaps.error ? <ErrorBox error={snaps.error} /> : <EvmTrend snaps={snaps.data ?? []} />)}
    </>
  );
}
