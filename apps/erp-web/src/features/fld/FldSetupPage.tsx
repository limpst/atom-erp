import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import { facApi, flattenTree } from '@/api/fac';
import { fldApi, fldSetupApi, SURVEY_TYPE_LABEL, ZONE_STATUS_LABEL, type Round } from '@/api/fld';
import { newIdempotencyKey, ProblemError } from '@/api/http';
import { describeError, toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { todayIso, useEmployees } from '@/features/admin/common';

/** 서버 4xx(범위 403 · 중복 409 · 검증 422)는 화면 안에, 그 밖은 토스트 */
function useInlineRefusal() {
  const [refused, setRefused] = useState<string>();
  const onError = (e: unknown) => {
    if (e instanceof ProblemError && e.status >= 400 && e.status < 500) setRefused(`${describeError(e).title} — ${e.problem.detail ?? ''}`);
    else toastError(e);
  };
  return { refused, setRefused, onError };
}

function NewRound({ projectId, onCreated }: { projectId: string; onCreated: (r: Round) => void }) {
  const qc = useQueryClient();
  const buildings = useQuery({ queryKey: ['fac', 'buildings'], queryFn: () => facApi.list(), meta: { silent: true } });
  const [buildingId, setBuildingId] = useState('');
  const [surveyType, setSurveyType] = useState('PRECISE_INSPECTION');
  const [evidence, setEvidence] = useState(false);
  const [startedOn, setStartedOn] = useState(todayIso());
  const [roundNo, setRoundNo] = useState('');
  const [key, setKey] = useState(newIdempotencyKey); // 같은 입력 재시도 = 같은 키
  const { refused, setRefused, onError } = useInlineRefusal();
  const list = (buildings.data?.content ?? []).filter((b) => b.status !== 'MERGED');
  const bId = buildingId || list[0]?.id || '';
  const create = useMutation({
    mutationFn: () => fldSetupApi.createRound({
      projectId, buildingId: bId, surveyType, evidenceMode: evidence, startedOn: startedOn || null,
      roundNo: roundNo ? Number(roundNo) : null,
    }, key),
    meta: { silent: true },
    onSuccess: (r) => {
      toast.success(`${r.roundNo}회차 ${SURVEY_TYPE_LABEL[r.surveyType] ?? r.surveyType} 생성`);
      setKey(newIdempotencyKey()); setRefused(undefined); setRoundNo('');
      qc.invalidateQueries({ queryKey: ['fld'] });
      onCreated(r);
    },
    onError,
  });
  const change = <T,>(f: (v: T) => void) => (v: T) => { f(v); setKey(newIdempotencyKey()); };
  return (
    <Card title="새 조사 회차">
      {buildings.error && <ErrorBox error={buildings.error} />}
      <div className="form">
        <Field label="대상 시설물">
          <select aria-label="대상 시설물" value={bId} onChange={(e) => change(setBuildingId)(e.target.value)}>
            {list.map((b) => <option key={b.id} value={b.id}>{b.name}{b.dongName ? ` ${b.dongName}` : ''}</option>)}
          </select>
        </Field>
        <Field label="조사 종류">
          <select aria-label="조사 종류" value={surveyType} onChange={(e) => change(setSurveyType)(e.target.value)}>
            {Object.entries(SURVEY_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </Field>
        <Field label="회차 번호(비우면 자동)">
          <input aria-label="회차 번호" type="number" min={1} value={roundNo} onChange={(e) => change(setRoundNo)(e.target.value)} />
        </Field>
        <Field label="착수일"><input aria-label="착수일" type="date" value={startedOn} onChange={(e) => change(setStartedOn)(e.target.value)} /></Field>
        <label className="row" style={{ gap: 4 }}>
          <input type="checkbox" aria-label="증거 모드" checked={evidence} onChange={(e) => change(setEvidence)(e.target.checked)} />
          증거 모드(사진 해시 체인 시각 = 서버 수신 시각)
        </label>
        <button className="primary" disabled={!bId || create.isPending} onClick={() => create.mutate()} data-testid="round-create">회차 만들기</button>
      </div>
      {refused && <Banner tone="danger" testId="round-refused">{refused}</Banner>}
    </Card>
  );
}

function ZoneSetup({ round, projectId }: { round: Round; projectId: string }) {
  const qc = useQueryClient();
  const { label } = useEmployees();
  const tree = useQuery({ queryKey: ['fac', 'tree', round.buildingId], queryFn: () => facApi.tree(round.buildingId), meta: { silent: true } });
  const members = useQuery({ queryKey: ['core', 'members', projectId], queryFn: () => coreApi.members(projectId), meta: { silent: true } });
  const floors = useMemo(() => flattenTree(tree.data ?? []).filter((n) => n.node.nodeType === 'FLOOR'), [tree.data]);
  const [zoneName, setZoneName] = useState('');
  const [floorNodeId, setFloorNodeId] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const { refused, setRefused, onError } = useInlineRefusal();
  const people = (members.data ?? []).filter((m, i, a) => a.findIndex((x) => x.employeeId === m.employeeId) === i);
  const add = useMutation({
    mutationFn: () => fldSetupApi.addZone(round.id, { zoneName: zoneName.trim(), floorNodeId: floorNodeId || null, assigneeId }, key),
    meta: { silent: true },
    onSuccess: (z) => {
      toast.success(`구역 ${z.zoneName} 추가 — 담당 ${label(z.assigneeId)}`);
      setZoneName(''); setKey(newIdempotencyKey()); setRefused(undefined);
      qc.invalidateQueries({ queryKey: ['fld'] });
    },
    onError,
  });
  const completed = round.status === 'COMPLETED';
  return (
    <Card title={`${round.roundNo}회차 구역 설정 — ${SURVEY_TYPE_LABEL[round.surveyType] ?? round.surveyType}`}
      actions={<Link to="/fld">관리 화면에서 보기 →</Link>}>
      {completed && <Banner tone="warn">완료된 회차에는 구역을 추가할 수 없습니다(서버도 거부).</Banner>}
      <table className="simple" data-testid="zones">
        <thead><tr><th>구역</th><th>층</th><th>담당</th><th>상태</th><th className="num">결함</th></tr></thead>
        <tbody>
          {round.zones.map((z) => (
            <tr key={z.id}>
              <td>{z.zoneName}</td>
              <td>{floors.find((f) => f.node.id === z.floorNodeId)?.node.code ?? '—'}</td>
              <td>{label(z.assigneeId)}</td>
              <td><Badge tone={z.status === 'LOCKED' ? 'ok' : z.status === 'IN_PROGRESS' ? 'warn' : undefined}>{ZONE_STATUS_LABEL[z.status]}</Badge></td>
              <td className="num">{z.defectCount}</td>
            </tr>
          ))}
          {round.zones.length === 0 && <tr><td colSpan={5} className="muted">아직 구역이 없습니다 — 아래에서 추가하세요</td></tr>}
        </tbody>
      </table>
      {!completed && (
        <div className="form" style={{ marginTop: 12 }}>
          <Field label="구역 이름">
            <input aria-label="구역 이름" value={zoneName} maxLength={50} onChange={(e) => { setZoneName(e.target.value); setKey(newIdempotencyKey()); }} />
          </Field>
          <Field label="층(위치 노드)">
            <select aria-label="층" value={floorNodeId} onChange={(e) => { setFloorNodeId(e.target.value); setKey(newIdempotencyKey()); }}>
              <option value="">(지정 안 함)</option>
              {floors.map((f) => <option key={f.node.id} value={f.node.id}>{f.node.code}{f.node.name && f.node.name !== f.node.code ? ` ${f.node.name}` : ''}</option>)}
            </select>
          </Field>
          <Field label="담당 조사자(투입 인원)">
            <select aria-label="담당 조사자" value={assigneeId} onChange={(e) => { setAssigneeId(e.target.value); setKey(newIdempotencyKey()); }}>
              <option value="">선택</option>
              {people.map((m) => <option key={m.employeeId} value={m.employeeId}>{label(m.employeeId)} ({m.roleInProject})</option>)}
            </select>
          </Field>
          <button className="primary" disabled={!zoneName.trim() || !assigneeId || add.isPending} onClick={() => add.mutate()} data-testid="zone-add">구역 추가</button>
        </div>
      )}
      {refused && <Banner tone="danger" testId="zone-refused">{refused}</Banner>}
      <p className="muted">구역은 현장 앱의 배정 단위입니다. ENG 는 배정된 구역에만 결함을 올릴 수 있고(NOT_ASSIGNED), 구역 잠금은 관리 화면에서 합니다.</p>
    </Card>
  );
}

/** G1 › 조사 회차 · 구역 설정(데스크톱 관리, PM · ADMIN) */
export function FldSetupPage() {
  const { hasRole } = useAuth();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects() });
  const [pid, setPid] = useState('');
  const projectId = pid || projects.data?.[0]?.id || '';
  const rounds = useQuery({ queryKey: ['fld', 'rounds', projectId], queryFn: () => fldApi.rounds(projectId), enabled: !!projectId });
  const [rid, setRid] = useState('');
  const latest = [...(rounds.data ?? [])].sort((a, b) => b.roundNo - a.roundNo)[0];
  const round = (rounds.data ?? []).find((r) => r.id === rid) ?? latest;
  return (
    <>
      <h1>조사 회차 · 구역 설정</h1>
      {!hasRole('PM', 'ADMIN') && <Banner tone="warn">회차 · 구역 설정은 PM(투입 프로젝트) · ADMIN 만 할 수 있습니다 — 서버가 거부합니다.</Banner>}
      <Card title="프로젝트"
        actions={
          <select aria-label="프로젝트" value={projectId} onChange={(e) => { setPid(e.target.value); setRid(''); }}>
            {(projects.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.projectNo} {p.name}</option>)}
          </select>
        }>
        {(projects.error || rounds.error) && <ErrorBox error={projects.error ?? rounds.error} />}
        <div className="row" data-testid="round-list">
          {(rounds.data ?? []).map((r) => (
            <button key={r.id} className={r.id === round?.id ? 'primary' : ''} onClick={() => setRid(r.id)}>
              {r.roundNo}회차 · {SURVEY_TYPE_LABEL[r.surveyType] ?? r.surveyType} · 구역 {r.zones.length}
            </button>
          ))}
          {rounds.data?.length === 0 && <span className="muted">회차 없음</span>}
        </div>
      </Card>
      {projectId && <NewRound projectId={projectId} onCreated={(r) => setRid(r.id)} />}
      {round && <ZoneSetup key={round.id} round={round} projectId={projectId} />}
    </>
  );
}
