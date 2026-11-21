import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { cstBillingApi, DUNNING_LEVEL_LABEL, lastDayOfPrevMonth, type DunningNotice, type DunningStatus } from '@/api/core';
import { useAuth } from '@/auth/auth';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, fmtMoney, Kpi, Modal } from '@/components/ui';

const STATUS_LABEL: Record<DunningStatus, string> = { DRAFT: '초안', APPROVED: '결재됨', SENT: '보냄', CANCELLED: '취소(입금 완료)' };
const STATUS_TONE: Record<DunningStatus, 'warn' | 'info' | 'ok' | 'danger'> = { DRAFT: 'warn', APPROVED: 'info', SENT: 'ok', CANCELLED: 'danger' };

/** 상태별 건수 · 미수 합계(원) — 서버 숫자를 세기만 한다 */
export function summarize(rows: DunningNotice[]) {
  const by: Record<DunningStatus, { count: number; openKrw: number }> = {
    DRAFT: { count: 0, openKrw: 0 }, APPROVED: { count: 0, openKrw: 0 }, SENT: { count: 0, openKrw: 0 }, CANCELLED: { count: 0, openKrw: 0 },
  };
  for (const r of rows) { by[r.status].count += 1; by[r.status].openKrw += r.openKrw; }
  return by;
}

