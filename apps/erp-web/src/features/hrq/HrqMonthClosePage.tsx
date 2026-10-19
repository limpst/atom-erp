import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { hrqApi, type MonthCloseRow } from '@/api/hrq';
import { useAuth } from '@/auth/auth';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, fmtNum, Modal } from '@/components/ui';
import { useEmployees } from '@/features/admin/common';

const MC_STATUS: Record<MonthCloseRow['status'], { label: string; tone?: 'ok' | 'warn' | 'danger' | 'info' }> = {
  OPEN: { label: '열림', tone: 'info' }, CLOSED: { label: '마감', tone: 'ok' }, REOPENED: { label: '재오픈', tone: 'warn' },
};

/** 'YYYY-MM' ↔ 'YYYYMM' */
export const ymKey = (ym: string) => ym.replace('-', '');
export const ymLabel = (k: string) => `${k.slice(0, 4)}-${k.slice(4, 6)}`;
export function prevMonth(d = new Date()): string {
  const x = new Date(d.getFullYear(), d.getMonth() - 1, 1);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}`;
}

export function ReopenDialog({ title, onClose, onConfirm, pending }: {
  title: string; onClose: () => void; onConfirm: (reason: string) => void; pending: boolean;
}) {
  const [reason, setReason] = useState('');
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>취소</button>
          <button className="danger" disabled={reason.trim().length < 5 || pending} onClick={() => onConfirm(reason.trim())} data-testid="reopen-confirm">
            재오픈
          </button>
        </>
      }
    >
      <p className="muted">재오픈은 감사 로그에 사유와 함께 남습니다. 마감 후 정정은 가능하면 조정 전표(다음 열린 월)로 처리하세요.</p>
      <Field label="재오픈 사유(5자 이상)">
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} aria-label="재오픈 사유" />
      </Field>
    </Modal>
  );
}

/** G5 › HRQ 월 마감 — 미승인 0건이면 인·일 집계 → 타임시트 LOCKED → hrq.manday.closed(→ CST 대사). 재오픈은 사유 필수 */
export function HrqMonthClosePage() {
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const { label } = useEmployees();
  const isAdmin = hasRole('ADMIN');
  const [ym, setYm] = useState(prevMonth());
  const [reopenFor, setReopenFor] = useState<string>();
  const list = useQuery({ queryKey: ['hrq', 'month-close'], queryFn: hrqApi.monthCloses });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['hrq', 'month-close'] });
    qc.invalidateQueries({ queryKey: ['cst', 'periods'] });
  };
  const close = useMutation({
    mutationFn: () => hrqApi.closeMonth(ymKey(ym)),
    onSuccess: (r) => {
      toast.success(`HRQ ${ymLabel(r.yearMonth)} 마감 — 인·일 ${fmtNum(r.totalMandays)} · 잠금 ${r.lockedEntries}건`);
      refresh();
    },
  });
  const reopen = useMutation({
    mutationFn: ({ k, reason }: { k: string; reason: string }) => hrqApi.reopenMonth(k, reason),
    onSuccess: (_r, v) => {
      toast.success(`HRQ ${ymLabel(v.k)} 재오픈`);
      setReopenFor(undefined);
      refresh();
    },
  });

  return (
    <>
      <h1>HRQ 월 마감 (타임시트 인·일)</h1>
      <Banner tone="info">
        마감하면 그 달 타임시트는 수정할 수 없고(HRQ-409-001), 인·일 집계가 원가(CST)로 넘어가 HRQ↔CST 대사가 실행됩니다. CST 회계 마감은 이
        대사 결과가 MATCHED 여야 합니다.
      </Banner>
      {isAdmin && (
        <Card title="월 마감 실행 (ADMIN)">
          <div className="row">
            <Field label="마감 월">
              <input type="month" value={ym} onChange={(e) => setYm(e.target.value)} aria-label="HRQ 마감 월" />
            </Field>
            <button className="primary" onClick={() => close.mutate()} disabled={!ym || close.isPending} data-testid="hrq-close">
              {ym} 마감
            </button>
          </div>
          {close.data && (
            <p className="muted" data-testid="hrq-close-result">
              {ymLabel(close.data.yearMonth)}: 집계 {close.data.rows}행 · 인·일 {fmtNum(close.data.totalMandays)} · 잠금 {close.data.lockedEntries}건
            </p>
          )}
        </Card>
      )}
      <Card title="월 마감 이력">
        {list.error && <ErrorBox error={list.error} />}
        <table className="simple" data-testid="hrq-month-list">
          <thead>
            <tr><th>월</th><th>상태</th><th className="num">인·일</th><th>마감</th><th>재오픈</th><th>사유</th>{isAdmin && <th />}</tr>
          </thead>
          <tbody>
            {(list.data ?? []).map((m) => (
              <tr key={m.year_month} data-testid={`hrq-month-${m.year_month}`}>
                <td className="mono">{ymLabel(m.year_month)}</td>
                <td><Badge tone={MC_STATUS[m.status].tone}>{MC_STATUS[m.status].label}</Badge></td>
                <td className="num">{fmtNum(m.total_mandays)}</td>
                <td>{fmtDateTime(m.closed_at)} {m.closed_by && <span className="muted">· {label(m.closed_by)}</span>}</td>
                <td>{fmtDateTime(m.reopened_at)} {m.reopened_by && <span className="muted">· {label(m.reopened_by)}</span>}</td>
                <td>{m.reopen_reason ?? '—'}</td>
                {isAdmin && (
                  <td>
                    {m.status === 'CLOSED' && (
                      <button className="danger" onClick={() => setReopenFor(m.year_month)} aria-label={`HRQ 재오픈 ${ymLabel(m.year_month)}`}>
                        재오픈
                      </button>
                    )}
                  </td>
                )}
              </tr>
            ))}
            {list.data?.length === 0 && (
              <tr><td colSpan={7} className="muted">마감 이력이 없습니다</td></tr>
            )}
          </tbody>
        </table>
      </Card>
      {reopenFor && (
        <ReopenDialog
          title={`HRQ ${ymLabel(reopenFor)} 재오픈`}
          onClose={() => setReopenFor(undefined)}
          pending={reopen.isPending}
          onConfirm={(reason) => reopen.mutate({ k: reopenFor, reason })}
        />
      )}
    </>
  );
}
