import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  aipApi, JOB_STATUS, NEXT_STAGES, promotionNeeds, REJECT_REASONS, SERVICE_LABEL, STAGE_LABEL, SUGG_STATUS,
  type AuditRow, type Job, type JobStatus, type ModelRow, type ModelStage, type Suggestion,
} from '@/api/aip';
import { coreApi } from '@/api/core';
import { useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, fmtNum, Kpi, Modal } from '@/components/ui';

type Tab = 'jobs' | 'models' | 'suggestions' | 'audit';
const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
const who = (id?: string | null) => (id ? devName.get(id) ?? id.slice(0, 8) : '—');
const ALIAS_TONE: Record<string, 'ok' | 'warn' | 'info'> = { production: 'ok', canary: 'warn', shadow: 'info' };

function JobChip({ s }: { s: JobStatus }) {
  return <Badge tone={JOB_STATUS[s].tone}>{JOB_STATUS[s].label}</Badge>;
}

function JobDetail({ job, onClose }: { job: Job; onClose: () => void }) {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const events = useQuery({ queryKey: ['aip', 'events', job.id], queryFn: () => aipApi.events(job.id) });
  const cancel = useMutation({
    mutationFn: () => aipApi.cancel(job.id),
    onSuccess: (j) => {
      toast.success(`Job 취소 — ${JOB_STATUS[j.status].label}`);
      qc.invalidateQueries({ queryKey: ['aip'] });
      onClose();
    },
  });
  return (
    <Modal title={`Job ${job.id.slice(0, 8)} — ${SERVICE_LABEL[job.serviceCode] ?? job.serviceCode}`} onClose={onClose}
      footer={
        <>
          {(job.status === 'QUEUED' || job.status === 'RUNNING') && hasRole('PM', 'ENG') && (
            <button className="danger" onClick={() => cancel.mutate()} disabled={cancel.isPending}>취소</button>
          )}
          <button onClick={onClose}>닫기</button>
        </>
      }>
      <div className="kpis">
        <Kpi label="상태" value={<JobChip s={job.status} />} />
        <Kpi label="모드 · 우선순위" value={`${job.mode} · P${job.priority}`} />
        <Kpi label="시도" value={`${job.attempts}/${job.maxAttempts}`} />
        <Kpi label="별칭" value={job.routeAlias ?? '—'} />
      </div>
      <p className="muted">입력 해시(정렬된 첨부 SHA-256 + 정규 params) <span className="hash">{job.inputSha256}</span></p>
      {job.error && <Banner tone="danger">오류 {job.error.code ?? ''} {job.error.message ?? ''}</Banner>}
      <h3>상태 이력 (job_event, 추가만)</h3>
      <table className="simple">
        <thead><tr><th>시각</th><th>종류</th><th>내용</th></tr></thead>
        <tbody>
          {(events.data ?? []).map((e) => (
            <tr key={e.id}><td>{fmtDateTime(e.at)}</td><td>{e.kind}</td><td className="mono" style={{ fontSize: 11 }}>{JSON.stringify(e.payload)}</td></tr>
          ))}
        </tbody>
      </table>
    </Modal>
  );
}

