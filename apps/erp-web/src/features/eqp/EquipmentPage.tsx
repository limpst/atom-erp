import { useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { CAL_STATUS, EQUIPMENT_STATUS_LABEL, eqpApi, type CalibrationStatus } from '@/api/eqp';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Banner, Card, ErrorBox, Kpi, Modal } from '@/components/ui';
import { useAuth } from '@/auth/auth';

interface Row extends CalibrationStatus { model: string | null; serialNo: string | null; homeLocation: string | null }

export function CalChip({ s }: { s: Pick<CalibrationStatus, 'calStatus' | 'daysLeft'> }) {
  const c = CAL_STATUS[s.calStatus];
  const suffix = s.daysLeft === null ? '' : s.daysLeft < 0 ? ` D+${-s.daysLeft}` : ` D-${s.daysLeft}`;
  return <Badge tone={c.tone}>{c.label}{s.calStatus === 'NOT_REQUIRED' || s.calStatus === 'NONE' ? '' : suffix}</Badge>;
}

function LabelDialog({ row, onClose }: { row: Row; onClose: () => void }) {
  const issue = useMutation({ mutationFn: () => eqpApi.issueLabel(row.equipmentId) });
  return (
    <Modal title={`QR 라벨 — ${row.code}`} onClose={onClose} footer={<button onClick={onClose}>닫기</button>}>
      {!issue.data ? (
        <>
          <p>현재 라벨 서명으로 QR 토큰을 발급합니다(회전하지 않음 — 기존 라벨도 계속 유효).</p>
          <button className="primary" onClick={() => issue.mutate()} disabled={issue.isPending} data-testid="label-issue">
            토큰 발급
          </button>
        </>
      ) : (
        <>
          <p className="muted">라벨 QR 에 인쇄되는 토큰입니다. 현장 앱 대신 반출 화면의 「QR 토큰」 칸에 붙여 넣을 수 있습니다.</p>
          <pre className="mono" data-testid="label-token" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{issue.data.token}</pre>
          <p className="muted mono" style={{ fontSize: 11 }}>{issue.data.qrUrl}</p>
          <button onClick={() => navigator.clipboard?.writeText(issue.data!.token)}>복사</button>
        </>
      )}
    </Modal>
  );
}

/** G5 › 장비 — 목록(교정 상태 칩) · 교정 만료 목록 · QR 라벨 */
export function EquipmentPage() {
  const { hasRole } = useAuth();
  const [tab, setTab] = useState<'all' | 'expiring'>('all');
  const [labelFor, setLabelFor] = useState<Row>();
  const cal = useQuery({ queryKey: ['eqp', 'cal'], queryFn: () => eqpApi.calibrationStatus() });
  const expiring = useQuery({ queryKey: ['eqp', 'cal', 60], queryFn: () => eqpApi.calibrationStatus(60) });
  const eq = useQuery({ queryKey: ['eqp', 'equipment'], queryFn: eqpApi.list, meta: { silent: true } });
  const byId = useMemo(() => new Map((eq.data?.content ?? []).map((e) => [e.id, e])), [eq.data]);
  const rows: Row[] = useMemo(
    () => (cal.data ?? []).map((c) => {
      const e = byId.get(c.equipmentId);
      return { ...c, model: e?.model ?? null, serialNo: e?.serialNo ?? null, homeLocation: e?.homeLocation ?? null };
    }),
    [cal.data, byId],
  );
  const count = (k: CalibrationStatus['calStatus']) => rows.filter((r) => r.calStatus === k).length;

  const cols = useMemo<ColDef<Row>[]>(
    () => [
      { field: 'code', headerName: '장비 코드', width: 170 },
      { field: 'typeName', headerName: '종류', width: 160 },
      { field: 'model', headerName: '모델', flex: 1, minWidth: 140 },
      { headerName: '상태', width: 130, valueGetter: (p) => EQUIPMENT_STATUS_LABEL[p.data!.status] ?? p.data!.status },
      {
        headerName: '교정', width: 170, valueGetter: (p) => CAL_STATUS[p.data!.calStatus].label,
        cellRenderer: (p: { data: Row }) => <CalChip s={p.data} />,
      },
      { field: 'nextDueOn', headerName: '차기 교정일', width: 125 },
      { field: 'homeLocation', headerName: '보관 위치', width: 150 },
      ...(hasRole('ADMIN', 'SYSADMIN')
        ? [{
            headerName: '', width: 110, sortable: false,
            cellRenderer: (p: { data: Row }) => (
              <button onClick={() => setLabelFor(p.data)} aria-label={`QR 라벨 ${p.data.code}`}>QR 라벨</button>
            ),
          } as ColDef<Row>]
        : []),
    ],
    [hasRole],
  );

  return (
    <>
      <h1>장비</h1>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="장비" value={`${rows.length}대`} />
        <Kpi label="교정 만료" value={`${count('EXPIRED') + count('FAILED')}대`} tone={count('EXPIRED') ? 'danger' : undefined} />
        <Kpi label="60일 이내 교정" value={`${count('DUE_SOON')}대`} tone={count('DUE_SOON') ? 'warn' : undefined} />
        <Kpi label="교정 이력 없음" value={`${count('NONE')}대`} />
      </div>
      <Card>
        <div className="tabs" role="tablist">
          <button role="tab" className={tab === 'all' ? 'active' : ''} onClick={() => setTab('all')}>장비 목록</button>
          <button role="tab" className={tab === 'expiring' ? 'active' : ''} onClick={() => setTab('expiring')}>
            교정 만료 목록 ({expiring.data?.length ?? 0})
          </button>
        </div>
        {(cal.error || expiring.error) && <ErrorBox error={cal.error ?? expiring.error} />}
        {tab === 'all' ? (
          <DataGrid<Row> testId="eqp-grid" rowData={rows} columnDefs={cols} height={380} getRowId={(p) => p.data.equipmentId} />
        ) : (
          <>
            <Banner tone="warn">
              교정 만료 · 불합격 · 이력 없음 · 60일 이내 도래 장비입니다. 교정이 만료된 장비는 반출이 막히고(EQP-409-001), 예약 기간 중 만료되면
              예약도 막힙니다(EQP-409-009). 품질검토자(QA)가 최대 7일 예외를 승인할 수 있습니다.
            </Banner>
            <table className="simple" data-testid="cal-expiring">
              <thead><tr><th>장비</th><th>종류</th><th>차기 교정일</th><th>교정 상태</th><th>장비 상태</th></tr></thead>
              <tbody>
                {(expiring.data ?? []).map((r) => (
                  <tr key={r.equipmentId}>
                    <td className="mono">{r.code}</td>
                    <td>{r.typeName}</td>
                    <td>{r.nextDueOn ?? '—'}</td>
                    <td><CalChip s={r} /></td>
                    <td>{EQUIPMENT_STATUS_LABEL[r.status]}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Card>
      {labelFor && <LabelDialog row={labelFor} onClose={() => setLabelFor(undefined)} />}
    </>
  );
}