export function CstDunningPage() {
  const { hasRole, session } = useAuth();
  const canRead = hasRole('EXEC', 'ADMIN', 'FIN', 'AUDITOR');
  const canWrite = hasRole('FIN');
  const canApprove = hasRole('EXEC', 'ADMIN');
  const qc = useQueryClient();
  const [status, setStatus] = useState<DunningStatus | ''>('');
  const [asOf, setAsOf] = useState(lastDayOfPrevMonth());
  const [view, setView] = useState<DunningNotice | null>(null);
  const [holdFor, setHoldFor] = useState<DunningNotice | null>(null);
  const [reason, setReason] = useState('');
  const notices = useQuery({ queryKey: ['cst', 'dunning', status], queryFn: () => cstBillingApi.dunningNotices(status || undefined), enabled: canRead });
  const refresh = () => qc.invalidateQueries({ queryKey: ['cst', 'dunning'] });
  const run = useMutation({
    mutationFn: () => cstBillingApi.dunningRun(asOf),
    onSuccess: (r) => { toast.success(`기준일 ${r.asOf}: 초안 ${r.created}건 생성 · 취소 ${r.cancelled}건 · 보류로 건너뜀 ${r.skippedOnHold}건`); refresh(); },
  });
  const approve = useMutation({ mutationFn: (id: string) => cstBillingApi.dunningApprove(id), onSuccess: () => { toast.success('결재 완료 — 보낼 수 있습니다'); refresh(); } });
  const sent = useMutation({ mutationFn: (id: string) => cstBillingApi.dunningSent(id, 'EMAIL'), onSuccess: () => { toast.success('보냄으로 기록'); refresh(); } });
  const hold = useMutation({
    mutationFn: ({ invoiceId, r }: { invoiceId: string; r: string }) => cstBillingApi.dunningHold(invoiceId, r),
    onSuccess: () => { toast.success('독촉 보류 — 새 초안을 만들지 않습니다'); setHoldFor(null); setReason(''); refresh(); },
  });
  if (!canRead) return <Banner tone="warn">독촉 통지는 재무 · 경영지원 · 임원 · 감사만 봅니다.</Banner>;
  const rows = notices.data ?? [];
  const sum = summarize(rows);
  return (
    <>
      <h1>독촉 통지 (CST · 4-eyes)</h1>
      <Banner tone="info">
        단계: L0 31일 이상 알림 · L1 61일 이상 담당자 안내 · L2 91일 이상 공문(임원 결재). 초안은 재무가 만들고, 결재는 초안을 만든 사람과 다른 임원 · 경영지원이 합니다. 자동 발송은 없습니다. 보낸 뒤 "보냄" 으로 기록합니다.
      </Banner>
      <div className="kpis" data-testid="dunning-kpis">
        <Kpi label="초안(결재 대기)" value={`${sum.DRAFT.count}건 · ${fmtMoney(sum.DRAFT.openKrw)}원`} tone={sum.DRAFT.count > 0 ? 'warn' : 'ok'} />
        <Kpi label="결재됨(보낼 것)" value={`${sum.APPROVED.count}건 · ${fmtMoney(sum.APPROVED.openKrw)}원`} />
        <Kpi label="보냄" value={`${sum.SENT.count}건 · ${fmtMoney(sum.SENT.openKrw)}원`} tone="ok" />
      </div>
      <div className="row">
        <Field label="상태">
          <select value={status} onChange={(e) => setStatus(e.target.value as DunningStatus | '')} aria-label="상태">
            <option value="">전체</option>
            {(Object.keys(STATUS_LABEL) as DunningStatus[]).map((k) => <option key={k} value={k}>{STATUS_LABEL[k]}</option>)}
          </select>
        </Field>
        {canWrite && (
          <>
            <Field label="기준일"><input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} aria-label="기준일" /></Field>
            <button disabled={run.isPending} onClick={() => run.mutate()}>초안 만들기(기준일 기준)</button>
          </>
        )}
      </div>
      {run.error && <ErrorBox error={run.error} />}
      {notices.error && <ErrorBox error={notices.error} />}
      <Card title="통지 목록">
        {rows.length === 0 && <p className="muted">통지가 없습니다. 재무가 "초안 만들기" 를 누르면 기준일에 31일 넘은 미수마다 초안이 생깁니다.</p>}
        <table className="simple" data-testid="dunning-table">
          <thead><tr><th>기준일</th><th>단계</th><th>발주처</th><th>청구번호</th><th className="num">경과(일)</th><th className="num">미수(원)</th><th>상태</th><th>동작</th></tr></thead>
          <tbody>
            {rows.map((n) => (
              <tr key={n.id}>
                <td>{n.asOf}</td><td>{DUNNING_LEVEL_LABEL[n.level]}</td><td>{n.clientName}</td><td>{n.invoiceNo}</td>
                <td className="num">{n.ageDays}</td><td className="num">{fmtMoney(n.openKrw)}</td>
                <td><Badge tone={STATUS_TONE[n.status]}>{STATUS_LABEL[n.status]}</Badge></td>
                <td>
                  <button onClick={() => setView(n)}>본문</button>{' '}
                  {canApprove && n.status === 'DRAFT' && n.createdBy !== session?.actorId && (
                    <button disabled={approve.isPending} onClick={() => approve.mutate(n.id)}>결재</button>
                  )}
                  {canApprove && n.status === 'DRAFT' && n.createdBy === session?.actorId && <span className="muted">본인 초안</span>}
                  {canWrite && n.status === 'APPROVED' && <button disabled={sent.isPending} onClick={() => sent.mutate(n.id)}>보냄 기록</button>}
                  {canWrite && (n.status === 'DRAFT' || n.status === 'APPROVED') && <>{' '}<button onClick={() => setHoldFor(n)}>보류</button></>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {view && (
        <Modal title={`${view.clientName} · ${view.invoiceNo} · ${DUNNING_LEVEL_LABEL[view.level]}`} onClose={() => setView(null)}>
          <p className="muted">만든 때 {fmtDateTime(view.createdAt)} · 결재 {view.approvedBy ? '있음' : '없음'} · 보낸 때 {view.sentAt ? fmtDateTime(view.sentAt) : '—'}</p>
          <pre data-testid="dunning-body" style={{ whiteSpace: 'pre-wrap' }}>{view.bodyText}</pre>
        </Modal>
      )}
      {holdFor && (
        <Modal
          title={`독촉 보류 — ${holdFor.clientName} · ${holdFor.invoiceNo}`} onClose={() => setHoldFor(null)}
          footer={<button disabled={hold.isPending || reason.trim().length < 2} onClick={() => hold.mutate({ invoiceId: holdFor.invoiceId, r: reason.trim() })}>보류 확정</button>}
        >
          <p>분쟁 · 하자 협의 등 사유를 적습니다. 보류 중에는 이 청구에 새 초안을 만들지 않습니다. 해제도 기록됩니다.</p>
          <Field label="사유"><input value={reason} onChange={(e) => setReason(e.target.value)} aria-label="보류 사유" maxLength={300} /></Field>
        </Modal>
      )}
    </>
  );
}
