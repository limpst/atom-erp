import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import { SALES_GROUP_LABEL, salesGroupApi, type SalesGroupMember } from '@/api/admin';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtDateTime } from '@/components/ui';
import { EmployeeSelect, RoleGate, useEmployees } from './common';

/** 공통 › 관리 › 영업팀장 그룹 (SAL BR-SAL-006 할인 한도 10%, ADMIN 전용) */
function EditPanel({ m, name, onDone }: { m: SalesGroupMember; name: string; onDone: () => void }) {
  const qc = useQueryClient();
  const [note, setNote] = useState(m.note ?? '');
  const save = useMutation({
    mutationFn: () => salesGroupApi.update(m, { note }),
    onSuccess: () => {
      toast.success(`${name} 메모 저장`);
      qc.invalidateQueries({ queryKey: ['sal', 'sales-group'] });
      onDone();
    },
  });
  return (
    <Card title={`수정 — ${name}`}>
      <div className="form">
        <Field label="메모">
          <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} data-testid="sg-edit-note" />
        </Field>
        <button className="primary" onClick={() => save.mutate()} disabled={save.isPending}>
          저장
        </button>
        <button onClick={onDone}>닫기</button>
      </div>
    </Card>
  );
}

function SalesGroupInner() {
  const qc = useQueryClient();
  const { employees, label } = useEmployees();
  const list = useQuery({ queryKey: ['sal', 'sales-group'], queryFn: salesGroupApi.list, meta: { silent: true } });
  const [employeeId, setEmployeeId] = useState('');
  const [note, setNote] = useState('');
  const [editing, setEditing] = useState<SalesGroupMember>();
  const members = useMemo(() => new Set((list.data ?? []).map((m) => m.employeeId)), [list.data]);

  const add = useMutation({
    mutationFn: () => salesGroupApi.add({ employeeId, groupCode: 'SALES_LEAD', note: note || undefined }),
    onSuccess: (m) => {
      toast.success(`${label(m.employeeId)} 영업팀장 그룹 등록`);
      setEmployeeId('');
      setNote('');
      qc.invalidateQueries({ queryKey: ['sal', 'sales-group'] });
    },
  });
  const toggle = useMutation({
    mutationFn: (m: SalesGroupMember) => salesGroupApi.update(m, { active: !m.active }),
    onSuccess: (m) => {
      toast.success(`${label(m.employeeId)} ${m.active ? '다시 활성화' : '비활성화'}`);
      qc.invalidateQueries({ queryKey: ['sal', 'sales-group'] });
    },
  });

  const cols = useMemo<ColDef<SalesGroupMember>[]>(
    () => [
      { headerName: '직원', flex: 1, minWidth: 140, valueGetter: (p) => label(p.data!.employeeId) },
      { headerName: '그룹', width: 190, valueGetter: (p) => SALES_GROUP_LABEL[p.data!.groupCode] ?? p.data!.groupCode },
      {
        headerName: '상태', width: 100,
        valueGetter: (p) => (p.data!.active ? '활성' : '비활성'),
        cellRenderer: (p: { data: SalesGroupMember }) => <Badge tone={p.data.active ? 'ok' : undefined}>{p.data.active ? '활성' : '비활성'}</Badge>,
      },
      { field: 'note', headerName: '메모', flex: 1, minWidth: 160 },
      { headerName: '변경', width: 170, valueGetter: (p) => fmtDateTime(p.data!.updatedAt ?? p.data!.createdAt) },
      {
        headerName: '', width: 190, sortable: false,
        cellRenderer: (p: { data: SalesGroupMember }) => (
          <div className="row" style={{ gap: 6 }}>
            <button onClick={() => setEditing(p.data)} aria-label={`수정 ${label(p.data.employeeId)}`}>
              수정
            </button>
            <button onClick={() => toggle.mutate(p.data)} aria-label={`${p.data.active ? '비활성화' : '활성화'} ${label(p.data.employeeId)}`}>
              {p.data.active ? '비활성화' : '활성화'}
            </button>
          </div>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [label],
  );

  return (
    <>
      <Card title="그룹 등록">
        <div className="form">
          <Field label="직원">
            <EmployeeSelect employees={employees} value={employeeId} onChange={setEmployeeId} exclude={members} testId="sg-employee" />
          </Field>
          <Field label="메모">
            <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} placeholder="(합성) 영업2팀장" />
          </Field>
          <button className="primary" disabled={!employeeId || add.isPending} onClick={() => add.mutate()} data-testid="sg-add">
            등록
          </button>
        </div>
        <p className="muted">
          영업팀장 그룹은 역할이 아니라 소속표입니다. 활성 소속만 견적 발행 할인 한도(SALES 5% → 10%)에 반영되고, 모든 변경은
          감사 로그에 남습니다. 비활성화는 행을 지우지 않습니다.
        </p>
      </Card>
      <Card title="소속 목록">
        {list.error && <ErrorBox error={list.error} />}
        <DataGrid<SalesGroupMember> testId="sg-grid" rowData={list.data} columnDefs={cols} height={320} getRowId={(p) => `${p.data.employeeId}/${p.data.groupCode}`} />
      </Card>
      {editing && <EditPanel key={editing.employeeId} m={editing} name={label(editing.employeeId)} onDone={() => setEditing(undefined)} />}
    </>
  );
}

export function SalesGroupPage() {
  return (
    <>
      <h1>영업팀장 그룹</h1>
      <RoleGate roles={['ADMIN']} what="영업팀장 그룹 관리">
        <SalesGroupInner />
      </RoleGate>
    </>
  );
}
