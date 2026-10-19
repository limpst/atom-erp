import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  GRADE_LABEL, LABOR_GRADES, RATE_STATUS_LABEL, rateApi, rateInEffect, type Rate, type RateKind,
} from '@/api/admin';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtMoney, fmtNum } from '@/components/ui';
import { RoleGate, todayIso, useEmployees } from './common';

/**
 * 공통 › 관리 › 표준 노임 단가 (CST 기준 요율, 설계 08 A2: ADMIN 관리 · EXEC 승인 → V0038 4-eyes).
 * 유효기간 [시작, 종료) · 승인된 기간끼리 겹침 금지 · 소급 종료 금지. 값은 ADMIN 이 확인한 것만(⚠️ 법령 · 고시 최신본 확인).
 */
const KIND_LABEL: Record<RateKind, string> = {
  STD_LABOR: '표준 노임 일 단가', OVERHEAD_PCT: '제경비율', TECHFEE_PCT: '기술료율', EQUIP_DAILY: '장비 손료(일)',
};
const isPct = (k: RateKind) => k === 'OVERHEAD_PCT' || k === 'TECHFEE_PCT';
const fmtValue = (r: Pick<Rate, 'rateKind' | 'value'>) => (isPct(r.rateKind) ? `${fmtNum(r.value, 4)}%` : fmtMoney(r.value));
const keyLabel = (r: Pick<Rate, 'rateKind' | 'keyCode'>) =>
  r.rateKind === 'STD_LABOR' && GRADE_LABEL[r.keyCode] ? `${r.keyCode} (${GRADE_LABEL[r.keyCode]})` : r.keyCode;

type Action = { kind: 'edit' | 'reject' | 'retire'; rate: Rate };

function ActionPanel({ a, onDone }: { a: Action; onDone: () => void }) {
  const qc = useQueryClient();
  const r = a.rate;
  const [value, setValue] = useState(String(r.value));
  const [validFrom, setValidFrom] = useState(r.validFrom);
  const [validTo, setValidTo] = useState(r.validTo ?? '');
  const [sourceRef, setSourceRef] = useState(r.sourceRef);
  const [reason, setReason] = useState('');
  const [retireOn, setRetireOn] = useState(todayIso());
  const run = useMutation({
    mutationFn: () =>
      a.kind === 'edit'
        ? rateApi.edit(r, { value: Number(value), validFrom, validTo: validTo || null, sourceRef })
        : a.kind === 'reject'
          ? rateApi.reject(r, reason)
          : rateApi.retire(r, retireOn),
    onSuccess: () => {
      toast.success(a.kind === 'edit' ? '초안 수정' : a.kind === 'reject' ? '반려' : `적용 종료 — ${retireOn} 부터 미적용`);
      qc.invalidateQueries({ queryKey: ['cst', 'rates'] });
      onDone();
    },
  });
  const title = { edit: '초안 수정', reject: '반려', retire: '적용 종료' }[a.kind];
  return (
    <Card title={`${title} — ${keyLabel(r)} ${r.validFrom}`}>
      <div className="form">
        {a.kind === 'edit' && (
          <>
            <Field label="값">
              <input type="number" min={0} value={value} onChange={(e) => setValue(e.target.value)} aria-label="수정 값" />
            </Field>
            <Field label="적용 시작">
              <input type="date" value={validFrom} onChange={(e) => setValidFrom(e.target.value)} aria-label="수정 적용 시작" />
            </Field>
            <Field label="적용 종료(선택)">
              <input type="date" value={validTo} onChange={(e) => setValidTo(e.target.value)} aria-label="수정 적용 종료" />
            </Field>
            <Field label="근거">
              <input value={sourceRef} onChange={(e) => setSourceRef(e.target.value)} aria-label="수정 근거" />
            </Field>
          </>
        )}
        {a.kind === 'reject' && (
          <Field label="반려 사유 (필수)">
            <input value={reason} onChange={(e) => setReason(e.target.value)} aria-label="반려 사유" />
          </Field>
        )}
        {a.kind === 'retire' && (
          <Field label="종료일(이 날부터 미적용, 오늘 이후)">
            <input type="date" min={todayIso()} value={retireOn} onChange={(e) => setRetireOn(e.target.value)} aria-label="종료일" />
          </Field>
        )}
        <button className="primary" onClick={() => run.mutate()} disabled={run.isPending || (a.kind === 'reject' && !reason)} data-testid="rate-action-save">
          {title}
        </button>
        <button onClick={onDone}>닫기</button>
      </div>
    </Card>
  );
}

