import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  AGING_LABEL, cstBillingApi, lastDayOfPrevMonth, type AgingBucket, type AgingRow, type MatchCandidate, type OpenItem,
} from '@/api/core';
import { useAuth } from '@/auth/auth';
import { Badge, Banner, Card, ErrorBox, Field, fmtMoney, fmtNum, Kpi } from '@/components/ui';

const BUCKETS: AgingBucket[] = ['A_0_30', 'B_31_60', 'C_61_90', 'D_91_180', 'E_181_PLUS'];

/** 억 원 표시(소수 1자리). 계산은 서버 값(원)만 쓰고 여기서는 보여 주기만 한다 */
export function fmtEok(v: number) {
  return `${(v / 1e8).toFixed(1)}억 원`;
}

/** aging 막대의 너비(%) — 가장 큰 구간을 100 으로 */
export function agingBars(rows: AgingRow[]): { bucket: AgingBucket; label: string; count: number; openKrw: number; pct: number }[] {
  const byBucket = new Map(rows.map((r) => [r.bucket, r]));
  const max = Math.max(1, ...rows.map((r) => r.openKrw));
  return BUCKETS.map((b) => {
    const r = byBucket.get(b);
    const open = r?.openKrw ?? 0;
    return { bucket: b, label: AGING_LABEL[b], count: r?.invoiceCount ?? 0, openKrw: open, pct: Math.round((open / max) * 100) };
  });
}

