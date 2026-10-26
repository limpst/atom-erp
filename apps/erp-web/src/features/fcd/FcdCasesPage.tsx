import { useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  CASE_STATUS_LABEL, fcdApi, GRADE_LABEL, LABEL_LABEL, parseHits, type CaseLabel, type CaseListItem, type CaseStatus,
  type RiskGrade,
} from '@/api/fcd';
import { useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtDateTime, fmtMoney, Kpi } from '@/components/ui';

const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
const gradeTone = (g: RiskGrade) => (g === 'HIGH' ? 'danger' : g === 'EXPLAIN' ? 'warn' : g === 'WATCH' ? 'info' : 'ok');
const WAITING_EXPLANATION: CaseStatus[] = ['EXPLANATION_REQUESTED', 'OVERDUE', 'SUPPLEMENT_REQUESTED'];

function QueueTools() {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [ym, setYm] = useState('2026-05');
  const [policy, setPolicy] = useState<'THRESHOLD' | 'MONTHLY_BUDGET'>('THRESHOLD');
  const [ovr, setOvr] = useState({ kPer1000: '', minScore: '', reason: '' });
  const [file, setFile] = useState<File>();
  const isAuditor = hasRole('AUDITOR');
  const overriding = isAuditor && (ovr.kPer1000 !== '' || ovr.minScore !== '');
  const run = useMutation({
    mutationFn: () =>
      fcdApi.runReviewQueue(ym, policy, overriding ? {
        kPer1000: ovr.kPer1000 === '' ? undefined : Number(ovr.kPer1000),
        minScore: ovr.minScore === '' ? undefined : Number(ovr.minScore),
        reason: ovr.reason,
      } : undefined),
    onSuccess: (r) => {
      toast.success(`검토 큐 ${r.period}: 거래 ${r.nTxn}건 중 ${r.selected.length}건 선정, 케이스 ${r.casesOpened.length}건 열림`);
      qc.invalidateQueries({ queryKey: ['fcd'] });
    },
  });
  const upload = useMutation({
    mutationFn: async () => {
      const b = await fcdApi.uploadStatement(file!);
      if (b.status !== 'VALIDATED') {
        throw new Error(`명세서 상태 ${b.status}: ${b.warnings.join(' / ')}`);
      }
      return fcdApi.commitImport(b.id);
    },
    onSuccess: (b) => {
      toast.success(`명세서 확정 — ${b.profile_code} · ${b.rows_total}행 (확정 시 자동 점수)`);
      qc.invalidateQueries({ queryKey: ['fcd'] });
    },
  });
  if (!hasRole('FIN', 'AUDITOR', 'ADMIN')) return null;
  return (
    <Card title="수집 · 검토 큐 (FIN · AUDITOR)">
      <div className="form">
        {hasRole('FIN', 'ADMIN') && (
          <>
            <Field label="카드 명세서 (CSV · XLSX)">
              <input type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0])} />
            </Field>
            <button disabled={!file || upload.isPending} onClick={() => upload.mutate()}>
              업로드 · 확정
            </button>
          </>
        )}
        <Field label="검토 월 (YYYY-MM)">
          <input value={ym} onChange={(e) => setYm(e.target.value)} pattern="\d{4}-\d{2}" />
        </Field>
        <Field label="정책">
          <select value={policy} onChange={(e) => setPolicy(e.target.value as typeof policy)}>
            <option value="THRESHOLD">THRESHOLD (점수 60↑)</option>
            <option value="MONTHLY_BUDGET">MONTHLY_BUDGET (1,000건당 20건)</option>
          </select>
        </Field>
        {isAuditor && (
          <>
            {/* FCD-13: 기본값(1,000건당 20건 · 최소 점수 30/60) 재정의는 감사만, 사유 필수 — 서버 범위 k 5~100 · 점수 20~60 */}
            <Field label="재정의: 1,000건당 k (5~100)">
              <input type="number" min={5} max={100} value={ovr.kPer1000} onChange={(e) => setOvr({ ...ovr, kPer1000: e.target.value })} />
            </Field>
            <Field label="재정의: 최소 점수 (20~60)">
              <input type="number" min={20} max={60} value={ovr.minScore} onChange={(e) => setOvr({ ...ovr, minScore: e.target.value })} />
            </Field>
            <Field label="재정의 사유">
              <input value={ovr.reason} onChange={(e) => setOvr({ ...ovr, reason: e.target.value })} />
            </Field>
          </>
        )}
        <button className="primary" disabled={run.isPending || (overriding && !ovr.reason.trim())} onClick={() => run.mutate()}>
          검토 큐 실행
        </button>
      </div>
    </Card>
  );
}