function JobsTab() {
  const [status, setStatus] = useState<JobStatus | ''>('');
  const [open, setOpen] = useState<Job>();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects(), meta: { silent: true } });
  const pName = useMemo(() => new Map((projects.data ?? []).map((p) => [p.id, p.projectNo])), [projects.data]);
  const jobs = useQuery({ queryKey: ['aip', 'jobs'], queryFn: () => aipApi.jobs(), refetchInterval: 5000 });
  const all = jobs.data ?? [];
  const rows = status ? all.filter((j) => j.status === status) : all;
  const cols = useMemo<ColDef<Job>[]>(
    () => [
      { field: 'queuedAt', headerName: '요청', width: 160, valueFormatter: (p) => fmtDateTime(p.value) },
      { field: 'serviceCode', headerName: '서비스', width: 150, valueFormatter: (p) => `${SERVICE_LABEL[p.value] ?? p.value}` },
      { field: 'mode', headerName: '모드', width: 80 },
      { field: 'priority', headerName: 'P', width: 60 },
      {
        field: 'status', headerName: '상태', width: 150, valueFormatter: (p) => JOB_STATUS[p.value as JobStatus]?.label,
        cellRenderer: (p: { data: Job }) => <JobChip s={p.data.status} />,
      },
      { headerName: '시도', width: 80, valueGetter: (p) => `${p.data!.attempts}/${p.data!.maxAttempts}` },
      { headerName: '프로젝트', width: 140, valueGetter: (p) => (p.data!.projectId ? pName.get(p.data!.projectId) ?? p.data!.projectId.slice(0, 8) : '—') },
      { headerName: '요청자', width: 110, valueGetter: (p) => who(p.data!.requestedBy) },
      { field: 'routeAlias', headerName: '별칭', width: 100 },
      { headerName: '오류', flex: 1, minWidth: 140, valueGetter: (p) => p.data!.error?.code ?? '' },
    ],
    [pName],
  );
  const count = (s: JobStatus) => all.filter((j) => j.status === s).length;
  return (
    <>
      <div className="row" style={{ marginBottom: 8 }} role="group" aria-label="Job 상태 필터">
        <button className={status === '' ? 'primary' : ''} onClick={() => setStatus('')}>전체 {all.length}</button>
        {(Object.keys(JOB_STATUS) as JobStatus[]).filter((s) => count(s) > 0).map((s) => (
          <button key={s} className={status === s ? 'primary' : ''} onClick={() => setStatus(s)} aria-label={`상태 ${JOB_STATUS[s].label}`}>
            {JOB_STATUS[s].label} {count(s)}
          </button>
        ))}
      </div>
      {jobs.error && <ErrorBox error={jobs.error} />}
      <DataGrid<Job> testId="aip-jobs" rowData={rows} columnDefs={cols} height={340} getRowId={(p) => p.data.id} onRowClicked={(e) => setOpen(e.data)} />
      <p className="muted" style={{ fontSize: 12 }}>
        상태: 대기 → 실행 중 → 성공 / 실패(지수 백오프 재시도) → 중단(DEAD, 시도 소진 · 마감 초과). 행을 누르면 상태 이력. PROJECT 범위 역할은 투입 프로젝트 Job 만 보입니다.
      </p>
      {open && <JobDetail job={open} onClose={() => setOpen(undefined)} />}
    </>
  );
}

function PromoteDialog({ m, onClose }: { m: ModelRow; onClose: () => void }) {
  const qc = useQueryClient();
  const targets = NEXT_STAGES[m.stage];
  const [to, setTo] = useState<ModelStage | ''>(targets[0] ?? '');
  const [svc, setSvc] = useState(m.deployments?.[0]?.serviceCode ?? 'CRACK_DETECT');
  const [pct, setPct] = useState(10);
  const promote = useMutation({
    mutationFn: () => aipApi.promote(m.id, to as ModelStage, svc, to === 'CANARY' ? pct : undefined),
    onSuccess: (r) => {
      toast.success(`승격 — ${m.name}@${m.version} → ${STAGE_LABEL[r.stage]}${r.alias ? ` (${r.alias} ${r.trafficPct}%)` : ''}`);
      qc.invalidateQueries({ queryKey: ['aip', 'models'] });
      onClose();
    },
  });
  return (
    <Modal title={`단계 전이 — ${m.name}@${m.version}`} onClose={onClose}
      footer={<><button onClick={onClose}>취소</button><button className="primary" disabled={!to || promote.isPending} onClick={() => promote.mutate()}>전이</button></>}>
      <div className="form">
        <Field label="대상 단계">
          <select value={to} onChange={(e) => setTo(e.target.value as ModelStage)} aria-label="대상 단계">
            {targets.map((t) => <option key={t} value={t}>{STAGE_LABEL[t]}</option>)}
          </select>
        </Field>
        <Field label="서비스(별칭 전환)">
          <select value={svc} onChange={(e) => setSvc(e.target.value)} aria-label="서비스">
            {Object.keys(SERVICE_LABEL).map((s) => <option key={s} value={s}>{SERVICE_LABEL[s]}</option>)}
          </select>
        </Field>
        {to === 'CANARY' && (
          <Field label="canary 트래픽 %">
            <input type="number" min={1} max={50} value={pct} onChange={(e) => setPct(Number(e.target.value))} aria-label="canary 비율" />
          </Field>
        )}
      </div>
      <p className="muted" style={{ fontSize: 12 }}>SHADOW 진입은 QA 품질 승인, PRODUCTION 진입은 EXEC 최종 승인이 먼저 있어야 합니다(서버 AIP-409).</p>
    </Modal>
  );
}