function AgingPanel({ rows }: { rows: AgingRow[] }) {
  const bars = agingBars(rows);
  const total = rows.reduce((a, r) => a + r.openKrw, 0);
  const overdue = rows.filter((r) => r.bucket === 'D_91_180' || r.bucket === 'E_181_PLUS').reduce((a, r) => a + r.openKrw, 0);
  return (
    <Card title="미수 aging (발행 뒤 지난 날수)">
      <div className="row" style={{ gap: 16, marginBottom: 8 }}>
        <Kpi label="미수 합계" value={fmtEok(total)} />
        <Kpi label="90일 초과(D·E) — 독촉 대상" value={fmtEok(overdue)} tone={overdue > 0 ? 'danger' : 'ok'} />
        <Kpi label="청구 건수" value={fmtNum(rows.reduce((a, r) => a + r.invoiceCount, 0), 0)} />
      </div>
      <table className="simple" data-testid="aging-table">
        <thead><tr><th>구간</th><th className="num">건수</th><th className="num">미수 (원)</th><th style={{ width: '40%' }}>막대</th></tr></thead>
        <tbody>
          {bars.map((b) => (
            <tr key={b.bucket}>
              <td>{b.label}</td>
              <td className="num">{fmtNum(b.count, 0)}</td>
              <td className="num">{fmtMoney(b.openKrw)}</td>
              <td>
                <div aria-label={`${b.label} ${b.pct}%`} style={{ height: 12, width: `${b.pct}%`, minWidth: b.pct ? 2 : 0,
                  background: b.bucket === 'D_91_180' || b.bucket === 'E_181_PLUS' ? 'var(--danger, #b3261e)' : 'var(--accent, #2d5a85)' }} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function OpenItemsPanel({ items }: { items: OpenItem[] }) {
  const [q, setQ] = useState('');
  const rows = useMemo(() => {
    const k = q.trim().toLowerCase();
    return k ? items.filter((r) => `${r.clientName} ${r.invoiceNo}`.toLowerCase().includes(k)) : items;
  }, [items, q]);
  return (
    <Card title={`미수 목록 (${rows.length} / ${items.length}건)`}
      actions={<input placeholder="발주처 · 청구 번호 검색" value={q} onChange={(e) => setQ(e.target.value)} aria-label="미수 검색" />}>
      <table className="simple" data-testid="open-items">
        <thead><tr><th>발주처</th><th>청구 번호</th><th>발행일</th><th className="num">합계</th><th className="num">받은 돈</th><th className="num">미수</th><th className="num">경과일</th><th>구간</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.invoiceId}>
              <td>{r.clientName}</td><td>{r.invoiceNo}</td><td>{r.issueDate}</td>
              <td className="num">{fmtMoney(r.totalKrw)}</td><td className="num">{fmtMoney(r.allocatedKrw)}</td>
              <td className="num">{fmtMoney(r.openKrw)}</td><td className="num">{r.ageDays}</td>
              <td><Badge tone={r.ageDays > 90 ? 'danger' : r.ageDays > 30 ? 'warn' : 'info'}>{AGING_LABEL[r.bucket]}</Badge></td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function CandidatesPanel({ asOf, canPropose }: { asOf: string; canPropose: boolean }) {
  const qc = useQueryClient();
  const [minScore, setMinScore] = useState(60);
  const cands = useQuery({ queryKey: ['cst', 'candidates', asOf, minScore], queryFn: () => cstBillingApi.candidates(asOf, minScore), enabled: canPropose });
  const propose = useMutation({
    mutationFn: (c: MatchCandidate) => cstBillingApi.propose(c.invoiceId, c.receiptId, Math.min(c.openKrw, c.remainingKrw)),
    onSuccess: (p) => {
      toast.success(`제안 등록 — 점수 ${p.matchScore}. 다른 사람(경영지원 · 임원)이 확정합니다.`);
      qc.invalidateQueries({ queryKey: ['cst', 'candidates'] });
    },
  });
  if (!canPropose) return null;
  return (
    <Card title="통장 입금 ↔ 청구 짝 후보 (점수 = 금액 50 + 이름 30 + 날짜 20)"
      actions={<Field label="최소 점수"><input type="number" min={0} max={100} value={minScore} onChange={(e) => setMinScore(Number(e.target.value))} aria-label="최소 점수" style={{ width: 70 }} /></Field>}>
      {cands.error && <ErrorBox error={cands.error} />}
      {cands.data?.length === 0 && <p className="muted">후보가 없습니다 — 남은 입금이 없거나 점수가 기준보다 낮습니다.</p>}
      <table className="simple" data-testid="candidates">
        <thead><tr><th>점수</th><th>발주처 · 청구</th><th>발행일</th><th className="num">미수</th><th>입금(거래내용)</th><th>입금일</th><th className="num">입금 잔액</th><th /></tr></thead>
        <tbody>
          {cands.data?.map((c) => (
            <tr key={`${c.invoiceId}:${c.receiptId}`}>
              <td title={`금액 ${c.amountScore} + 이름 ${c.nameScore} + 날짜 ${c.dateScore}`}>
                <Badge tone={c.autoCandidate ? 'ok' : c.score >= 70 ? 'warn' : 'info'}>{c.score}</Badge>
              </td>
              <td>{c.clientName} · {c.invoiceNo}</td><td>{c.issueDate}</td><td className="num">{fmtMoney(c.openKrw)}</td>
              <td>{c.payerText}</td><td>{c.valueDate}</td><td className="num">{fmtMoney(c.remainingKrw)}</td>
              <td><button onClick={() => propose.mutate(c)} disabled={propose.isPending} data-testid={`propose-${c.invoiceNo}`}>제안</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

/**
 * CST › 미수 현황 · 통장 대사 (docs/CST_BILLING_COLLECTION_DESIGN.md §10).
 * 기준일(asOf)은 화면이 정한다 — 서버의 "오늘"을 쓰지 않아 같은 기준일이면 같은 숫자가 나온다.
 * 재무(FIN)만 짝을 제안하고, 확정은 "내 할 일"에서 다른 사람이 한다.
 */
export function CstReceivablesPage() {
  const { hasRole } = useAuth();
  const canRead = hasRole('EXEC', 'ADMIN', 'FIN', 'AUDITOR');
  const canPropose = hasRole('FIN');
  const [asOf, setAsOf] = useState(lastDayOfPrevMonth());
  const aging = useQuery({ queryKey: ['cst', 'aging', asOf], queryFn: () => cstBillingApi.aging(asOf), enabled: canRead && !!asOf });
  const open = useQuery({ queryKey: ['cst', 'open-items', asOf], queryFn: () => cstBillingApi.openItems(asOf), enabled: canRead && !!asOf });
  if (!canRead) return <Banner tone="warn">미수 현황은 재무 · 경영지원 · 임원 · 감사만 봅니다.</Banner>;
  return (
    <>
      <h1>미수 현황 · 통장 대사 (CST)</h1>
      <Banner tone="info">
        기준일 <b data-testid="asof">{asOf}</b> 기준입니다. 기준일 뒤에 들어온 입금은 세지 않습니다. 구간: A 0~30 · B 31~60 · C 61~90 · D 91~180 · E 181일 이상(D · E 는 독촉 대상).
      </Banner>
      <div className="row">
        <Field label="기준일"><input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} aria-label="기준일" /></Field>
      </div>
      {aging.error && <ErrorBox error={aging.error} />}
      {aging.data && <AgingPanel rows={aging.data} />}
      {open.error && <ErrorBox error={open.error} />}
      {open.data && <OpenItemsPanel items={open.data} />}
      <CandidatesPanel asOf={asOf} canPropose={canPropose} />
    </>
  );
}
