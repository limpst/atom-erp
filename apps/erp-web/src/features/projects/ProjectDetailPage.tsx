import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  coreApi, cstApi, PROJECT_STATUS_LABEL, ROLE_IN_PROJECT_LABEL, type LedgerRow, type RoleInProject,
} from '@/api/core';
import { ProblemError } from '@/api/http';
import { DEV_USERS } from '@/auth/devUsers';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtMoney, fmtNum, Kpi, Loading } from '@/components/ui';

const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
const today = () => new Date().toISOString().slice(0, 10);

/** 403/404 는 화면 안에서 안내(토스트 없음) */
const silent = { meta: { silent: true }, retry: false } as const;

function Unavailable({ error }: { error: unknown }) {
  if (error instanceof ProblemError && error.status === 404) {
    return <div className="muted">{error.problem.detail ?? '데이터 없음'}</div>;
  }
  return <ErrorBox error={error} />;
}

export function ProjectDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const project = useQuery({ queryKey: ['project', id], queryFn: () => coreApi.project(id), ...silent });
  const members = useQuery({ queryKey: ['project', id, 'members'], queryFn: () => coreApi.members(id), ...silent, enabled: project.isSuccess });
  const budgets = useQuery({ queryKey: ['cst', id, 'budgets'], queryFn: () => cstApi.budgets(id), ...silent, enabled: project.isSuccess });
  const profit = useQuery({ queryKey: ['cst', id, 'profit'], queryFn: () => cstApi.profitability(id), ...silent, enabled: project.isSuccess });
  const evm = useQuery({ queryKey: ['cst', id, 'evm'], queryFn: () => cstApi.evm(id), ...silent, enabled: project.isSuccess });
  const ledger = useQuery({ queryKey: ['cst', id, 'ledger'], queryFn: () => cstApi.ledger(id), ...silent, enabled: project.isSuccess });

  const [m, setM] = useState<{ employeeId: string; roleInProject: RoleInProject; fromDate: string; toDate: string }>({
    employeeId: '', roleInProject: 'PART', fromDate: today(), toDate: `${new Date().getFullYear()}-12-31`,
  });
  const assign = useMutation({
    mutationFn: () => coreApi.assignMember(id, m),
    onSuccess: () => {
      toast.success('투입 지정 완료');
      qc.invalidateQueries({ queryKey: ['project', id] });
    },
  });
  const approve = useMutation({
    mutationFn: (rev: { id: string; version: number }) => cstApi.approveBudget(id, rev),
    onSuccess: (r) => {
      toast.success(`실행예산 Rev.${r.revNo} 승인`);
      qc.invalidateQueries({ queryKey: ['cst', id] });
    },
  });

  const ledgerCols = useMemo<ColDef<LedgerRow>[]>(
    () => [
      { field: 'postingDate', headerName: '전기일', width: 115 },
      { field: 'periodYm', headerName: '기간', width: 90 },
      { field: 'sourceType', headerName: 'Source', width: 110 },
      { field: 'costElement', headerName: '원가 요소', width: 110 },
      { headerName: '직원', width: 110, valueGetter: (p) => (p.data!.employeeId ? devName.get(p.data!.employeeId) ?? p.data!.employeeId.slice(0, 8) : '') },
      { field: 'gradeCode', headerName: '등급', width: 90 },
      { field: 'hours', headerName: '시간', width: 80, type: 'rightAligned' },
      { field: 'amountStdKrw', headerName: '표준원가', width: 130, type: 'rightAligned', valueFormatter: (p) => fmtMoney(p.value) },
      { headerName: '실원가', width: 130, type: 'rightAligned', valueGetter: (p) => (p.data!.amountKrwMasked ? '•••' : fmtMoney(p.data!.amountKrw)) },
      { field: 'flags', headerName: 'Flags', flex: 1, valueFormatter: (p) => (p.value ?? []).join(', ') },
    ],
    [],
  );

  if (project.isLoading) return <Loading />;
  if (project.error) {
    return (
      <>
        <Link to="/projects">← 목록</Link>
        <h1>프로젝트</h1>
        <ErrorBox error={project.error} />
      </>
    );
  }
  const p = project.data!;
  const e = evm.data;
  const level = e?.assessment.level;
  return (
    <>
      <Link to="/projects">← 목록</Link>
      <h1>
        {p.projectNo} {p.name}
      </h1>
      <Card>
        <div className="kpis">
          <Kpi label="상태" value={PROJECT_STATUS_LABEL[p.status]} />
          <Kpi label="계약금액" value={fmtMoney(p.contractAmount)} />
          <Kpi label="기간" value={`${p.startDate ?? '—'} ~ ${p.endDate ?? '—'}`} />
          <Kpi label="책임기술자(PM)" value={p.pmId ? devName.get(p.pmId) ?? p.pmId.slice(0, 8) : '미지정'} />
          <Kpi label="Service type" value={p.serviceType} />
          <Kpi label="ETag version" value={`v${p.version}`} />
        </div>
      </Card>

      <div className="grid2">
        <Card title="투입 인원">
          {members.error ? (
            <Unavailable error={members.error} />
          ) : (
            <table className="simple" data-testid="members-table">
              <thead>
                <tr>
                  <th>직원</th>
                  <th>사번</th>
                  <th>역할</th>
                  <th>기간</th>
                </tr>
              </thead>
              <tbody>
                {members.data?.map((x) => (
                  <tr key={x.id}>
                    <td>{devName.get(x.employeeId) ?? x.employeeId.slice(0, 8)}</td>
                    <td className="mono">{x.empNo}</td>
                    <td>{ROLE_IN_PROJECT_LABEL[x.roleInProject]}</td>
                    <td>
                      {x.fromDate} ~ {x.toDate}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {hasRole('EXEC', 'ADMIN', 'PM') && (
            <form
              className="form"
              style={{ marginTop: 12 }}
              onSubmit={(ev) => {
                ev.preventDefault();
                assign.mutate();
              }}
            >
              <Field label="직원">
                <select value={m.employeeId} onChange={(ev) => setM({ ...m, employeeId: ev.target.value })} required>
                  <option value="">선택</option>
                  {DEV_USERS.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name} ({u.roles.join(',')})
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="역할">
                <select value={m.roleInProject} onChange={(ev) => setM({ ...m, roleInProject: ev.target.value as RoleInProject })}>
                  {Object.entries(ROLE_IN_PROJECT_LABEL).map(([k, v]) => (
                    <option key={k} value={k}>
                      {k} {v}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="시작">
                <input type="date" value={m.fromDate} onChange={(ev) => setM({ ...m, fromDate: ev.target.value })} />
              </Field>
              <Field label="종료">
                <input type="date" value={m.toDate} onChange={(ev) => setM({ ...m, toDate: ev.target.value })} />
              </Field>
              <button className="primary" disabled={assign.isPending}>
                투입 지정
              </button>
            </form>
          )}
        </Card>

        <Card title="실행예산 (CST budget revisions)">
          {budgets.error ? (
            <Unavailable error={budgets.error} />
          ) : budgets.data?.length === 0 ? (
            <div className="muted">예산 리비전 없음 — 계약 서명(sal.contract.signed)으로 Rev.0 초안이 생깁니다.</div>
          ) : (
            budgets.data?.map((b) => (
              <div key={b.id} style={{ marginBottom: 12 }}>
                <div className="row">
                  <strong>Rev.{b.revNo}</strong>
                  <Badge tone={b.status === 'APPROVED' ? 'ok' : b.status === 'DRAFT' ? 'warn' : undefined}>{b.status}</Badge>
                  <span>BAC {fmtMoney(b.bacKrw)}</span>
                  <span className="muted">예상 이익률 {fmtNum(b.expectedMarginPct)}%</span>
                  {['DRAFT', 'SUBMITTED', 'REVIEWED'].includes(b.status) && hasRole('EXEC', 'ADMIN', 'FIN') && (
                    <button className="primary" onClick={() => approve.mutate(b)} disabled={approve.isPending}>
                      승인 (If-Match v{b.version})
                    </button>
                  )}
                </div>
                <table className="simple" style={{ marginTop: 6 }}>
                  <thead>
                    <tr>
                      <th>WBS</th>
                      <th>이름</th>
                      <th className="num">비중</th>
                      <th className="num">예산</th>
                    </tr>
                  </thead>
                  <tbody>
                    {b.wbs.map((w) => (
                      <tr key={w.id}>
                        <td className="mono">{w.code}</td>
                        <td>{w.name}</td>
                        <td className="num">{fmtNum(w.weightPct)}%</td>
                        <td className="num">{fmtMoney(w.budgetKrw)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))
          )}
        </Card>
      </div>

      <div className="grid2">
        <Card title={<>EVM {level && <Badge tone={level === 'GREEN' ? 'ok' : level === 'YELLOW' ? 'warn' : 'danger'}>{level}</Badge>}</>}>
          {evm.error ? (
            <Unavailable error={evm.error} />
          ) : e ? (
            <>
              <div className="kpis" data-testid="evm-kpis">
                <Kpi label="BAC" value={fmtMoney(e.result.bac)} />
                <Kpi label="PV" value={fmtMoney(e.result.pv)} />
                <Kpi label="EV" value={fmtMoney(e.result.ev)} />
                <Kpi label="AC" value={fmtMoney(e.result.ac)} />
                <Kpi label="CPI" value={fmtNum(e.result.cpi, 3)} tone={e.result.cpi < 0.9 ? 'danger' : undefined} />
                <Kpi label="SPI" value={fmtNum(e.result.spi, 3)} tone={e.result.spi < 0.8 ? 'danger' : undefined} />
                <Kpi label="EAC" value={fmtMoney(e.result.eac)} />
                <Kpi label="VAC" value={fmtMoney(e.result.vac)} />
              </div>
              <ul>
                {e.assessment.alerts.map((a, i) => (
                  <li key={i}>
                    <Badge tone={a.level === 'RED' ? 'danger' : 'warn'}>{a.code}</Badge> {a.message}
                  </li>
                ))}
              </ul>
              <div className="muted">
                기준일 {e.asOf} · AC basis {e.acBasis} · EAC method {e.result.eacMethod}
              </div>
            </>
          ) : (
            <Loading />
          )}
        </Card>
        <Card title="수익성 (profitability)">
          {profit.error ? (
            <Unavailable error={profit.error} />
          ) : profit.data ? (
            <>
              <div className="kpis" data-testid="profit-kpis">
                <Kpi label="계약금액" value={fmtMoney(profit.data.contractAmountKrw)} />
                <Kpi label="실적원가 AC" value={fmtMoney(profit.data.acKrw)} />
                <Kpi label="이익" value={fmtMoney(profit.data.marginKrw)} />
                <Kpi label="이익률" value={profit.data.marginPct == null ? '—' : `${fmtNum(profit.data.marginPct)}%`} />
                <Kpi label="투입 M/D" value={fmtNum(profit.data.mandays)} />
                <Kpi label="원가 기준" value={profit.data.basis === 'REAL' ? '실원가' : '표준원가'} />
              </div>
              <table className="simple" style={{ marginTop: 8 }}>
                <tbody>
                  {Object.entries(profit.data.byElement).map(([k, v]) => (
                    <tr key={k}>
                      <td>{k}</td>
                      <td className="num">{fmtMoney(v)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : (
            <Loading />
          )}
        </Card>
      </div>

      <Card title="원가 원장 (append-only ledger)">
        {ledger.error ? <Unavailable error={ledger.error} /> : <DataGrid<LedgerRow> rowData={ledger.data} columnDefs={ledgerCols} height={260} />}
      </Card>
    </>
  );
}
