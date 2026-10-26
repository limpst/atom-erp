import { useCallback, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import {
  AMENDABLE_FIELDS, AMENDMENT_STATUS, demApi, demLogApi, PACKAGE_TYPE_LABEL, PHASE_LABEL, type CheckResult, type ChecklistInput,
  type UpsertResult, type WorkPackage,
} from '@/api/dem';
import { coreApi } from '@/api/core';
import { newIdempotencyKey, ProblemError } from '@/api/http';
import { describeError, toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, Loading } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { todayIso, useEmployees } from '@/features/admin/common';
import { clearDraft, listDrafts, loadDraft, saveDraft, toRequest, updateDraft, type LogDraft } from './logDraft';

type SendOutcome =
  | { kind: 'sent'; result: UpsertResult & { httpStatus: number } }
  | { kind: 'offline' }
  | { kind: 'refused'; error: ProblemError };

/** 초안 1건 전송 — 네트워크 실패면 전송 대기로 남기고(같은 clientOpId), 성공하면 지운다, 4xx 는 초안으로 되돌린다 */
export async function sendDraft(d: LogDraft): Promise<SendOutcome> {
  const sending = updateDraft(d, { state: 'PENDING_SEND', attempts: d.attempts + 1 });
  try {
    const result = await demLogApi.upsert(d.wpId, d.date, toRequest(sending));
    clearDraft(d.wpId, d.date);
    return { kind: 'sent', result };
  } catch (e) {
    if (e instanceof ProblemError && (e.status === 0 || e.status >= 500)) {
      updateDraft(sending, { state: 'PENDING_SEND', lastError: e.status === 0 ? '오프라인 — 연결되면 다시 보냅니다' : `서버 오류 ${e.status}` });
      return { kind: 'offline' };
    }
    if (e instanceof ProblemError) {
      updateDraft(sending, { state: 'DRAFT', lastError: describeError(e).title });
      return { kind: 'refused', error: e };
    }
    throw e;
  }
}

const RESULT_LABEL: Record<CheckResult, string> = { PASS: '적합', FAIL: '부적합', NA: '해당 없음' };

// ================================================================== 작성(오프라인 초안)
function WriteCard({ wp, date, onSent }: { wp: WorkPackage; date: string; onSent: () => void }) {
  const { session } = useAuth();
  const draft0 = useMemo(() => loadDraft(wp.id, date), [wp.id, date]);
  const [phase, setPhase] = useState(draft0?.body.phaseCode ?? 'UPPER_DEMOLITION');
  const [progress, setProgress] = useState(draft0?.body.progressPct?.toString() ?? '');
  const [workers, setWorkers] = useState(draft0?.body.workersCount?.toString() ?? '');
  const [sky, setSky] = useState(String((draft0?.body.weather as { sky?: string } | null)?.sky ?? '맑음'));
  const [remarks, setRemarks] = useState(draft0?.body.remarks ?? '');
  const [checks, setChecks] = useState<Record<string, ChecklistInput>>(
    Object.fromEntries((draft0?.body.checklist ?? []).map((c) => [c.templateItemId, c])),
  );
  const [draft, setDraft] = useState<LogDraft | null>(draft0);
  const [outcome, setOutcome] = useState<SendOutcome>();
  const [busy, setBusy] = useState(false);
  const template = useQuery({ queryKey: ['dem', 'template', phase, date], queryFn: () => demLogApi.template(phase, date) });
  const logs = useQuery({ queryKey: ['dem', 'logs', wp.id], queryFn: () => demApi.dailyLogs(wp.id), meta: { silent: true } });
  const mine = (logs.data ?? []).find((l) => l.logDate === date && l.authorId === session?.actorId);
  const body = (submit: boolean): LogDraft['body'] => ({
    phaseCode: phase,
    progressPct: progress === '' ? null : Number(progress),
    workersCount: workers === '' ? null : Number(workers),
    weather: { sky },
    remarks: remarks || null,
    checklist: (template.data?.items ?? []).map((i) => checks[i.id] ?? { templateItemId: i.id, result: 'NA' as CheckResult }),
    deviceSignedAt: new Date().toISOString(),
    submit,
  });
  const saveLocal = () => {
    const d = saveDraft(wp.id, date, body(false), 'DRAFT');
    setDraft(d);
    toast.success('이 기기에 임시 저장했습니다(서버 전송 전)');
  };
  const submit = async () => {
    setBusy(true);
    const d = saveDraft(wp.id, date, body(true), 'PENDING_SEND');
    try {
      const o = await sendDraft(d);
      setOutcome(o);
      if (o.kind === 'sent') {
        setDraft(null);
        toast.success(o.result.replayed ? '이미 저장된 같은 요청입니다(서버가 같은 결과를 돌려줌)'
          : o.result.httpStatus === 202 ? '잠긴 일지 — 정정 요청(승인 대기)으로 접수했습니다' : '감리일지를 제출했습니다');
        onSent();
      } else setDraft(loadDraft(wp.id, date));
    } catch (e) {
      toastError(e);
    } finally {
      setBusy(false);
    }
  };
  const setCheck = (id: string, patch: Partial<ChecklistInput>) =>
    setChecks((c) => ({ ...c, [id]: { ...(c[id] ?? { templateItemId: id, result: 'NA' as CheckResult }), ...patch } }));
  const future = date > todayIso();
  return (
    <Card title={`감리일지 작성 — ${date}`} actions={draft && <span className="draft-chip" data-testid="draft-chip">
      {draft.state === 'PENDING_SEND' ? '전송 대기' : '임시 저장됨'} · 작업 ID {draft.clientOpId.slice(0, 8)}
    </span>}>
      {mine?.locked && <Banner tone="warn">이 날짜 일지는 잠겼습니다(🔒). 보내면 원문은 그대로 두고 <strong>정정 요청(승인 대기)</strong>으로 접수됩니다.</Banner>}
      {mine && !mine.locked && <Banner tone="info">이미 제출한 일지가 있습니다(잠금 전) — 다시 보내면 내용이 바뀝니다.</Banner>}
      {future && <Banner tone="danger">미래 날짜 일지는 작성할 수 없습니다.</Banner>}
      {wp.supervisorGap && <Banner tone="danger">감리자 공백 — 대체 책임 감리원 지정 전에는 서버가 거부합니다(DEM-409-006).</Banner>}
      <div className="form">
        <Field label="공정">
          <select aria-label="공정" value={phase} onChange={(e) => { setPhase(e.target.value); setChecks({}); }}>
            {Object.entries(PHASE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
        <Field label="공정률(%)"><input aria-label="공정률" type="number" min={0} max={100} value={progress} onChange={(e) => setProgress(e.target.value)} /></Field>
        <Field label="작업 인원"><input aria-label="작업 인원" type="number" min={0} value={workers} onChange={(e) => setWorkers(e.target.value)} /></Field>
        <Field label="날씨">
          <select aria-label="날씨" value={sky} onChange={(e) => setSky(e.target.value)}>
            {['맑음', '흐림', '비', '눈', '강풍'].map((s) => <option key={s}>{s}</option>)}
          </select>
        </Field>
      </div>
      <Field label="특기 사항"><textarea aria-label="특기 사항" value={remarks} onChange={(e) => setRemarks(e.target.value)} /></Field>
      {template.error && <ErrorBox error={template.error} />}
      {template.data && (
        <>
          <h3>점검표 {template.data.example && <Badge tone="warn">⚠️ 예시 템플릿</Badge>} <span className="muted">v{template.data.versionNo}</span></h3>
          <table className="simple" data-testid="dem-checklist">
            <thead><tr><th>#</th><th>항목</th><th>결과</th><th>부적합 심각도</th><th>메모</th></tr></thead>
            <tbody>
              {template.data.items.map((i) => {
                const c = checks[i.id] ?? { templateItemId: i.id, result: 'NA' as CheckResult };
                return (
                  <tr key={i.id}>
                    <td>{i.seq}</td>
                    <td>{i.title} {i.photoRequired && <Badge tone="info">사진 필수</Badge>}</td>
                    <td>
                      <select aria-label={`결과 ${i.seq}`} value={c.result} onChange={(e) => setCheck(i.id, { result: e.target.value as CheckResult })}>
                        {(Object.keys(RESULT_LABEL) as CheckResult[]).map((r) => <option key={r} value={r}>{RESULT_LABEL[r]}</option>)}
                      </select>
                    </td>
                    <td>
                      {c.result === 'FAIL' && (
                        <select aria-label={`심각도 ${i.seq}`} value={c.severity ?? ''} onChange={(e) => setCheck(i.id, { severity: (e.target.value || null) as ChecklistInput['severity'] })}>
                          <option value="">기본({i.defaultSeverity ?? '—'})</option>
                          {['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((s) => <option key={s}>{s}</option>)}
                        </select>
                      )}
                    </td>
                    <td><input aria-label={`메모 ${i.seq}`} value={c.note ?? ''} onChange={(e) => setCheck(i.id, { note: e.target.value })} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="muted">사진 필수 항목을 「적합」으로 내려면 현장 앱에서 증거 사진을 먼저 동기화해야 합니다(없으면 서버가 DEM-422-004 로 거부). 부적합은 시정지시가 자동 발행됩니다.</p>
        </>
      )}
      <div className="row" style={{ marginTop: 8 }}>
        <button onClick={saveLocal} disabled={!template.data} data-testid="log-save-local">이 기기에 임시 저장</button>
        <button className="primary" onClick={submit} disabled={busy || future || !template.data} data-testid="log-submit">제출(서버 전송)</button>
        {draft && <button onClick={() => { clearDraft(wp.id, date); setDraft(null); }}>초안 버리기</button>}
      </div>
      {outcome?.kind === 'offline' && <Banner tone="warn" testId="log-offline">오프라인이거나 서버에 닿지 않습니다 — 초안을 이 기기에 보관했고, 연결되면 같은 작업 ID 로 자동 재전송합니다.</Banner>}
      {outcome?.kind === 'refused' && (
        <Banner tone="danger" testId="log-refused">
          <strong>{describeError(outcome.error).title}</strong> {outcome.error.problem.detail}
          {Array.isArray(outcome.error.problem.invalidItems) && <div>항목: {(outcome.error.problem.invalidItems as string[]).length}건</div>}
        </Banner>
      )}
      {outcome?.kind === 'sent' && (
        <Banner tone="ok" testId="log-sent">
          {outcome.result.httpStatus === 202 ? '정정 요청 접수(승인 대기)' : '서버 저장 완료'} — 상태 {outcome.result.status}
          {outcome.result.replayed && ' · 재전송(같은 응답)'}
          {outcome.result.lateSync && ' · 늦은 동기화(LATE_SYNC)'}
          {outcome.result.correctiveOrders.length > 0 && ` · 시정지시 ${outcome.result.correctiveOrders.map((o) => o.orderNo).join(', ')}`}
        </Banner>
      )}
    </Card>
  );
}

// ================================================================== 전송 대기 초안
function PendingDrafts({ tick, resend }: { tick: number; resend: () => void }) {
  const drafts = useMemo(() => listDrafts(), [tick]); // eslint-disable-line react-hooks/exhaustive-deps
  if (drafts.length === 0) return null;
  return (
    <Card title={`이 기기의 초안 ${drafts.length}건`} actions={<button onClick={resend} data-testid="drafts-resend">전송 대기분 지금 보내기</button>}>
      <table className="simple" data-testid="dem-drafts">
        <thead><tr><th>일자</th><th>상태</th><th>작업 ID(clientOpId)</th><th>시도</th><th>마지막 오류</th></tr></thead>
        <tbody>
          {drafts.map((d) => (
            <tr key={`${d.wpId}.${d.date}`}>
              <td>{d.date}</td>
              <td><Badge tone={d.state === 'PENDING_SEND' ? 'warn' : undefined}>{d.state === 'PENDING_SEND' ? '전송 대기' : '임시 저장'}</Badge></td>
              <td className="mono">{d.clientOpId}</td>
              <td>{d.attempts}</td>
              <td>{d.lastError ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

// ================================================================== 정정 요청 · 결정
function CorrectionPanel({ logId }: { logId: string }) {
  const qc = useQueryClient();
  const { session, hasRole } = useAuth();
  const { label } = useEmployees();
  const log = useQuery({ queryKey: ['dem', 'log-full', logId], queryFn: () => demLogApi.log(logId) });
  const [field, setField] = useState('remarks');
  const [value, setValue] = useState('');
  const [reason, setReason] = useState('');
  const [key, setKey] = useState(newIdempotencyKey); // 같은 요청 재시도 = 같은 멱등 키
  const [refused, setRefused] = useState<string>();
  const refresh = () => { qc.invalidateQueries({ queryKey: ['dem'] }); qc.invalidateQueries({ queryKey: ['inbox'] }); };
  const req = useMutation({
    mutationFn: () => demLogApi.requestCorrection(logId, {
      fieldPath: field,
      newValue: field === 'progressPct' || field === 'workersCount' ? Number(value) : value,
      reason,
    }, key),
    meta: { silent: true },
    onSuccess: (d) => {
      const mineNew = [...d.amendments].reverse().find((a) => a.amendedBy === session?.actorId && a.fieldPath === field);
      toast.success(mineNew?.status === 'PENDING' ? '정정 요청을 올렸습니다 — 다른 사람의 승인을 기다립니다' : '정정이 적용됐습니다(원문은 그대로, 정정 이력에 추가)');
      setValue(''); setReason(''); setKey(newIdempotencyKey()); setRefused(undefined);
      refresh();
    },
    onError: (e) => { if (e instanceof ProblemError && e.status < 500) setRefused(`${describeError(e).title} — ${e.problem.detail ?? ''}`); else toastError(e); },
  });
  const decide = useMutation({
    mutationFn: (v: { id: string; d: 'approve' | 'reject' }) => demLogApi.decideCorrection(v.id, v.d),
    meta: { silent: true },
    onSuccess: () => { toast.success('정정 요청을 처리했습니다'); setRefused(undefined); refresh(); },
    onError: (e) => {
      if (e instanceof ProblemError && (e.status === 404 || e.status === 405)) setRefused('정정 승인 API 는 서버 정책(DEM-03b) 반영 후 열립니다 — 지금은 처리할 수 없습니다.');
      else if (e instanceof ProblemError && e.status < 500) setRefused(`${describeError(e).title} — ${e.problem.detail ?? ''}`);
      else toastError(e);
    },
  });
  if (log.error) return <ErrorBox error={log.error} />;
  if (!log.data) return <Loading />;
  const d = log.data;
  const locked = d.status === 'LOCKED';
  return (
    <Card title={`감리일지 ${d.logDate} — ${label(d.authorId)}`}>
      {locked ? (
        <Banner tone="info">🔒 잠긴 일지 — 원문 해시 <span className="mono">{d.contentSha256?.slice(0, 16)}…</span> 는 바뀌지 않습니다. 고칠 내용은 정정 요청으로 남깁니다.</Banner>
      ) : <Banner tone="ok">잠금 전 — 감리원이 위 작성 화면에서 다시 제출하면 됩니다(정정 요청 불필요).</Banner>}
      <p>{d.remarks}</p>
      <h3>정정 이력</h3>
      <table className="simple" data-testid="dem-amendments">
        <thead><tr><th>항목</th><th>새 값</th><th>사유</th><th>요청자</th><th>시각</th><th>상태</th><th /></tr></thead>
        <tbody>
          {d.amendments.map((a) => {
            const st = AMENDMENT_STATUS[a.status] ?? { label: a.status, tone: 'info' as const };
            const canDecide = a.status === 'PENDING' && a.amendedBy !== session?.actorId && hasRole('PM', 'EXEC');
            return (
              <tr key={a.id}>
                <td>{AMENDABLE_FIELDS[a.fieldPath] ?? a.fieldPath}</td>
                <td className="mono" style={{ fontSize: 11, maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis' }}>{JSON.stringify(a.newValue)}</td>
                <td>{a.reason}</td>
                <td>{label(a.amendedBy)}</td>
                <td>{fmtDateTime(a.amendedAt)}</td>
                <td><Badge tone={st.tone}>{st.label}</Badge></td>
                <td className="row" style={{ gap: 4 }}>
                  {canDecide && <>
                    <button onClick={() => decide.mutate({ id: a.id, d: 'approve' })} aria-label={`정정 승인 ${a.id.slice(0, 8)}`}>승인</button>
                    <button onClick={() => decide.mutate({ id: a.id, d: 'reject' })} aria-label={`정정 반려 ${a.id.slice(0, 8)}`}>반려</button>
                  </>}
                  {a.status === 'PENDING' && a.amendedBy === session?.actorId && <span className="muted">본인 요청 — 다른 사람이 승인</span>}
                </td>
              </tr>
            );
          })}
          {d.amendments.length === 0 && <tr><td colSpan={7} className="muted">정정 없음</td></tr>}
        </tbody>
      </table>
      {locked && hasRole('PM', 'ENG') && (
        <div style={{ marginTop: 12 }}>
          <h3>정정 요청</h3>
          <div className="form">
            <Field label="항목">
              <select aria-label="정정 항목" value={field} onChange={(e) => setField(e.target.value)}>
                {['remarks', 'progressPct', 'workersCount'].map((k) => <option key={k} value={k}>{AMENDABLE_FIELDS[k]}</option>)}
              </select>
            </Field>
            <Field label="새 값"><input aria-label="정정 값" value={value} onChange={(e) => setValue(e.target.value)} /></Field>
            <Field label="사유(필수)"><input aria-label="정정 사유" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <button className="primary" disabled={!value.trim() || !reason.trim() || req.isPending} onClick={() => req.mutate()} data-testid="correction-request">정정 요청</button>
          </div>
          <p className="muted">본인이 쓴 일지의 정정은 다른 사람(PM)의 승인을 받아야 적용됩니다(직무 분리). 승인 전에는 원문 · 유효 내용이 바뀌지 않습니다.</p>
        </div>
      )}
      {refused && <Banner tone="danger" testId="correction-refused">{refused}</Banner>}
    </Card>
  );
}

function LogsCard({ wp, sel, setSel }: { wp: WorkPackage; sel?: string; setSel: (id: string) => void }) {
  const { label } = useEmployees();
  const logs = useQuery({ queryKey: ['dem', 'logs', wp.id], queryFn: () => demApi.dailyLogs(wp.id) });
  return (
    <Card title="일지 목록">
      {logs.error && <ErrorBox error={logs.error} />}
      <table className="simple" data-testid="dem-log-list">
        <thead><tr><th>일자</th><th>작성자</th><th>공정</th><th>상태</th><th>정정</th></tr></thead>
        <tbody>
          {(logs.data ?? []).map((l) => (
            <tr key={l.id} onClick={() => setSel(l.id)} style={{ cursor: 'pointer', background: sel === l.id ? 'var(--surface-2)' : undefined }} data-testid={`log-row-${l.logDate}`}>
              <td>{l.logDate}</td>
              <td>{label(l.authorId)}</td>
              <td>{PHASE_LABEL[l.phaseCode] ?? l.phaseCode}</td>
              <td>{l.locked ? <Badge tone="info">🔒 잠금</Badge> : <Badge tone={l.status === 'SUBMITTED' ? 'ok' : 'warn'}>{l.status === 'SUBMITTED' ? '제출' : '초안'}</Badge>}</td>
              <td>{l.amendments}{l.pendingAmendments ? <> <Badge tone="warn">대기 {l.pendingAmendments}</Badge></> : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

// ================================================================== 페이지
export function DemLogWritePage() {
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const projects = useQuery({ queryKey: ['dem', 'projects'], queryFn: demApi.projects });
  const core = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects(), meta: { silent: true } });
  const names = useMemo(() => new Map((core.data ?? []).map((p) => [p.id, `${p.projectNo} ${p.name}`])), [core.data]);
  const [date, setDate] = useState(params.get('date') ?? todayIso());
  const [tick, setTick] = useState(0);
  const wps = (projects.data ?? []).flatMap((p) => p.workPackages.map((w) => ({ p, w })));
  const wpId = params.get('wp') ?? wps.find((x) => x.w.packageType === 'DEM_SUPERVISION')?.w.id ?? wps[0]?.w.id;
  const cur = wps.find((x) => x.w.id === wpId);
  const sel = params.get('log') ?? undefined;
  const setParam = (k: string, v: string) => setParams((p) => { p.set(k, v); return p; });

  const resendAll = useCallback(async () => {
    const pending = listDrafts().filter((d) => d.state === 'PENDING_SEND');
    let sent = 0;
    for (const d of pending) {
      const o = await sendDraft(d).catch(() => ({ kind: 'offline' as const }));
      if (o.kind === 'sent') sent++;
    }
    setTick((t) => t + 1);
    if (sent) {
      toast.success(`연결됨 — 전송 대기 감리일지 ${sent}건을 같은 작업 ID 로 다시 보냈습니다`);
      qc.invalidateQueries({ queryKey: ['dem'] });
    }
  }, [qc]);
  useEffect(() => {
    window.addEventListener('online', resendAll);
    return () => window.removeEventListener('online', resendAll);
  }, [resendAll]);

  if (projects.error) return <><h1>감리일지 작성 · 정정</h1><ErrorBox error={projects.error} /></>;
  if (!projects.data) return <Loading />;
  return (
    <>
      <h1>감리일지 작성 · 정정</h1>
      <Banner tone="info">현장이 오프라인이어도 작성할 수 있습니다. 제출이 실패하면 이 기기에 보관했다가 연결되면 <strong>같은 작업 ID(clientOpId)</strong>로 다시 보내 중복 저장이 생기지 않습니다. 일지는 다음 날 0시(서울)에 잠기고, 잠긴 일지는 정정 요청으로만 고칩니다.</Banner>
      <Card>
        <div className="form">
          <Field label="업무 패키지">
            <select aria-label="업무 패키지" value={wpId ?? ''} onChange={(e) => setParam('wp', e.target.value)}>
              {wps.map(({ p, w }) => (
                <option key={w.id} value={w.id}>{names.get(p.projectId) ?? p.projectId.slice(0, 8)} · {PACKAGE_TYPE_LABEL[w.packageType] ?? w.packageType}</option>
              ))}
            </select>
          </Field>
          <Field label="일지 일자">
            <input aria-label="일지 일자" type="date" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
          </Field>
        </div>
      </Card>
      <PendingDrafts tick={tick} resend={resendAll} />
      {cur && <WriteCard key={`${cur.w.id}.${date}`} wp={cur.w} date={date} onSent={() => { setTick((t) => t + 1); qc.invalidateQueries({ queryKey: ['dem'] }); }} />}
      {cur && (
        <div className="grid2">
          <LogsCard wp={cur.w} sel={sel} setSel={(id) => setParam('log', id)} />
          {sel ? <CorrectionPanel key={sel} logId={sel} /> : <Card><div className="empty">일지를 고르면 정정 이력 · 정정 요청이 보입니다</div></Card>}
        </div>
      )}
    </>
  );
}