function RatesInner() {
  const { hasRole, session } = useAuth();
  const qc = useQueryClient();
  const { label } = useEmployees();
  const [kind, setKind] = useState<RateKind>('STD_LABOR');
  const rates = useQuery({ queryKey: ['cst', 'rates', kind], queryFn: () => rateApi.list(kind), meta: { silent: true } });
  const [form, setForm] = useState({ keyCode: 'INTER', value: '', validFrom: todayIso(), validTo: '', sourceRef: '⚠️ 예시 — ' });
  const [action, setAction] = useState<Action>();
  const admin = hasRole('ADMIN');
  const exec = hasRole('EXEC');
  const today = todayIso();

  const register = useMutation({
    mutationFn: () =>
      rateApi.register({
        rateKind: kind, keyCode: form.keyCode, value: Number(form.value), validFrom: form.validFrom,
        validTo: form.validTo || null, sourceRef: form.sourceRef,
      }),
    onSuccess: (r) => {
      toast.success(`초안 등록 — ${keyLabel(r)} ${fmtValue(r)} (${r.validFrom}~), EXEC 승인 대기`);
      setForm({ ...form, value: '' });
      qc.invalidateQueries({ queryKey: ['cst', 'rates'] });
    },
  });
  const approve = useMutation({
    mutationFn: (r: Rate) => rateApi.approve(r),
    onSuccess: (r) => {
      toast.success(`승인 — ${keyLabel(r)} ${fmtValue(r)} ${r.validFrom}부터 적용`);
      qc.invalidateQueries({ queryKey: ['cst', 'rates'] });
    },
  });

  const cols = useMemo<ColDef<Rate>[]>(
    () => [
      { headerName: '키', width: 150, valueGetter: (p) => keyLabel(p.data!) },
      { headerName: '값', width: 130, type: 'rightAligned', valueGetter: (p) => fmtValue(p.data!) },
      { field: 'validFrom', headerName: '적용 시작', width: 115 },
      { headerName: '적용 종료', width: 115, valueGetter: (p) => p.data!.validTo ?? '무기한' },
      {
        headerName: '상태', width: 160,
        valueGetter: (p) => `${RATE_STATUS_LABEL[p.data!.status]}${rateInEffect(p.data!, today) ? ' · 적용 중' : ''}`,
        cellRenderer: (p: { data: Rate }) => (
          <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center', whiteSpace: 'nowrap', lineHeight: 1.5 }}>
            <Badge tone={p.data.status === 'APPROVED' ? 'ok' : p.data.status === 'DRAFT' ? 'warn' : 'danger'}>{RATE_STATUS_LABEL[p.data.status]}</Badge>
            {rateInEffect(p.data, today) && <Badge tone="info">적용 중</Badge>}
          </span>
        ),
      },
      { field: 'sourceRef', headerName: '근거', flex: 1, minWidth: 160 },
      { headerName: '등록', width: 120, valueGetter: (p) => label(p.data!.createdBy) },
      {
        headerName: '승인 · 반려', width: 140,
        valueGetter: (p) => (p.data!.approvedBy ? label(p.data!.approvedBy) : p.data!.rejectReason ?? ''),
      },
      {
        headerName: '', width: 200, sortable: false,
        cellRenderer: (p: { data: Rate }) => {
          const r = p.data;
          const k = `${keyLabel(r)} ${r.validFrom}`;
          const mine = r.createdBy === session?.actorId;
          return (
            <div className="row" style={{ gap: 6 }}>
              {r.status === 'DRAFT' && admin && (
                <button onClick={() => setAction({ kind: 'edit', rate: r })} aria-label={`수정 ${k}`}>
                  수정
                </button>
              )}
              {r.status === 'DRAFT' && exec && (
                <>
                  <button className="primary" onClick={() => approve.mutate(r)} disabled={mine} title={mine ? '등록자는 승인할 수 없습니다(4-eyes)' : undefined} aria-label={`승인 ${k}`}>
                    승인
                  </button>
                  <button onClick={() => setAction({ kind: 'reject', rate: r })} aria-label={`반려 ${k}`}>
                    반려
                  </button>
                </>
              )}
              {r.status === 'APPROVED' && admin && (r.validTo === null || r.validTo > today) && (
                <button onClick={() => setAction({ kind: 'retire', rate: r })} aria-label={`적용 종료 ${k}`}>
                  적용 종료
                </button>
              )}
            </div>
          );
        },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [label, admin, exec, session?.actorId, today],
  );
  const drafts = (rates.data ?? []).filter((r) => r.status === 'DRAFT');

  return (
    <>
      <Card>
        <div className="row">
          <Field label="요율 종류">
            <select value={kind} onChange={(e) => setKind(e.target.value as RateKind)} aria-label="요율 종류">
              {(Object.keys(KIND_LABEL) as RateKind[]).map((k) => (
                <option key={k} value={k}>
                  {KIND_LABEL[k]}
                </option>
              ))}
            </select>
          </Field>
          <span className="muted">
            ADMIN 초안 등록 · 수정 → EXEC 승인(등록 · 수정자 ≠ 승인자)해야 원장 · 예산에 쓰입니다. 승인된 기간은 겹칠 수 없고, 새 무기한 단가를
            승인하면 직전 무기한 단가가 그 시작일에 자동 마감됩니다. 종료는 오늘 이후로만(소급 금지).
          </span>
        </div>
        {drafts.length > 0 && (
          <p data-testid="rate-drafts">
            <Badge tone="warn">승인 대기 {drafts.length}건</Badge> {drafts.map((d) => `${keyLabel(d)} ${fmtValue(d)}`).join(' · ')}
          </p>
        )}
      </Card>
      {admin && (
        <Card title="초안 등록 (ADMIN)">
          <div className="form">
            <Field label="키">
              {kind === 'STD_LABOR' ? (
                <select value={form.keyCode} onChange={(e) => setForm({ ...form, keyCode: e.target.value })} aria-label="등급">
                  {LABOR_GRADES.map((g) => (
                    <option key={g} value={g}>
                      {g} ({GRADE_LABEL[g]})
                    </option>
                  ))}
                </select>
              ) : (
                <input value={form.keyCode} onChange={(e) => setForm({ ...form, keyCode: e.target.value })} aria-label="키 코드" />
              )}
            </Field>
            <Field label={isPct(kind) ? '값(%)' : '값(원)'}>
              <input type="number" min={0} value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} aria-label="값" />
            </Field>
            <Field label="적용 시작">
              <input type="date" value={form.validFrom} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} aria-label="적용 시작" />
            </Field>
            <Field label="적용 종료(선택)">
              <input type="date" value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} aria-label="적용 종료" />
            </Field>
            <Field label="근거(고시명 · 공표일)">
              <input value={form.sourceRef} onChange={(e) => setForm({ ...form, sourceRef: e.target.value })} aria-label="근거" />
            </Field>
            <button className="primary" onClick={() => register.mutate()} disabled={!form.value || !form.sourceRef.trim() || register.isPending} data-testid="rate-register">
              초안 등록
            </button>
          </div>
        </Card>
      )}
      <Card title={`${KIND_LABEL[kind]} — 유효기간별`}>
        {rates.error && <ErrorBox error={rates.error} />}
        <DataGrid<Rate> testId="rate-grid" rowData={rates.data} columnDefs={cols} height={380} getRowId={(p) => p.data.id} />
      </Card>
      {action && <ActionPanel key={`${action.kind}-${action.rate.id}`} a={action} onDone={() => setAction(undefined)} />}
    </>
  );
}

export function LaborRatesPage() {
  return (
    <>
      <h1>표준 노임 단가</h1>
      <RoleGate roles={['ADMIN', 'EXEC', 'FIN', 'SYSADMIN']} what="기준 요율">
        <RatesInner />
      </RoleGate>
    </>
  );
}