function ModelsTab() {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [promoting, setPromoting] = useState<ModelRow>();
  const models = useQuery({ queryKey: ['aip', 'models'], queryFn: aipApi.models });
  const approve = useMutation({
    mutationFn: ({ m, role }: { m: ModelRow; role: 'QA' | 'EXEC' }) => aipApi.approve(m.id, role),
    onSuccess: (_r, v) => {
      toast.success(`${v.role} 승인 기록 — ${v.m.name}@${v.m.version}`);
      qc.invalidateQueries({ queryKey: ['aip', 'models'] });
    },
  });
  const rows = models.data ?? [];
  const requests = rows.flatMap((m) => promotionNeeds(m).map((n) => ({ m, ...n })));
  return (
    <>
      {models.error && <ErrorBox error={models.error} />}
      <table className="simple" data-testid="aip-models">
        <thead><tr><th>모델</th><th>기반</th><th>단계</th><th>별칭(서비스 · 트래픽)</th><th>QA 승인</th><th>EXEC 승인</th><th /></tr></thead>
        <tbody>
          {rows.map((m) => (
            <tr key={m.id}>
              <td className="mono">{m.name}@{m.version}</td>
              <td>{m.base_model ?? '—'}</td>
              <td><Badge tone={m.stage === 'PRODUCTION' ? 'ok' : m.stage === 'CANARY' || m.stage === 'SHADOW' ? 'warn' : undefined}>{STAGE_LABEL[m.stage]}</Badge></td>
              <td>
                {(m.deployments ?? []).map((d) => (
                  <Badge key={`${d.serviceCode}-${d.alias}`} tone={ALIAS_TONE[d.alias]}>{`${d.alias} · ${SERVICE_LABEL[d.serviceCode] ?? d.serviceCode} ${d.trafficPct}%`}</Badge>
                ))}
              </td>
              <td>{who(m.approved_by)}</td>
              <td>{who(m.exec_approved_by)}</td>
              <td>
                <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                  {hasRole('QA') && !m.approved_by && <button onClick={() => approve.mutate({ m, role: 'QA' })}>QA 승인</button>}
                  {hasRole('EXEC') && !m.exec_approved_by && <button onClick={() => approve.mutate({ m, role: 'EXEC' })}>EXEC 승인</button>}
                  {hasRole('SYSADMIN') && NEXT_STAGES[m.stage].length > 0 && <button onClick={() => setPromoting(m)}>단계 전이…</button>}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>승격 요청 (다음 단계 · 필요한 승인)</h3>
      <table className="simple" data-testid="aip-promotions">
        <thead><tr><th>모델</th><th>현재</th><th>다음</th><th>필요 승인</th><th>충족</th></tr></thead>
        <tbody>
          {requests.filter((r) => r.to !== 'ARCHIVED').map((r) => (
            <tr key={`${r.m.id}-${r.to}`}>
              <td className="mono">{r.m.name}@{r.m.version}</td><td>{STAGE_LABEL[r.m.stage]}</td><td>{STAGE_LABEL[r.to]}</td>
              <td>{r.approver ?? '없음'}</td><td><Badge tone={r.approved ? 'ok' : 'warn'}>{r.approved ? '충족' : '대기'}</Badge></td>
            </tr>
          ))}
          {requests.length === 0 && (
            <tr><td colSpan={5} className="muted">대기 중인 승격 요청이 없습니다 — 레지스트리의 모델이 모두 Production 입니다(새 버전은 학습 · 평가 파이프라인이 등록).</td></tr>
          )}
        </tbody>
      </table>
      <p className="muted" style={{ fontSize: 12 }}>
        단계: 학습 → 평가 → Shadow(QA 승인, 비교만) → Canary(트래픽 일부, 사진 해시 버킷 고정) → Production(EXEC 승인). 전이는 시스템 관리자(SYSADMIN)만.
      </p>
      {promoting && <PromoteDialog m={promoting} onClose={() => setPromoting(undefined)} />}
    </>
  );
}

function RejectSuggestion({ s, onClose }: { s: Suggestion; onClose: () => void }) {
  const qc = useQueryClient();
  const [reason, setReason] = useState('FALSE_POSITIVE');
  const decide = useMutation({
    mutationFn: () => aipApi.decide(s.id, 'REJECTED', reason),
    onSuccess: () => {
      toast.success('제안 거절');
      qc.invalidateQueries({ queryKey: ['aip', 'suggestions'] });
      onClose();
    },
  });
  return (
    <Modal title="AI 제안 거절" onClose={onClose}
      footer={<><button onClick={onClose}>취소</button><button className="danger" onClick={() => decide.mutate()} disabled={decide.isPending}>거절</button></>}>
      <Field label="거절 사유">
        <select value={reason} onChange={(e) => setReason(e.target.value)} aria-label="거절 사유">
          {Object.entries(REJECT_REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
    </Modal>
  );
}

function SuggestionsTab() {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [view, setView] = useState<'pending' | 'decided'>('pending');
  const [rejecting, setRejecting] = useState<Suggestion>();
  const list = useQuery({ queryKey: ['aip', 'suggestions'], queryFn: () => aipApi.suggestions() });
  const accept = useMutation({
    mutationFn: (s: Suggestion) => aipApi.decide(s.id, 'ACCEPTED'),
    onSuccess: () => {
      toast.success('제안 수락 — 피드백 기록(학습 반영은 데이터셋 커밋 때만)');
      qc.invalidateQueries({ queryKey: ['aip', 'suggestions'] });
    },
  });
  const all = list.data ?? [];
  const pending = all.filter((s) => s.status === 'SUGGESTED');
  const decided = all.filter((s) => s.status !== 'SUGGESTED');
  const rows = view === 'pending' ? pending : decided;
  const canDecide = hasRole('PM', 'ENG');
  const cols = useMemo<ColDef<Suggestion>[]>(
    () => [
      { field: 'created_at', headerName: '제안', width: 160, valueFormatter: (p) => fmtDateTime(p.value) },
      { field: 'defect_type', headerName: '유형', width: 90 },
      { field: 'crack_pattern', headerName: '패턴', width: 110 },
      { field: 'width_max_mm', headerName: '최대폭(mm)', width: 110, valueFormatter: (p) => (p.value == null ? '스케일 없음' : fmtNum(p.value, 3)) },
      { field: 'width_uncert_mm', headerName: '±불확도', width: 90, valueFormatter: (p) => fmtNum(p.value, 3) },
      { field: 'scale_source', headerName: '스케일', width: 90 },
      { field: 'confidence', headerName: '신뢰도', width: 90, valueFormatter: (p) => fmtNum(p.value, 2) },
      { headerName: '플래그', width: 210, valueGetter: (p) => (p.data!.flags ?? []).join(', ') },
      {
        field: 'status', headerName: '상태', width: 110, valueFormatter: (p) => SUGG_STATUS[p.value as Suggestion['status']]?.label,
        cellRenderer: (p: { data: Suggestion }) => <Badge tone={SUGG_STATUS[p.data.status].tone}>{SUGG_STATUS[p.data.status].label}</Badge>,
      },
      ...(view === 'decided'
        ? [
            { headerName: '결정자', width: 100, valueGetter: (p) => who(p.data!.decided_by) } as ColDef<Suggestion>,
            { field: 'reason_code', headerName: '사유', width: 130, valueFormatter: (p) => (p.value ? REJECT_REASONS[p.value] ?? p.value : '') } as ColDef<Suggestion>,
          ]
        : canDecide
          ? [{
              headerName: '', width: 150, sortable: false,
              cellRenderer: (p: { data: Suggestion }) => (
                <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                  <button className="primary" onClick={() => accept.mutate(p.data)} aria-label={`수락 ${p.data.id.slice(0, 8)}`}>수락</button>
                  <button className="danger" onClick={() => setRejecting(p.data)} aria-label={`거절 ${p.data.id.slice(0, 8)}`}>거절</button>
                </span>
              ),
            } as ColDef<Suggestion>]
          : []),
    ],
    [view, canDecide, accept],
  );
  return (
    <>
      <div className="tabs" role="tablist" aria-label="제안 보기">
        <button role="tab" className={view === 'pending' ? 'active' : ''} onClick={() => setView('pending')}>결정 대기 ({pending.length})</button>
        <button role="tab" className={view === 'decided' ? 'active' : ''} onClick={() => setView('decided')}>결정됨 ({decided.length})</button>
      </div>
      <Banner tone="info">AI 제안 값은 사람이 수락하기 전에는 결함 기록에 들어가지 않습니다(BR-AIP-001). 스케일(ArUco 등)이 없으면 폭 수치를 내지 않습니다(BR-AIP-002).</Banner>
      {list.error && <ErrorBox error={list.error} />}
      <DataGrid<Suggestion> testId="aip-suggestions" rowData={rows} columnDefs={cols} height={300} getRowId={(p) => p.data.id} />
      {rejecting && <RejectSuggestion s={rejecting} onClose={() => setRejecting(undefined)} />}
    </>
  );
}

function AuditTab() {
  const audit = useQuery({ queryKey: ['aip', 'audit'], queryFn: () => aipApi.audit() });
  const cols = useMemo<ColDef<AuditRow>[]>(
    () => [
      { field: 'created_at', headerName: '시각', width: 160, valueFormatter: (p) => fmtDateTime(p.value) },
      { field: 'service_code', headerName: '서비스', width: 130, valueFormatter: (p) => SERVICE_LABEL[p.value] ?? p.value },
      { headerName: '모델', width: 150, valueGetter: (p) => `${p.data!.model_name ?? '—'}@${p.data!.model_version ?? ''}` },
      { headerName: '프롬프트', width: 170, valueGetter: (p) => (p.data!.prompt_key ? `${p.data!.prompt_key}@${p.data!.prompt_version}` : '—') },
      { field: 'input_sha256', headerName: '입력 SHA-256', flex: 1, minWidth: 200, cellClass: 'hash' },
      { field: 'output_sha256', headerName: '출력 SHA-256', flex: 1, minWidth: 200, cellClass: 'hash' },
      { field: 'latency_ms', headerName: '지연(ms)', width: 95, type: 'numericColumn' },
      { field: 'partition_name', headerName: '파티션', width: 170 },
    ],
    [],
  );
  return (
    <>
      <Banner tone="info">추론 감사는 추가만 되는 월 파티션 표입니다. 화면에는 해시 · 모델 · 지연만 보이고 입력 원문 · 파라미터 · 출처 세부는 표시하지 않습니다.</Banner>
      {audit.error && <ErrorBox error={audit.error} />}
      <DataGrid<AuditRow> testId="aip-audit" rowData={audit.data ?? []} columnDefs={cols} height={320} getRowId={(p) => `${p.data.job_id}-${p.data.created_at}`} />
    </>
  );
}

/** G1 › AI Job · 모델 — Job 상태 · 모델 레지스트리(단계 · 별칭 · 승격) · 제안(결정 대기/완료) · 추론 감사(해시만) */
export function AipPage() {
  const { hasRole } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) ?? 'jobs';
  const canJobs = hasRole('EXEC', 'PM', 'ENG', 'QA');
  const tabs: { key: Tab; label: string; show: boolean }[] = [
    { key: 'jobs', label: 'Job', show: canJobs },
    { key: 'models', label: '모델 레지스트리', show: canJobs },
    { key: 'suggestions', label: 'AI 제안', show: hasRole('PM', 'ENG', 'QA', 'SYSADMIN') },
    { key: 'audit', label: '추론 감사', show: hasRole('AUDITOR') },
  ];
  const visible = tabs.filter((t) => t.show);
  const active = visible.find((t) => t.key === tab)?.key ?? visible[0]?.key;
  return (
    <>
      <h1>AI Job · 모델</h1>
      {!visible.length ? (
        <div className="empty">AI 플랫폼 화면은 EXEC · PM · ENG · QA · AUDITOR 역할이 사용합니다.</div>
      ) : (
        <Card>
          <div className="tabs" role="tablist">
            {visible.map((t) => (
              <button key={t.key} role="tab" className={active === t.key ? 'active' : ''} onClick={() => setParams({ tab: t.key })}>
                {t.label}
              </button>
            ))}
          </div>
          {active === 'jobs' && <JobsTab />}
          {active === 'models' && <ModelsTab />}
          {active === 'suggestions' && <SuggestionsTab />}
          {active === 'audit' && <AuditTab />}
        </Card>
      )}
    </>
  );
}
