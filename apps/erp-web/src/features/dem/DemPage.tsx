import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import {
  COMMENT_NEXT, COMMENT_STATUS_LABEL, constructionBlockers, credentialWarning, demApi, PACKAGE_TYPE_LABEL, PHASE_LABEL,
  PROJECT_STATUS_LABEL, PURPOSE_LABEL, REVISION_STATUS_LABEL, type DemDocument, type Revision, type TransmittalPurpose,
  type WorkPackage,
} from '@/api/dem';
import { http, ProblemError } from '@/api/http';
import { toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, Loading } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { EmployeeSelect, todayIso, useEmployees } from '@/features/admin/common';

interface HrqCredential { id: string; typeCode: string; certNo: string; expiresOn: string | null; status: string }

const REASON_LABEL: Record<string, string> = {
  CREDENTIAL_EXPIRED: '자격 만료', NO_VALID_QUALIFICATION: '유효한 감리 자격 없음', EMPLOYEE_TERMINATED: '퇴사자',
};

// ================================================================== 감리자
function SupervisorsTab({ wp }: { wp: WorkPackage }) {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const today = todayIso();
  const { employees, label } = useEmployees();
  const sup = useQuery({ queryKey: ['dem', 'sup', wp.id], queryFn: () => demApi.supervisors(wp.id) });
  const [emp, setEmp] = useState('');
  const [role, setRole] = useState<'CHIEF' | 'ASSISTANT'>('ASSISTANT');
  const [from, setFrom] = useState(today);
  const [refused, setRefused] = useState<{ title: string; reasons: string[] }>();
  // 선택한 직원의 HRQ 자격 — 배정 전에 만료 · 임박 경고
  const creds = useQuery({
    queryKey: ['hrq', 'employee', emp],
    queryFn: () => http.get<{ credentials: HrqCredential[] }>(`/hrq/employees/${emp}`),
    enabled: !!emp,
    meta: { silent: true },
  });
  const assign = useMutation({
    mutationFn: () => demApi.assign(wp.id, { employeeId: emp, role, validFrom: from }),
    meta: { silent: true },
    onSuccess: (a) => {
      toast.success(`${label(a.employeeId)} ${a.role === 'CHIEF' ? '책임' : '보조'} 감리원 지정`);
      setRefused(undefined);
      qc.invalidateQueries({ queryKey: ['dem'] });
    },
    onError: (e) => {
      if (e instanceof ProblemError && ['DEM-422-002', 'DEM-422-003', 'DEM-409-003'].includes(e.code ?? '')) {
        setRefused({ title: `[${e.code}] ${e.problem.title}`, reasons: (e.problem.reasons as string[]) ?? [] });
      } else toastError(e);
    },
  });
  const active = (sup.data?.assignments ?? []).filter((a) => a.status === 'ACTIVE');
  const alerts = (sup.data?.alerts ?? []).filter((a) => !a.resolvedAt);
  const warnCount = active.filter((a) => a.hrqCheckSnapshot?.credentials.some((c) => credentialWarning(c, today))).length;
  return (
    <>
      {wp.supervisorGap && <Banner tone="danger">감리자 공백 — 책임 감리원 자격이 끊겼습니다. 새 책임 감리원을 지정할 때까지 감리일지 작성이 막힙니다(DEM-409-006).</Banner>}
      {alerts.map((a) => (
        <Banner key={a.id} tone="danger">
          {a.kind === 'CREDENTIAL_EXPIRED' ? '자격 만료 알림' : '퇴사 알림'}: {label(a.employeeId)} ({fmtDateTime(a.createdAt)})
        </Banner>
      ))}
      {warnCount > 0 && <Banner tone="warn" testId="dem-cred-warning">자격 만료 · 만료 임박 감리원 {warnCount}명 — 만료되면 이 패키지는 감리자 공백이 됩니다.</Banner>}
      {sup.error && <ErrorBox error={sup.error} />}
      <table className="simple" data-testid="dem-supervisors">
        <thead><tr><th>감리원</th><th>구분</th><th>기간</th><th>상태</th><th>자격(배정 시 확인)</th></tr></thead>
        <tbody>
          {(sup.data?.assignments ?? []).map((a) => (
            <tr key={a.id}>
              <td>{label(a.employeeId)}</td>
              <td>{a.role === 'CHIEF' ? '책임' : '보조'}</td>
              <td>{a.validFrom} ~ {a.validTo ?? ''}</td>
              <td><Badge tone={a.status === 'ACTIVE' ? 'ok' : undefined}>{a.status === 'ACTIVE' ? '활동' : '해제'}</Badge></td>
              <td>
                {(a.hrqCheckSnapshot?.credentials ?? []).map((c) => {
                  const w = credentialWarning(c, today);
                  return (
                    <span key={c.credentialId} style={{ marginRight: 6 }}>
                      {c.typeCode}{c.expiresOn ? ` ~${c.expiresOn}` : ''} {w ? <Badge tone={w.tone}>{w.label}</Badge> : <Badge tone="ok">유효</Badge>}
                    </span>
                  );
                })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {hasRole('PM') && (
        <div style={{ marginTop: 16 }}>
          <h3>감리원 지정</h3>
          <div className="form">
            <Field label="직원">
              <EmployeeSelect employees={employees} value={emp} onChange={(v) => { setEmp(v); setRefused(undefined); }} exclude={new Set(active.map((a) => a.employeeId))} testId="dem-assign-employee" />
            </Field>
            <Field label="구분">
              <select aria-label="감리 구분" value={role} onChange={(e) => setRole(e.target.value as 'CHIEF' | 'ASSISTANT')}>
                <option value="CHIEF">책임 감리원</option>
                <option value="ASSISTANT">보조 감리원</option>
              </select>
            </Field>
            <Field label="시작일">
              <input aria-label="감리 시작일" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </Field>
            <button className="primary" disabled={!emp || assign.isPending} onClick={() => assign.mutate()} data-testid="dem-assign">지정</button>
          </div>
          {emp && creds.data && (
            <p data-testid="dem-candidate-creds">
              HRQ 자격:{' '}
              {creds.data.credentials.length === 0 && <Badge tone="warn">등록된 자격 없음</Badge>}
              {creds.data.credentials.map((c) => {
                const w = credentialWarning({ status: c.status, expiresOn: c.expiresOn, expiredEventReceived: false }, today);
                return (
                  <span key={c.id} style={{ marginRight: 6 }}>
                    {c.typeCode}({c.status}{c.expiresOn ? ` ~${c.expiresOn}` : ''}) {w && <Badge tone={w.tone}>{w.label}</Badge>}
                  </span>
                );
              })}
            </p>
          )}
          {refused && (
            <Banner tone="danger" testId="dem-assign-refused">
              <strong>{refused.title}</strong>
              {refused.reasons.length > 0 && <div>사유: {refused.reasons.map((r) => REASON_LABEL[r] ?? r).join(', ')}</div>}
            </Banner>
          )}
        </div>
      )}
    </>
  );
}

// ================================================================== 감리일지
function LogsTab({ wp }: { wp: WorkPackage }) {
  const { label } = useEmployees();
  const logs = useQuery({ queryKey: ['dem', 'logs', wp.id], queryFn: () => demApi.dailyLogs(wp.id) });
  const [sel, setSel] = useState<string>();
  const detail = useQuery({ queryKey: ['dem', 'log', sel], queryFn: () => demApi.dailyLog(sel!), enabled: !!sel });
  return (
    <>
      <p className="muted">감리일지는 다음 날 0시(서울)에 잠깁니다. 잠긴 일지는 내용 해시가 고정되고, 바꾸려면 정정(amendment) 기록을 남겨야 합니다.</p>
      {logs.error && <ErrorBox error={logs.error} />}
      <table className="simple" data-testid="dem-logs">
        <thead><tr><th>일자</th><th>작성자</th><th>공정</th><th>진척</th><th>인원</th><th>상태</th><th>잠금 시각</th><th>내용 해시</th><th>정정</th></tr></thead>
        <tbody>
          {(logs.data ?? []).map((l) => (
            <tr key={l.id} onClick={() => setSel(l.id)} style={{ cursor: 'pointer', background: sel === l.id ? 'var(--surface-2)' : undefined }}>
              <td>{l.logDate}</td>
              <td>{label(l.authorId)}</td>
              <td>{PHASE_LABEL[l.phaseCode] ?? l.phaseCode}</td>
              <td className="num">{l.progressPct ?? '—'}%</td>
              <td className="num">{l.workersCount ?? '—'}</td>
              <td>{l.locked ? <Badge tone="info">🔒 잠금</Badge> : <Badge tone={l.status === 'SUBMITTED' ? 'ok' : 'warn'}>{l.status === 'SUBMITTED' ? '제출(잠금 전)' : '초안'}</Badge>}</td>
              <td>{fmtDateTime(l.lockedAt)}</td>
              <td className="mono" style={{ fontSize: 11 }}>{l.contentSha256 ? `${l.contentSha256.slice(0, 12)}…` : '—'}</td>
              <td>{l.amendments}{l.pendingAmendments ? ` (대기 ${l.pendingAmendments})` : ''}</td>
            </tr>
          ))}
          {logs.data?.length === 0 && <tr><td colSpan={9} className="muted">감리일지 없음</td></tr>}
        </tbody>
      </table>
      {sel && detail.data && (
        <Card title={`감리일지 ${detail.data.logDate}`}>
          {detail.data.status === 'LOCKED' ? (
            <Banner tone="info">잠긴 일지입니다(읽기 전용) — 잠금 {fmtDateTime(detail.data.lockedAt)}, 해시 <span className="mono">{detail.data.contentSha256}</span></Banner>
          ) : (
            <Banner tone="ok">잠금 전 — 감리원이 현장 앱에서 오늘 안에 고칠 수 있습니다.</Banner>
          )}
          <p>{detail.data.remarks}</p>
          <table className="simple">
            <thead><tr><th>#</th><th>점검 항목</th><th>결과</th><th>시정 지시</th></tr></thead>
            <tbody>
              {detail.data.checklist.map((c) => (
                <tr key={c.id}>
                  <td>{c.seq}</td>
                  <td>{c.title}</td>
                  <td><Badge tone={c.result === 'PASS' ? 'ok' : c.result === 'FAIL' ? 'danger' : undefined}>{c.result}</Badge> {c.note}</td>
                  <td>{c.correctiveOrderNo ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}

// ================================================================== 도서 · 트랜스미털 · 검토의견
function CommentsPanel({ rev, onChanged }: { rev: Revision; onChanged: () => void }) {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const list = useQuery({ queryKey: ['dem', 'comments', rev.id], queryFn: () => demApi.comments(rev.id) });
  const [body, setBody] = useState('');
  const [severity, setSeverity] = useState('MINOR');
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['dem', 'comments', rev.id] });
    onChanged();
  };
  const add = useMutation({
    mutationFn: () => demApi.addComment(rev.id, { source: 'INTERNAL', severity, body }),
    onSuccess: (c) => { toast.success(`검토의견 ${c.commentNo} 등록`); setBody(''); refresh(); },
  });
  const reply = useMutation({
    mutationFn: (v: { id: string; to: keyof typeof COMMENT_NEXT }) =>
      demApi.reply(v.id, { body: `(웹) ${COMMENT_STATUS_LABEL[v.to]} 처리`, resultingStatus: v.to }),
    onSuccess: (c) => { toast.success(`${c.commentNo} → ${COMMENT_STATUS_LABEL[c.status]}`); refresh(); },
  });
  const canWrite = hasRole('QA', 'PM', 'ENG');
  return (
    <Card title={`검토의견 — ${rev.docNo} Rev.${rev.revCode}`}>
      <table className="simple" data-testid="dem-comments">
        <thead><tr><th>번호</th><th>출처 · 중요도</th><th>내용</th><th>상태</th><th /></tr></thead>
        <tbody>
          {(list.data ?? []).map((c) => (
            <tr key={c.id}>
              <td>{c.commentNo}</td>
              <td>{c.source} · <Badge tone={c.severity === 'MINOR' ? undefined : 'warn'}>{c.severity}</Badge></td>
              <td>{c.body}</td>
              <td><Badge tone={c.unresolved ? 'danger' : 'ok'}>{COMMENT_STATUS_LABEL[c.status]}</Badge></td>
              <td className="row" style={{ gap: 4 }}>
                {canWrite && COMMENT_NEXT[c.status].map((to) => (
                  <button key={to} onClick={() => reply.mutate({ id: c.id, to })} aria-label={`${c.commentNo} ${COMMENT_STATUS_LABEL[to]}`}>
                    {COMMENT_STATUS_LABEL[to]}
                  </button>
                ))}
              </td>
            </tr>
          ))}
          {list.data?.length === 0 && <tr><td colSpan={5} className="muted">검토의견 없음</td></tr>}
        </tbody>
      </table>
      {canWrite && rev.status !== 'SUPERSEDED' && (
        <div className="form" style={{ marginTop: 12 }}>
          <Field label="새 검토의견">
            <input aria-label="새 검토의견" value={body} onChange={(e) => setBody(e.target.value)} />
          </Field>
          <Field label="중요도">
            <select aria-label="의견 중요도" value={severity} onChange={(e) => setSeverity(e.target.value)}>
              <option value="MINOR">MINOR</option><option value="MAJOR">MAJOR</option><option value="CRITICAL">CRITICAL</option>
            </select>
          </Field>
          <button disabled={!body.trim() || add.isPending} onClick={() => add.mutate()}>의견 등록</button>
        </div>
      )}
    </Card>
  );
}

function DrawingsTab({ wp }: { wp: WorkPackage }) {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const docs = useQuery({ queryKey: ['dem', 'docs', wp.id], queryFn: () => demApi.documents(wp.id) });
  const trs = useQuery({ queryKey: ['dem', 'trs', wp.id], queryFn: () => demApi.transmittals(wp.id) });
  const [rev, setRev] = useState<Revision>();
  const [purpose, setPurpose] = useState<TransmittalPurpose>('FOR_CONSTRUCTION');
  const [picked, setPicked] = useState<string[]>([]);
  const [refused, setRefused] = useState<string>();
  const refresh = () => qc.invalidateQueries({ queryKey: ['dem'] });
  const approvedRevs = (docs.data ?? []).flatMap((d) => d.revisions.filter((r) => r.status === 'APPROVED'));
  const blockers = purpose === 'FOR_CONSTRUCTION' ? constructionBlockers(docs.data ?? [], picked) : [];
  const issue = useMutation({
    mutationFn: () => demApi.issue({ workPackageId: wp.id, purpose, revisionIds: picked, recipientName: '(합성) 시공사 현장소장' }),
    meta: { silent: true },
    onSuccess: (t) => { toast.success(`${t.transmittalNo} ${PURPOSE_LABEL[t.purpose]} 발행`); setPicked([]); setRefused(undefined); refresh(); },
    onError: (e) => {
      if (e instanceof ProblemError && e.code === 'DEM-409-002') setRefused(`[DEM-409-002] 미결 검토의견 ${String(e.problem.openCount ?? '')}건 — 시공용 발행 불가`);
      else toastError(e);
    },
  });
  const revCmd = useMutation({
    mutationFn: (v: { id: string; cmd: 'request-review' | 'approve' | 'reject' }) => demApi.revisionCommand(v.id, v.cmd),
    onSuccess: (r) => { toast.success(`${r.docNo} Rev.${r.revCode} → ${REVISION_STATUS_LABEL[r.status]}`); refresh(); },
  });
  const unresolvedTotal = (docs.data ?? []).reduce((s, d) => s + d.unresolvedComments, 0);
  return (
    <>
      {docs.error && <ErrorBox error={docs.error} />}
      <table className="simple" data-testid="dem-docs">
        <thead><tr><th>도서</th><th>리비전</th><th>상태</th><th>발행 번호</th><th>미결 의견</th><th /></tr></thead>
        <tbody>
          {(docs.data ?? []).flatMap((d: DemDocument) =>
            d.revisions.map((r, i) => (
              <tr key={r.id} style={{ background: rev?.id === r.id ? 'var(--surface-2)' : undefined }}>
                <td>{i === 0 ? <><strong>{d.docNo}</strong> {d.title}</> : ''}</td>
                <td>Rev.{r.revCode} <span className="muted">{r.changeSummary}</span></td>
                <td><Badge tone={r.status === 'ISSUED' ? 'ok' : r.status === 'APPROVED' ? 'info' : r.status === 'SUPERSEDED' ? undefined : 'warn'}>{REVISION_STATUS_LABEL[r.status]}</Badge></td>
                <td>{r.issuedRev ?? '—'}</td>
                <td>{i === 0 ? <Badge tone={d.unresolvedComments ? 'danger' : 'ok'}>{d.unresolvedComments}건</Badge> : ''}</td>
                <td className="row" style={{ gap: 4 }}>
                  <button onClick={() => setRev(r)} aria-label={`의견 ${d.docNo} ${r.revCode}`}>의견</button>
                  {r.status === 'WIP' && hasRole('PM', 'ENG') && <button onClick={() => revCmd.mutate({ id: r.id, cmd: 'request-review' })}>검토 요청</button>}
                  {r.status === 'INTERNAL_REVIEW' && hasRole('PM') && <button onClick={() => revCmd.mutate({ id: r.id, cmd: 'approve' })}>승인</button>}
                </td>
              </tr>
            )),
          )}
        </tbody>
      </table>
      {hasRole('PM') && (
        <Card title="트랜스미털 발행">
          <div className="form">
            <Field label="목적">
              <select aria-label="발행 목적" value={purpose} onChange={(e) => { setPurpose(e.target.value as TransmittalPurpose); setRefused(undefined); }}>
                {(Object.keys(PURPOSE_LABEL) as TransmittalPurpose[]).map((p) => <option key={p} value={p}>{PURPOSE_LABEL[p]}</option>)}
              </select>
            </Field>
          </div>
          <div className="row" style={{ margin: '8px 0' }}>
            {approvedRevs.length === 0 && <span className="muted">승인된(발행 대기) 리비전이 없습니다</span>}
            {approvedRevs.map((r) => (
              <label key={r.id} className="row" style={{ gap: 4 }}>
                <input type="checkbox" aria-label={`발행 ${r.docNo} ${r.revCode}`} checked={picked.includes(r.id)}
                  onChange={(e) => setPicked((p) => (e.target.checked ? [...p, r.id] : p.filter((x) => x !== r.id)))} />
                {r.docNo} Rev.{r.revCode}
              </label>
            ))}
          </div>
          {blockers.length > 0 && (
            <Banner tone="danger" testId="dem-fc-gate">
              시공용(FOR_CONSTRUCTION) 발행 불가 — 미결 검토의견: {blockers.map((d) => `${d.docNo} ${d.unresolvedComments}건`).join(', ')}. 의견을 종결(수용 → 종결)한 뒤 발행하세요.
            </Banner>
          )}
          {refused && <Banner tone="danger">{refused}</Banner>}
          <button className="primary" disabled={picked.length === 0 || blockers.length > 0 || issue.isPending} onClick={() => issue.mutate()} data-testid="dem-issue">
            {PURPOSE_LABEL[purpose]} 발행
          </button>
          <span className="muted" style={{ marginLeft: 8 }}>패키지 미결 의견 합계 {unresolvedTotal}건</span>
        </Card>
      )}
      <Card title="트랜스미털 이력">
        <table className="simple" data-testid="dem-transmittals">
          <thead><tr><th>번호</th><th>목적</th><th>수신</th><th>발행</th><th>도서</th></tr></thead>
          <tbody>
            {(trs.data ?? []).map((t) => (
              <tr key={t.id}>
                <td className="mono">{t.transmittalNo}</td>
                <td>{PURPOSE_LABEL[t.purpose]}</td>
                <td>{t.recipientName}</td>
                <td>{fmtDateTime(t.issuedAt)}</td>
                <td>{t.revisions.map((r) => `${r.docNo} Rev.${r.revCode}(${r.issuedRev})`).join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {rev && <CommentsPanel key={rev.id} rev={rev} onChanged={refresh} />}
    </>
  );
}

// ================================================================== 페이지
export function DemPage() {
  const projects = useQuery({ queryKey: ['dem', 'projects'], queryFn: demApi.projects });
  const core = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects(), meta: { silent: true } });
  const names = useMemo(() => new Map((core.data ?? []).map((p) => [p.id, `${p.projectNo} ${p.name}`])), [core.data]);
  const [pid, setPid] = useState<string>();
  const [wpId, setWpId] = useState<string>();
  const [tab, setTab] = useState<'sup' | 'logs' | 'docs'>('sup');
  if (projects.error) return <><h1>해체 · 감리</h1><ErrorBox error={projects.error} /></>;
  if (!projects.data) return <Loading />;
  const project = projects.data.find((p) => p.projectId === pid) ?? projects.data[0];
  if (!project) return <><h1>해체 · 감리</h1><div className="empty">DEM 프로젝트가 없습니다</div></>;
  const wp = project.workPackages.find((w) => w.id === wpId) ?? project.workPackages[0];
  return (
    <>
      <h1>해체 · 감리</h1>
      <Card
        title={<>{names.get(project.projectId) ?? project.projectId.slice(0, 8)} <Badge tone="info">{PROJECT_STATUS_LABEL[project.status] ?? project.status}</Badge></>}
        actions={
          <select aria-label="DEM 프로젝트" value={project.projectId} onChange={(e) => { setPid(e.target.value); setWpId(undefined); }}>
            {projects.data.map((p) => <option key={p.projectId} value={p.projectId}>{names.get(p.projectId) ?? p.projectId}</option>)}
          </select>
        }
      >
        <div className="row" data-testid="dem-wps">
          {project.workPackages.map((w) => (
            <button key={w.id} className={w.id === wp?.id ? 'primary' : ''} onClick={() => setWpId(w.id)}>
              {PACKAGE_TYPE_LABEL[w.packageType] ?? w.packageType}
              {w.supervisorGap && ' ⚠'}
            </button>
          ))}
        </div>
      </Card>
      {wp && (
        <Card>
          <div className="tabs" role="tablist">
            <button role="tab" className={tab === 'sup' ? 'active' : ''} onClick={() => setTab('sup')}>감리자</button>
            <button role="tab" className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>감리일지</button>
            <button role="tab" className={tab === 'docs' ? 'active' : ''} onClick={() => setTab('docs')}>도서 · 트랜스미털 · 검토의견</button>
          </div>
          {tab === 'sup' && <SupervisorsTab key={wp.id} wp={wp} />}
          {tab === 'logs' && <LogsTab key={wp.id} wp={wp} />}
          {tab === 'docs' && <DrawingsTab key={wp.id} wp={wp} />}
        </Card>
      )}
    </>
  );
}
