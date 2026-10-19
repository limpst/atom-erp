import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  CRED_STATUS_LABEL, credentialChip, hrqApi, type CredentialRow, type CredentialStatus, type EmployeeRow,
} from '@/api/hrq';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtDate, Kpi, Modal } from '@/components/ui';
import { todayIso } from '@/features/admin/common';

const FIELD_LABEL: Record<string, string> = { ARCH: '건축', CIVIL: '토목', SAFETY: '안전', STRUCT: '구조', OTHER: '기타' };
const EMP_STATUS: Record<EmployeeRow['status'], { label: string; tone?: 'ok' | 'warn' | 'danger' }> = {
  ACTIVE: { label: '재직', tone: 'ok' }, LEAVE: { label: '휴직', tone: 'warn' },
  RESIGN_PLANNED: { label: '퇴사 예정', tone: 'warn' }, RESIGNED: { label: '퇴사', tone: 'danger' },
};

export function CredentialChip({ c, today }: { c: Pick<CredentialRow, 'status' | 'expires_on'>; today: string }) {
  const chip = credentialChip(c, today);
  return <Badge tone={chip.tone}>{chip.label}</Badge>;
}

function RejectDialog({ row, onClose, onDone }: { row: CredentialRow; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const reject = useMutation({
    mutationFn: () => hrqApi.rejectCredential(row.id, reason.trim()),
    onSuccess: () => {
      toast.success(`자격 반려 — ${row.employee_name} ${row.type_name}`);
      onDone();
      onClose();
    },
  });
  return (
    <Modal
      title={`자격 반려 — ${row.employee_name}`}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>취소</button>
          <button className="danger" disabled={!reason.trim() || reject.isPending} onClick={() => reject.mutate()} data-testid="cred-reject-confirm">
            반려
          </button>
        </>
      }
    >
      <p className="muted">{row.type_name} · {row.cert_no ?? '번호 없음'} · 취득 {row.acquired_on}</p>
      <Field label="반려 사유(필수 — 본인에게 안내됩니다)">
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} aria-label="반려 사유" />
      </Field>
    </Modal>
  );
}

