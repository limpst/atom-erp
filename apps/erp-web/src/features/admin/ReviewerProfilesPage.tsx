import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import { formatExpertise, parseExpertise, reviewerApi, type ReviewerProfile, type ReviewerProfileInput } from '@/api/admin';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field } from '@/components/ui';
import { toastError } from '@/app/errors';
import { EmployeeSelect, RoleGate, useEmployees } from './common';

/** 공통 › 관리 › QA 검토자 (QAR 자동 · 수동 배정 자격, ADMIN 전용 — 설계 07 은 관리 주체 미지정, QA 팀장 역할 코드 없음) */
interface FormState { maxOpen: string; expertise: string; qualifications: string; note: string }
const empty: FormState = { maxOpen: '5', expertise: '', qualifications: '', note: '' };

function toInput(f: FormState): ReviewerProfileInput {
  return {
    maxOpen: Number(f.maxOpen),
    expertise: parseExpertise(f.expertise),
    qualifications: f.qualifications.split(',').map((x) => x.trim()).filter(Boolean),
    note: f.note,
  };
}

function ProfileFields({ f, set }: { f: FormState; set: (f: FormState) => void }) {
  return (
    <>
      <Field label="동시 검토 상한(1~50)">
        <input type="number" min={1} max={50} value={f.maxOpen} onChange={(e) => set({ ...f, maxOpen: e.target.value })} aria-label="동시 검토 상한" />
      </Field>
      <Field label="전문성(보고서유형=건수)">
        <input value={f.expertise} onChange={(e) => set({ ...f, expertise: e.target.value })} placeholder="RT_PSD=3, RT_PRC=1" aria-label="전문성" />
      </Field>
      <Field label="자격(쉼표 구분)">
        <input value={f.qualifications} onChange={(e) => set({ ...f, qualifications: e.target.value })} placeholder="(합성) 건축구조 검토 자격" aria-label="자격" />
      </Field>
      <Field label="메모">
        <input value={f.note} onChange={(e) => set({ ...f, note: e.target.value })} maxLength={200} aria-label="메모" />
      </Field>
    </>
  );
}

function EditPanel({ p, name, onDone }: { p: ReviewerProfile; name: string; onDone: () => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState<FormState>({
    maxOpen: String(p.maxOpen), expertise: formatExpertise(p.expertise), qualifications: p.qualifications.join(', '), note: p.note ?? '',
  });
  const save = useMutation({
    mutationFn: () => reviewerApi.update(p, toInput(f)),
    onSuccess: () => {
      toast.success(`${name} 프로필 저장`);
      qc.invalidateQueries({ queryKey: ['qar', 'reviewers'] });
      onDone();
    },
  });
  return (
    <Card title={`수정 — ${name}`}>
      <div className="form">
        <ProfileFields f={f} set={setF} />
        <button className="primary" onClick={() => save.mutate()} disabled={save.isPending} data-testid="rv-save">
          저장
        </button>
        <button onClick={onDone}>닫기</button>
      </div>
    </Card>
  );
}

function ReviewersInner() {
  const qc = useQueryClient();
  const { employees, label } = useEmployees();
  const list = useQuery({ queryKey: ['qar', 'reviewers'], queryFn: reviewerApi.list, meta: { silent: true } });
  const [employeeId, setEmployeeId] = useState('');
  const [f, setF] = useState<FormState>(empty);
  const [editing, setEditing] = useState<ReviewerProfile>();
  const existing = useMemo(() => new Set((list.data ?? []).map((p) => p.employeeId)), [list.data]);

  const create = useMutation({
    mutationFn: (input: ReviewerProfileInput) => reviewerApi.create({ employeeId, ...input }),
    onSuccess: (p) => {
      toast.success(`${label(p.employeeId)} QA 검토자 등록`);
      setEmployeeId('');
      setF(empty);
      qc.invalidateQueries({ queryKey: ['qar', 'reviewers'] });
    },
  });
  const toggle = useMutation({
    mutationFn: (p: ReviewerProfile) => reviewerApi.update(p, { active: !p.active }),
    onSuccess: (p) => {
      toast.success(`${label(p.employeeId)} ${p.active ? '다시 활성화' : '비활성화 — 배정 후보에서 제외'}`);
      qc.invalidateQueries({ queryKey: ['qar', 'reviewers'] });
    },
  });
  const submit = () => {
    try {
      create.mutate(toInput(f));
    } catch (e) {
      toastError(e);
    }
  };

  const cols = useMemo<ColDef<ReviewerProfile>[]>(
    () => [
      { headerName: '검토자', flex: 1, minWidth: 140, valueGetter: (p) => label(p.data!.employeeId) },
      { field: 'stepCode', headerName: '단계', width: 110 },
      {
        headerName: '상태', width: 100, valueGetter: (p) => (p.data!.active ? '활성' : '비활성'),
        cellRenderer: (p: { data: ReviewerProfile }) => <Badge tone={p.data.active ? 'ok' : undefined}>{p.data.active ? '활성' : '비활성'}</Badge>,
      },
      { field: 'maxOpen', headerName: '상한', width: 80, type: 'rightAligned' },
      { field: 'openAssignments', headerName: '진행 중', width: 90, type: 'rightAligned' },
      { headerName: '전문성', width: 170, valueGetter: (p) => formatExpertise(p.data!.expertise) },
      { headerName: '자격', flex: 1, minWidth: 160, valueGetter: (p) => p.data!.qualifications.join(', ') },
      { field: 'note', headerName: '메모', width: 140 },
      {
        headerName: '', width: 190, sortable: false,
        cellRenderer: (p: { data: ReviewerProfile }) => (
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
      <Card title="검토자 등록">
        <div className="form">
          <Field label="직원">
            <EmployeeSelect employees={employees} value={employeeId} onChange={setEmployeeId} exclude={existing} testId="rv-employee" />
          </Field>
          <ProfileFields f={f} set={setF} />
          <button className="primary" disabled={!employeeId || create.isPending} onClick={submit} data-testid="rv-add">
            등록
          </button>
        </div>
        <p className="muted">
          활성 프로필만 QA 검토 자동 배정 후보 · 수동 배정 대상이 됩니다(직무 분리 검사는 별도). 전문성은 보고서 유형별 과거 검토
          건수로 배정 점수에 쓰이고, 자격은 기록용입니다. 모든 변경은 감사 로그에 남습니다.
        </p>
      </Card>
      <Card title="검토자 목록">
        {list.error && <ErrorBox error={list.error} />}
        <DataGrid<ReviewerProfile> testId="rv-grid" rowData={list.data} columnDefs={cols} height={320} getRowId={(p) => p.data.employeeId} />
      </Card>
      {editing && <EditPanel key={editing.employeeId} p={editing} name={label(editing.employeeId)} onDone={() => setEditing(undefined)} />}
    </>
  );
}

export function ReviewerProfilesPage() {
  return (
    <>
      <h1>QA 검토자</h1>
      <RoleGate roles={['ADMIN']} what="QA 검토자 관리">
        <ReviewersInner />
      </RoleGate>
    </>
  );
}
