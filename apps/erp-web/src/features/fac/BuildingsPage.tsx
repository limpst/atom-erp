import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { BUILDING_STATUS_LABEL, DATA_SOURCE_LABEL, facApi, type Building } from '@/api/fac';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { RegisterWizard } from './RegisterWizard';

export const statusTone = (s: Building['status']) =>
  s === 'MERGED' || s === 'DEMOLISHED' || s === 'INACTIVE' ? undefined : s === 'DRAFT' || s === 'RECLASSIFY_REQUIRED' ? 'warn' : 'ok';

/** G2 › 시설물 — 목록 · 검색 · 등록 */
export function BuildingsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [q, setQ] = useState('');
  const [applied, setApplied] = useState('');
  const list = useQuery({ queryKey: ['fac', 'buildings', applied], queryFn: () => facApi.list(applied || undefined) });

  const cols = useMemo<ColDef<Building>[]>(
    () => [
      { headerName: '건물명', flex: 2, minWidth: 200, valueGetter: (p) => `${p.data!.name}${p.data!.dongName ? ` ${p.data!.dongName}` : ''}` },
      { field: 'roadAddress', headerName: '도로명주소', flex: 2, minWidth: 200 },
      {
        headerName: '상태', width: 120, valueGetter: (p) => BUILDING_STATUS_LABEL[p.data!.status],
        cellRenderer: (p: { data: Building }) => <Badge tone={statusTone(p.data.status)}>{BUILDING_STATUS_LABEL[p.data.status]}</Badge>,
      },
      { headerName: '출처', width: 110, valueGetter: (p) => DATA_SOURCE_LABEL[p.data!.dataSource] },
      { headerName: '층수', width: 110, valueGetter: (p) => `지상 ${p.data!.floorsAbove ?? '—'} / 지하 ${p.data!.floorsBelow ?? '—'}` },
      { field: 'approvalDate', headerName: '사용승인', width: 115 },
      { field: 'structureNm', headerName: '구조', width: 140 },
    ],
    [],
  );

  return (
    <>
      <h1>시설물</h1>
      <Card
        title={`시설물 목록${list.data ? ` (${list.data.totalElements})` : ''}`}
        actions={
          <form
            className="row"
            onSubmit={(e) => {
              e.preventDefault();
              setApplied(q.trim());
            }}
          >
            <input aria-label="건물명 검색" placeholder="건물명 검색" value={q} onChange={(e) => setQ(e.target.value)} />
            <button type="submit">검색</button>
          </form>
        }
      >
        {list.error && <ErrorBox error={list.error} />}
        <DataGrid<Building>
          testId="fac-grid"
          rowData={list.data?.content}
          columnDefs={cols}
          height={360}
          getRowId={(p) => p.data.id}
          onRowClicked={(e) => e.data && nav(`/fac/buildings/${e.data.id}`)}
          rowStyle={{ cursor: 'pointer' }}
        />
      </Card>
      {hasRole('SALES', 'PM', 'SYSADMIN') ? (
        <RegisterWizard
          onRegistered={(b) => {
            qc.invalidateQueries({ queryKey: ['fac'] });
            nav(`/fac/buildings/${b.id}`);
          }}
        />
      ) : (
        <p className="muted">시설물 등록은 영업 · 책임기술자 역할만 할 수 있습니다.</p>
      )}
    </>
  );
}