/** G5 › 인력 · 자격 — 직원 대장, 자격(만료 칩 · ADMIN 승인/반려) */
export function HrqPeoplePage() {
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const today = todayIso();
  const [tab, setTab] = useState<'emp' | 'cred'>('cred');
  const [status, setStatus] = useState<CredentialStatus | ''>('');
  const [rejecting, setRejecting] = useState<CredentialRow>();
  const isAdmin = hasRole('ADMIN');
  const employees = useQuery({ queryKey: ['hrq', 'employees'], queryFn: hrqApi.employees });
  const creds = useQuery({ queryKey: ['hrq', 'credentials'], queryFn: () => hrqApi.credentials() });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hrq', 'credentials'] });
    qc.invalidateQueries({ queryKey: ['hrq', 'compliance'] });
  };
  const approve = useMutation({
    mutationFn: (r: CredentialRow) => hrqApi.approveCredential(r.id),
    onSuccess: (_d, r) => {
      toast.success(`자격 승인 — ${r.employee_name} ${r.type_name}`);
      refresh();
    },
  });

  const all = creds.data ?? [];
  const rows = status ? all.filter((c) => c.status === status) : all;
  const pending = all.filter((c) => c.status === 'PENDING').length;
  const soon = all.filter((c) => {
    const ch = credentialChip(c, today);
    return ch.tone === 'warn' && c.status !== 'PENDING';
  }).length;
  const expired = all.filter((c) => credentialChip(c, today).tone === 'danger').length;

  const credCols = useMemo<ColDef<CredentialRow>[]>(
    () => [
      { headerName: '직원', width: 150, valueGetter: (p) => `${p.data!.emp_no} ${p.data!.employee_name}` },
      { field: 'type_name', headerName: '자격', flex: 1, minWidth: 180 },
      { field: 'cert_no', headerName: '번호', width: 170 },
      { field: 'acquired_on', headerName: '취득일', width: 110 },
      { field: 'expires_on', headerName: '만료일', width: 110, valueFormatter: (p) => p.value ?? '무기한' },
      {
        headerName: '상태', width: 160, valueGetter: (p) => credentialChip(p.data!, today).label,
        cellRenderer: (p: { data: CredentialRow }) => <CredentialChip c={p.data} today={today} />,
      },
      { field: 'reject_reason', headerName: '반려 사유', width: 180 },
      ...(isAdmin
        ? [{
            headerName: '', width: 170, sortable: false,
            cellRenderer: (p: { data: CredentialRow }) =>
              p.data.status === 'PENDING' ? (
                <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                  <button className="primary" aria-label={`승인 ${p.data.employee_name} ${p.data.type_name}`} onClick={() => approve.mutate(p.data)}>
                    승인
                  </button>
                  <button className="danger" aria-label={`반려 ${p.data.employee_name} ${p.data.type_name}`} onClick={() => setRejecting(p.data)}>
                    반려
                  </button>
                </span>
              ) : null,
          } as ColDef<CredentialRow>]
        : []),
    ],
    [isAdmin, today, approve],
  );
  const empCols = useMemo<ColDef<EmployeeRow>[]>(
    () => [
      { field: 'empNo', headerName: '사번', width: 100 },
      { field: 'name', headerName: '이름', width: 120 },
      { field: 'field', headerName: '분야', width: 90, valueFormatter: (p) => FIELD_LABEL[p.value] ?? p.value },
      { field: 'employmentType', headerName: '고용', width: 110 },
      { field: 'registeredField', headerName: '등록 분야', width: 100, valueFormatter: (p) => (p.value ? '예' : '아니오') },
      {
        headerName: '상태', width: 110, valueGetter: (p) => EMP_STATUS[p.data!.status].label,
        cellRenderer: (p: { data: EmployeeRow }) => <Badge tone={EMP_STATUS[p.data.status].tone}>{EMP_STATUS[p.data.status].label}</Badge>,
      },
      { field: 'hiredOn', headerName: '입사일', width: 110 },
      { field: 'resignPlannedOn', headerName: '퇴사 예정일', width: 120, valueFormatter: (p) => fmtDate(p.value) },
      {
        headerName: '자격', flex: 1, minWidth: 220, sortable: false,
        valueGetter: (p) => all.filter((c) => c.employee_id === p.data!.id && ['VALID', 'EXPIRING', 'EXPIRED', 'PENDING'].includes(c.status))
          .map((c) => `${c.type_name}(${CRED_STATUS_LABEL[c.status]})`).join(', '),
      },
    ],
    [all],
  );

  return (
    <>
      <h1>인력 · 자격</h1>
      <div className="kpis" style={{ marginBottom: 16 }} data-testid="cred-kpis">
        <Kpi label="직원" value={`${employees.data?.length ?? 0}명`} />
        <Kpi label="자격 승인 대기" value={<span data-testid="kpi-pending">{pending}건</span>} tone={pending ? 'warn' : undefined} />
        <Kpi label="90일 내 만료" value={`${soon}건`} tone={soon ? 'warn' : undefined} />
        <Kpi label="만료" value={`${expired}건`} tone={expired ? 'danger' : undefined} />
      </div>
      <Card>
        <div className="tabs" role="tablist">
          <button role="tab" className={tab === 'cred' ? 'active' : ''} onClick={() => setTab('cred')}>자격 ({all.length})</button>
          <button role="tab" className={tab === 'emp' ? 'active' : ''} onClick={() => setTab('emp')}>직원 대장 ({employees.data?.length ?? 0})</button>
        </div>
        {tab === 'cred' ? (
          <>
            <div className="row" style={{ marginBottom: 8 }}>
              <Field label="상태 필터">
                <select value={status} onChange={(e) => setStatus(e.target.value as CredentialStatus | '')} aria-label="자격 상태 필터">
                  <option value="">전체</option>
                  {(Object.keys(CRED_STATUS_LABEL) as CredentialStatus[]).map((s) => (
                    <option key={s} value={s}>{CRED_STATUS_LABEL[s]}</option>
                  ))}
                </select>
              </Field>
              <span className="muted">
                승인 · 반려는 경영지원(ADMIN)만 합니다. 승인 대기(PENDING) 자격은 등록요건 · 감리원 자격 확인에 쓰이지 않습니다(BR-HRQ-005).
              </span>
            </div>
            {creds.error && <ErrorBox error={creds.error} />}
            <DataGrid<CredentialRow> testId="cred-grid" rowData={rows} columnDefs={credCols} height={380} getRowId={(p) => p.data.id} />
          </>
        ) : (
          <>
            {employees.error && <ErrorBox error={employees.error} />}
            <DataGrid<EmployeeRow> testId="emp-grid" rowData={employees.data ?? []} columnDefs={empCols} height={380} getRowId={(p) => p.data.id} />
          </>
        )}
      </Card>
      {rejecting && <RejectDialog row={rejecting} onClose={() => setRejecting(undefined)} onDone={refresh} />}
    </>
  );
}
