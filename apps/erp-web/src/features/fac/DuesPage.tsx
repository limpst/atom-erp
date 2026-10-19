import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { dDay, DUE_STATUS_LABEL, facApi, INSPECTION_TYPE_LABEL, type DueItem } from '@/api/fac';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Kpi } from '@/components/ui';

/** G2 › 점검 도래 — 전 시설물의 다음 법정 점검일(D-day 배지) */
export function DuesPage() {
  const nav = useNavigate();
  const [within, setWithin] = useState<string>('365');
  const days = within === '' ? undefined : Number(within);
  const list = useQuery({ queryKey: ['fac', 'dueList', days], queryFn: () => facApi.dueList(days) });
  const rows = list.data ?? [];
  const overdue = rows.filter((r) => r.daysLeft < 0).length;
  const soon = rows.filter((r) => r.daysLeft >= 0 && r.daysLeft <= 30).length;

  const cols = useMemo<ColDef<DueItem>[]>(
    () => [
      {
        headerName: 'D-day', width: 130, sort: 'asc', comparator: (a, b) => a - b, valueGetter: (p) => p.data!.daysLeft,
        cellRenderer: (p: { data: DueItem }) => {
          const d = dDay(p.data.daysLeft);
          return <Badge tone={d.tone}>{d.label}</Badge>;
        },
      },
      { headerName: '시설물', flex: 2, minWidth: 200, valueGetter: (p) => `${p.data!.buildingName}${p.data!.dongName ? ` ${p.data!.dongName}` : ''}` },
      { headerName: '점검 종류', width: 150, valueGetter: (p) => INSPECTION_TYPE_LABEL[p.data!.inspectionType] ?? p.data!.inspectionType },
      { field: 'dueDate', headerName: '도래일', width: 120 },
      { headerName: '기준일', width: 120, valueGetter: (p) => p.data!.baseDate ?? '사용승인일' },
      { headerName: '알림 단계', width: 120, valueGetter: (p) => DUE_STATUS_LABEL[p.data!.status] ?? p.data!.status },
      { field: 'roadAddress', headerName: '주소', flex: 1, minWidth: 160 },
    ],
    [],
  );

  return (
    <>
      <h1>점검 도래</h1>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="표시 중" value={`${rows.length}건`} />
        <Kpi label="기한 경과" value={`${overdue}건`} tone={overdue ? 'danger' : undefined} />
        <Kpi label="30일 이내" value={`${soon}건`} tone={soon ? 'warn' : undefined} />
      </div>
      <Card
        title="법정 점검 도래 목록"
        actions={
          <select aria-label="도래 기간" value={within} onChange={(e) => setWithin(e.target.value)}>
            <option value="30">30일 이내(경과 포함)</option>
            <option value="90">90일 이내</option>
            <option value="180">180일 이내</option>
            <option value="365">1년 이내</option>
            <option value="">전체</option>
          </select>
        }
      >
        {list.error && <ErrorBox error={list.error} />}
        <DataGrid<DueItem>
          testId="due-grid"
          rowData={rows}
          columnDefs={cols}
          height={460}
          getRowId={(p) => p.data.id}
          onRowClicked={(e) => e.data && nav(`/fac/buildings/${e.data.buildingId}`)}
          rowStyle={{ cursor: 'pointer' }}
        />
        <p className="muted">
          D-day = 도래일 − 오늘. 빨강: 경과 또는 30일 이내, 주황: 90일 이내. 도래일은 법적 분류 · 최근 등급 · 사용승인일로 주기 엔진이 계산합니다.
        </p>
      </Card>
    </>
  );
}