function CaseDetailCard({ id }: { id: string }) {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const detail = useQuery({ queryKey: ['fcd', 'case', id], queryFn: () => fcdApi.case(id) });
  const c = detail.data;
  const risks = useQueries({
    queries: (c?.txnIds ?? []).map((t) => ({ queryKey: ['fcd', 'risk', t], queryFn: () => fcdApi.risk(t), meta: { silent: true } })),
  });
  const txns = useQuery({
    queryKey: ['fcd', 'txns'],
    queryFn: () => fcdApi.transactions(),
    enabled: !!c,
    meta: { silent: true },
  });
  const done = (msg: string) => {
    toast.success(msg);
    qc.invalidateQueries({ queryKey: ['fcd'] });
  };
  const requestExp = useMutation({ mutationFn: () => fcdApi.requestExplanation(id), onSuccess: (r) => done(`소명 요청 발송 — 기한 ${r.due}`) });
  const supplement = useMutation({ mutationFn: () => fcdApi.requestSupplement(id, '증빙 보완 요청(합성)'), onSuccess: () => done('보완 요청') });
  const escalate = useMutation({ mutationFn: () => fcdApi.escalate(id), onSuccess: () => done('상향(ESCALATED)') });
  const [expl, setExpl] = useState({ purpose: 'BUSINESS', text: '' });
  const explain = useMutation({ mutationFn: () => fcdApi.explain(id, { ...expl, attendees: [{ type: 'INTERNAL' }] }), onSuccess: () => done('소명 제출') });
  const [verdict, setVerdict] = useState<{ label: CaseLabel; reason: string; recoveryAmount: string }>({ label: 'LEGIT', reason: '', recoveryAmount: '' });
  const resolve = useMutation({
    mutationFn: () =>
      fcdApi.resolve(id, {
        label: verdict.label,
        reason: verdict.reason,
        recoveryAmount: verdict.label === 'MISUSE' && verdict.recoveryAmount ? Number(verdict.recoveryAmount) : undefined,
      }),
    onSuccess: (r) => done(`판정: ${LABEL_LABEL[r.label]} (${CASE_STATUS_LABEL[r.status]})`),
  });

  if (detail.error) return <ErrorBox error={detail.error} />;
  if (!c) return null;
  const isSubject = session?.actorId === c.subject_employee_id;
  const txnMap = new Map((txns.data ?? []).map((t) => [t.id, t]));
  return (
    <Card
      title={
        <>
          {c.case_no} <Badge tone={gradeTone(c.grade)}>{GRADE_LABEL[c.grade]}</Badge>{' '}
          <Badge>
            <span data-testid="case-status">{CASE_STATUS_LABEL[c.status]}</span>
          </Badge>
        </>
      }
    >
      <div className="kpis">
        <Kpi label="위험 점수" value={c.score} tone={c.score >= 80 ? 'danger' : undefined} />
        <Kpi label="대상 직원" value={c.subject_employee_id ? devName.get(c.subject_employee_id) ?? c.subject_employee_id.slice(0, 8) : '—'} />
        <Kpi label="소명 기한" value={c.due_on} />
        <Kpi label="정지 검토 후보" value={c.suspension_candidate ? '예' : '아니오'} />
      </div>
      <p className="muted">{c.opened_reason}</p>

      <h3>거래 · 탐지 규칙</h3>
      <table className="simple">
        <thead>
          <tr>
            <th>승인 일시</th>
            <th>가맹점</th>
            <th className="num">금액</th>
            <th>탐지 규칙 (hits)</th>
          </tr>
        </thead>
        <tbody>
          {c.txnIds.map((t, i) => {
            const tx = txnMap.get(t);
            const latest = risks[i]?.data?.[0];
            return (
              <tr key={t}>
                <td>{tx ? fmtDateTime(tx.approved_at) : t.slice(0, 8)}</td>
                <td>{tx?.merchant_name ?? ''}</td>
                <td className="num">{tx ? fmtMoney(tx.amount_krw) : ''}</td>
                <td>
                  {latest?.summary ? (
                    // FCD-18: 직원 본인에게는 적중 요약만(규칙 · 임계값 비공개)
                    <span className="muted" data-testid={`hit-summary-${t}`}>확인이 필요한 항목 {latest.hit_count ?? 0}건 (세부 탐지 기준 비공개)</span>
                  ) : latest
                    ? parseHits(latest).map((h) => (
                        <div key={h.code}>
                          <span className="mono">{h.code}</span> {h.message}
                        </div>
                      ))
                    : ''}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <div className="row" style={{ marginTop: 12 }}>
        {c.status === 'OPEN' && hasRole('FIN', 'AUDITOR') && (
          <button className="primary" onClick={() => requestExp.mutate()} data-testid="request-explanation">
            소명 요청
          </button>
        )}
        {c.status === 'SUBMITTED' && hasRole('FIN', 'AUDITOR') && <button onClick={() => supplement.mutate()}>보완 요청</button>}
        {c.status === 'SUBMITTED' && hasRole('AUDITOR', 'EXEC') && <button onClick={() => escalate.mutate()}>상향</button>}
      </div>

      {WAITING_EXPLANATION.includes(c.status) && (
        isSubject ? (
          <div className="card" style={{ marginTop: 12 }}>
            <h3>소명 제출 (본인)</h3>
            <div className="form">
              <Field label="목적">
                <select value={expl.purpose} onChange={(e) => setExpl({ ...expl, purpose: e.target.value })}>
                  <option value="BUSINESS">업무 (BUSINESS)</option>
                  <option value="MEAL">회식 · 식대 (MEAL)</option>
                  <option value="SUPPLIES">물품 구매 (SUPPLIES)</option>
                  <option value="OTHER">기타 (OTHER)</option>
                </select>
              </Field>
            </div>
            <textarea aria-label="소명 내용" value={expl.text} onChange={(e) => setExpl({ ...expl, text: e.target.value })} placeholder="사용 경위와 증빙을 적어 주세요(합성)" />
            <button className="primary" disabled={!expl.text || explain.isPending} onClick={() => explain.mutate()}>
              소명 제출
            </button>
          </div>
        ) : (
          <p className="muted">대상 직원의 소명을 기다리는 중입니다.</p>
        )
      )}

      {(c.status === 'SUBMITTED' || c.status === 'ESCALATED') && hasRole('AUDITOR', 'EXEC') && (
        <div className="card" style={{ marginTop: 12 }}>
          <h3>판정 (감사 · 본인 · 결재자 제외 — BR-FCD-018)</h3>
          {isSubject && <p className="error-box">본인 케이스는 판정할 수 없습니다(서버도 403 FCD-403-001).</p>}
          <div className="form">
            <Field label="판정">
              <select value={verdict.label} onChange={(e) => setVerdict({ ...verdict, label: e.target.value as CaseLabel })}>
                {Object.entries(LABEL_LABEL).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v} ({k})
                  </option>
                ))}
              </select>
            </Field>
            <Field label="사유">
              <input value={verdict.reason} onChange={(e) => setVerdict({ ...verdict, reason: e.target.value })} />
            </Field>
            {verdict.label === 'MISUSE' && (
              <Field label="환수 금액">
                <input type="number" value={verdict.recoveryAmount} onChange={(e) => setVerdict({ ...verdict, recoveryAmount: e.target.value })} />
              </Field>
            )}
            <button className="primary" disabled={!verdict.reason || resolve.isPending} onClick={() => resolve.mutate()}>
              판정 확정
            </button>
          </div>
        </div>
      )}

      <h3>이력</h3>
      <table className="simple">
        <tbody>
          {c.history.map((h, i) => (
            <tr key={i}>
              <td>{fmtDateTime(h.at)}</td>
              <td className="mono">{h.event}</td>
              <td>
                {h.from_status ? CASE_STATUS_LABEL[h.from_status] : '—'} → {CASE_STATUS_LABEL[h.to_status]}
              </td>
              <td>{devName.get(h.actor_id) ?? h.actor_id.slice(0, 8)}</td>
              <td>{h.note ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

export function FcdCasesPage() {
  const cases = useQuery({ queryKey: ['fcd', 'cases'], queryFn: fcdApi.cases });
  const [sel, setSel] = useState<string>();
  const cols = useMemo<ColDef<CaseListItem>[]>(
    () => [
      { field: 'case_no', headerName: '케이스', width: 160 },
      { field: 'status', headerName: '상태', width: 120, valueFormatter: (p) => CASE_STATUS_LABEL[p.value as CaseStatus] },
      { field: 'grade', headerName: '등급', width: 100, valueFormatter: (p) => GRADE_LABEL[p.value as RiskGrade] },
      { field: 'score', headerName: '점수', width: 80, type: 'rightAligned', sort: 'desc' },
      { headerName: '대상', width: 100, valueGetter: (p) => (p.data!.subject_employee_id ? devName.get(p.data!.subject_employee_id) ?? '—' : '—') },
      { field: 'due_on', headerName: '기한', width: 115 },
    ],
    [],
  );
  const waiting = (cases.data ?? []).filter((c) => WAITING_EXPLANATION.includes(c.status)).length;
  return (
    <>
      <h1>법인카드 검토 큐</h1>
      <QueueTools />
      <div className="grid2">
        <Card title="케이스" actions={<span className="muted">소명 대기 {waiting}건</span>}>
          {cases.error ? (
            <ErrorBox error={cases.error} />
          ) : (
            <DataGrid<CaseListItem> testId="cases-grid" rowData={cases.data} columnDefs={cols} height={420} onRowClicked={(e) => setSel(e.data?.id)} />
          )}
        </Card>
        <div>{sel ? <CaseDetailCard id={sel} /> : <div className="empty">케이스를 선택하세요</div>}</div>
      </div>
    </>
  );
}
