import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { CARD_TYPE_LABEL, fcdApi, safeMasked, type CardRow } from '@/api/fcd';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Banner, Card, ErrorBox, fmtMoney, Kpi } from '@/components/ui';
import { useEmployees } from '@/features/admin/common';

/** G3 › 법인카드 목록 — 마스킹 번호만(서버에 PAN 컬럼 없음 — HMAC 토큰 · 앞6 · 뒤4), 퇴사자 비활성 표시 */
export function FcdCardsPage() {
  const { label } = useEmployees();
  const cards = useQuery({ queryKey: ['fcd', 'cards'], queryFn: fcdApi.cards });
  const rows = cards.data ?? [];
  const cols = useMemo<ColDef<CardRow>[]>(
    () => [
      { headerName: '카드 번호(마스킹)', width: 190, valueGetter: (p) => safeMasked(p.data!.masked_no), cellClass: 'mono' },
      { field: 'card_company', headerName: '카드사', width: 100 },
      { field: 'card_type', headerName: '유형', width: 120, valueFormatter: (p) => CARD_TYPE_LABEL[p.value] ?? p.value },
      { field: 'alias', headerName: '별칭', width: 120 },
      { headerName: '소지자', width: 150, valueGetter: (p) => (p.data!.holder_employee_id ? label(p.data!.holder_employee_id) : '공용') },
      { field: 'dept', headerName: '부서', width: 110 },
      { field: 'limit_per_txn', headerName: '건당 한도', width: 120, type: 'numericColumn', valueFormatter: (p) => fmtMoney(p.value) },
      { field: 'limit_daily', headerName: '일 한도', width: 120, type: 'numericColumn', valueFormatter: (p) => fmtMoney(p.value) },
      { field: 'limit_monthly', headerName: '월 한도', width: 130, type: 'numericColumn', valueFormatter: (p) => fmtMoney(p.value) },
      {
        headerName: '상태', flex: 1, minWidth: 180, valueGetter: (p) => p.data!.status,
        cellRenderer: (p: { data: CardRow }) => (
          <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
            <Badge tone={p.data.status === 'ACTIVE' ? 'ok' : undefined}>{p.data.status === 'ACTIVE' ? '사용' : p.data.status}</Badge>
            {p.data.deactivation_flag && <Badge tone="danger" >비활성 권고{p.data.deactivation_reason ? ` — ${p.data.deactivation_reason}` : ''}</Badge>}
          </span>
        ),
      },
    ],
    [label],
  );
  return (
    <>
      <h1>법인카드 목록</h1>
      <Banner tone="info">
        카드 번호는 서버가 저장한 마스킹 값(앞 6 · 뒤 4)만 보입니다. 원래 번호(PAN)는 저장하지 않습니다(HMAC 토큰으로만 대조). 퇴사자 카드는 결제 차단 없이
        “비활성 권고”로 표시됩니다.
      </Banner>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="카드" value={`${rows.length}장`} />
        <Kpi label="비활성 권고" value={`${rows.filter((c) => c.deactivation_flag).length}장`} />
      </div>
      <Card>
        {cards.error && <ErrorBox error={cards.error} />}
        <DataGrid<CardRow> testId="card-grid" rowData={rows} columnDefs={cols} height={340} getRowId={(p) => p.data.id} />
      </Card>
    </>
  );
}
