import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  CLOSE_BLOCKER_LABEL, cstApi, PERIOD_STATUS_LABEL, type CloseResult, type PeriodRow, type ReconciliationRow,
} from '@/api/core';
import { useAuth } from '@/auth/auth';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, fmtNum } from '@/components/ui';
import { prevMonth, ReopenDialog, ymKey, ymLabel } from '@/features/hrq/HrqMonthClosePage';
import { useEmployees } from '@/features/admin/common';

const STATUS_TONE: Record<string, 'ok' | 'warn' | 'danger' | 'info' | undefined> = {
  OPEN: 'info', CLOSING: 'warn', CLOSED: 'ok', REOPENED: 'warn',
};

interface DiffRow { projectId: string; employeeId: string; grade: string; workType: string; hrqMandays: number; cstMandays: number; diff: number }
function parseDetail(r: ReconciliationRow): { groups?: number; diffs: DiffRow[] } {
  try {
    const d = JSON.parse(r.detail) as { groups?: number; diffs?: DiffRow[] };
    return { groups: d.groups, diffs: d.diffs ?? [] };
  } catch {
    return { diffs: [] };
  }
}

function ReconPanel({ ym }: { ym: string }) {
  const { label } = useEmployees();
  const recon = useQuery({ queryKey: ['cst', 'recon', ym], queryFn: () => cstApi.reconciliations(ym) });
  return (
    <Card title={`HRQ↔CST 인·일 대사 — ${ymLabel(ym)}`}>
      {recon.error && <ErrorBox error={recon.error} />}
      {recon.data?.length === 0 && <p className="muted">대사 기록이 없습니다 — HRQ 월 마감(hrq.manday.closed)을 받으면 원장 기록 없이 비교만 합니다.</p>}
      {recon.data?.map((r) => {
        const d = parseDetail(r);
        return (
          <div key={r.id} style={{ marginBottom: 12 }} data-testid="recon-run">
            <div className="row">
              <Badge tone={r.status === 'MATCHED' ? 'ok' : 'danger'}>{r.status === 'MATCHED' ? '일치(MATCHED)' : '불일치(MISMATCH)'}</Badge>
              <span>HRQ {fmtNum(r.hrq_total_mandays)} 인·일 · CST {fmtNum(r.cst_total_mandays)} 인·일 · 차이 {fmtNum(r.diff_mandays)}</span>
              <span className="muted">{fmtDateTime(r.created_at)} · 그룹 {d.groups ?? '—'} · 이벤트 {r.source_event_id.slice(0, 8)}</span>
            </div>
            {d.diffs.length > 0 && (
              <table className="simple" style={{ marginTop: 6 }}>
                <thead><tr><th>기술자</th><th>등급</th><th>업무</th><th className="num">HRQ</th><th className="num">CST</th><th className="num">차이</th></tr></thead>
                <tbody>
                  {d.diffs.map((x, i) => (
                    <tr key={i}>
                      <td>{label(x.employeeId)}</td><td>{x.grade}</td><td>{x.workType}</td>
                      <td className="num">{fmtNum(x.hrqMandays)}</td><td className="num">{fmtNum(x.cstMandays)}</td><td className="num">{fmtNum(x.diff)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        );
      })}
    </Card>
  );
}

/**
 * G3 › 원가 회계기간 — 마감(ADMIN, 체크리스트) · 재오픈(SYSADMIN, 목록 행에서 사유와 함께) · HRQ↔CST 인·일 대사 결과.
 * SYSADMIN 도 cst:period:read(V0040)로 목록을 본다.
 */
export function CstPeriodsPage() {
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const canRead = hasRole('EXEC', 'ADMIN', 'FIN', 'AUDITOR', 'SYSADMIN');
  const canReopen = hasRole('SYSADMIN');
  const [ym, setYm] = useState(prevMonth());
  const [reopenYm, setReopenYm] = useState<string>();
  const [selected, setSelected] = useState<string>();
  const [result, setResult] = useState<CloseResult>();
  const periods = useQuery({ queryKey: ['cst', 'periods'], queryFn: cstApi.periods, enabled: canRead });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['cst', 'periods'] });
    qc.invalidateQueries({ queryKey: ['cst', 'recon'] });
  };
  const close = useMutation({
    mutationFn: () => cstApi.closePeriod(ymKey(ym)),
    onSuccess: (r) => {
      setResult(r);
      if (r.closed) toast.success(`CST ${ymLabel(r.periodYm)} 회계 마감`);
      else toast.warning(`CST ${ymLabel(r.periodYm)} 마감 불가 — 체크리스트 ${r.blockers.length}건`);
      refresh();
    },
  });
  const reopen = useMutation({
    mutationFn: ({ k, reason }: { k: string; reason: string }) => cstApi.reopenPeriod(k, reason),
    onSuccess: (r) => {
      toast.success(`CST ${ymLabel(r.periodYm)} 재오픈`);
      setReopenYm(undefined);
      refresh();
    },
  });
  const rows = useMemo(() => periods.data ?? [], [periods.data]);

  return (
    <>
      <h1>원가 회계기간 (CST)</h1>
      <Banner tone="info">
        마감 월에는 원가 원장 행을 넣을 수 없습니다(DB 트리거 CST-409-002). 마감 뒤 들어오는 정정 · 경비는 다음 열린 월로 귀속됩니다.
        마감 체크리스트: ① HRQ 월 마감 수신 ② HRQ↔CST 인·일 대사 MATCHED.
      </Banner>
      <div className="grid2">
        {hasRole('ADMIN') && (
          <Card title="회계 마감 (ADMIN)">
            <div className="row">
              <Field label="마감 월">
                <input type="month" value={ym} onChange={(e) => setYm(e.target.value)} aria-label="CST 마감 월" />
              </Field>
              <button className="primary" onClick={() => close.mutate()} disabled={!ym || close.isPending} data-testid="cst-close">
                {ym} 마감
              </button>
            </div>
            {result && (
              <div data-testid="cst-close-result" style={{ marginTop: 8 }}>
                {result.closed ? (
                  <Banner tone="ok">{ymLabel(result.periodYm)} 마감 완료 — 이 달 원장 전기는 이제 차단됩니다.</Banner>
                ) : (
                  <Banner tone="danger">
                    {ymLabel(result.periodYm)} 마감 불가:
                    <ul style={{ margin: '4px 0 0 18px' }}>
                      {result.blockers.map((b) => <li key={b}>{CLOSE_BLOCKER_LABEL[b] ?? b} <span className="mono">({b})</span></li>)}
                    </ul>
                  </Banner>
                )}
              </div>
            )}
          </Card>
        )}
        {canReopen && (
          <Card title="회계 재오픈 (SYSADMIN · 사유 필수)">
            <p className="muted" style={{ fontSize: 12 }}>아래 목록에서 마감된 월의 [재오픈] 버튼을 누르고 사유(5자 이상)를 적습니다. 감사 로그에 남습니다.</p>
          </Card>
        )}
      </div>
      {canRead ? (
        <Card title="회계기간 · 대사 결과">
          {periods.error && <ErrorBox error={periods.error} />}
          <table className="simple" data-testid="cst-periods">
            <thead>
              <tr><th>월</th><th>상태</th><th>HRQ 마감 수신</th><th>대사</th><th className="num">HRQ 인·일</th><th className="num">CST 인·일</th><th className="num">차이</th><th>마감 / 재오픈</th>{canReopen && <th />}</tr>
            </thead>
            <tbody>
              {rows.map((p: PeriodRow) => (
                <tr
                  key={p.period_ym}
                  data-testid={`cst-period-${p.period_ym}`}
                  onClick={() => setSelected(p.period_ym)}
                  style={{ cursor: 'pointer', background: selected === p.period_ym ? 'var(--surface-2)' : undefined }}
                >
                  <td className="mono">{ymLabel(p.period_ym)}</td>
                  <td><Badge tone={STATUS_TONE[p.status]}>{PERIOD_STATUS_LABEL[p.status]}</Badge></td>
                  <td>{fmtDateTime(p.hrq_closed_at)}</td>
                  <td>{p.recon_status ? <Badge tone={p.recon_status === 'MATCHED' ? 'ok' : 'danger'}>{p.recon_status}</Badge> : '—'}</td>
                  <td className="num">{fmtNum(p.hrq_total_mandays)}</td>
                  <td className="num">{fmtNum(p.cst_total_mandays)}</td>
                  <td className="num">{fmtNum(p.diff_mandays)}</td>
                  <td>
                    {p.closed_at && <>마감 {fmtDateTime(p.closed_at)}</>}
                    {p.reopened_at && <div className="muted">재오픈 {fmtDateTime(p.reopened_at)} — {p.reopen_reason}</div>}
                  </td>
                  {canReopen && (
                    <td>
                      {p.status === 'CLOSED' && (
                        <button
                          className="danger"
                          onClick={(e) => { e.stopPropagation(); setReopenYm(p.period_ym); }}
                          aria-label={`CST 재오픈 ${ymLabel(p.period_ym)}`}
                        >
                          재오픈
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted" style={{ fontSize: 12 }}>행을 누르면 그 달의 대사 실행 이력(불일치 상세)을 봅니다.</p>
        </Card>
      ) : (
        <div className="empty">회계기간은 EXEC · ADMIN · FIN · AUDITOR · SYSADMIN 역할만 조회할 수 있습니다.</div>
      )}
      {canRead && selected && <ReconPanel ym={selected} />}
      {reopenYm && (
        <ReopenDialog
          title={`CST ${ymLabel(reopenYm)} 재오픈`}
          onClose={() => setReopenYm(undefined)}
          pending={reopen.isPending}
          onConfirm={(reason) => reopen.mutate({ k: reopenYm, reason })}
        />
      )}
    </>
  );
}
