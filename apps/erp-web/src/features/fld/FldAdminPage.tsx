import { useMemo, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import {
  allowedResolutions, fldApi, LOCK_ISSUE_LABEL, newOpId, RESOLUTION_LABEL, shortHash, SURVEY_TYPE_LABEL, UPLOAD_STATE,
  ZONE_STATUS_LABEL, type Conflict, type Resolution, type Round,
} from '@/api/fld';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, Kpi, Modal } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { useEmployees } from '@/features/admin/common';

const fmtVal = (v: unknown) => (v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

function ResolveDialog({ c, zoneName, onClose }: { c: Conflict; zoneName: string; onClose: () => void }) {
  const qc = useQueryClient();
  const { label } = useEmployees();
  const options = allowedResolutions(c);
  const [res, setRes] = useState<Resolution>(options[0]);
  const [merged, setMerged] = useState(String(c.serverValue ?? ''));
  const [opId] = useState(newOpId); // 대화상자 1개 = 작업 1개(재시도 시 같은 opId → replayed)
  const resolve = useMutation({
    mutationFn: () => fldApi.resolve(c.id, res, opId, res === 'MERGE' ? { value: Number.isNaN(Number(merged)) ? merged : Number(merged) } : undefined),
    onSuccess: (r) => {
      toast.success(`충돌 해결 — ${RESOLUTION_LABEL[r.resolution ?? res]} (${c.field} = ${fmtVal(r.resolvedValue ?? c.serverValue)})`);
      qc.invalidateQueries({ queryKey: ['fld'] });
      onClose();
    },
  });
  return (
    <Modal
      title="동기화 충돌 해결"
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>취소</button>
          <button className="primary" disabled={resolve.isPending || (res === 'MERGE' && !merged.trim())} onClick={() => resolve.mutate()} data-testid="fld-resolve-confirm">
            {RESOLUTION_LABEL[res]}로 해결
          </button>
        </>
      }
    >
      <p>
        구역 <strong>{zoneName}</strong> · 항목 <span className="mono">{c.field}</span> · 방식 {c.strategy === 'MANUAL' ? '수동 판단 필요' : c.strategy}
      </p>
      <table className="simple">
        <thead><tr><th /><th>값</th><th>기기 · 작성자</th><th>HLC</th></tr></thead>
        <tbody>
          <tr><th>서버(먼저 반영)</th><td data-testid="fld-server-value">{fmtVal(c.serverValue)}</td><td className="mono">{c.serverDeviceId.slice(-4)}</td><td className="mono" style={{ fontSize: 11 }}>{c.serverHlc}</td></tr>
          <tr><th>기기(충돌 op)</th><td data-testid="fld-client-value">{fmtVal(c.localValue)}</td><td><span className="mono">{c.localDeviceId.slice(-4)}</span> · {label(c.localActorId)}</td><td className="mono" style={{ fontSize: 11 }}>{c.localHlc}</td></tr>
        </tbody>
      </table>
      <div role="radiogroup" aria-label="해결 방식" style={{ marginTop: 12 }}>
        {options.map((o) => (
          <label key={o} className="row" style={{ gap: 6 }}>
            <input type="radio" name="resolution" checked={res === o} onChange={() => setRes(o)} aria-label={RESOLUTION_LABEL[o]} />
            {RESOLUTION_LABEL[o]}
            <span className="muted">{o === 'KEEP_SERVER' ? '— 기기 값 버림(이벤트 없음)' : o === 'KEEP_CLIENT' ? '— 기기 값을 새 이벤트로 반영' : '— 확인한 값을 새 이벤트로 반영'}</span>
          </label>
        ))}
      </div>
      {res === 'MERGE' && (
        <Field label="최종 값">
          <input aria-label="병합 값" value={merged} onChange={(e) => setMerged(e.target.value)} />
        </Field>
      )}
    </Modal>
  );
}

function RoundView({ round }: { round: Round }) {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const { label } = useEmployees();
  const conflicts = useQuery({ queryKey: ['fld', 'conflicts', round.id], queryFn: () => fldApi.conflicts(round.id) });
  const photos = useQuery({ queryKey: ['fld', 'photos', round.id], queryFn: () => fldApi.photos(round.id) });
  const checks = useQueries({
    queries: round.zones.map((z) => ({
      queryKey: ['fld', 'lock-check', z.id], queryFn: () => fldApi.lockCheck(z.id), enabled: z.status !== 'LOCKED',
      meta: { silent: true },
    })),
  });
  const [resolving, setResolving] = useState<Conflict>();
  const [verified, setVerified] = useState<Record<string, string>>({});
  const zoneName = (id: string) => round.zones.find((z) => z.id === id)?.zoneName ?? id.slice(0, 8);
  const lock = useMutation({
    mutationFn: (zoneId: string) => fldApi.lock(zoneId),
    onSuccess: (z) => { toast.success(`구역 ${z.zoneName} 잠금`); qc.invalidateQueries({ queryKey: ['fld'] }); },
  });
  const verify = useMutation({
    mutationFn: (photoId: string) => fldApi.verify(photoId),
    onSuccess: (v) => setVerified((m) => ({ ...m, [v.photoId]: v.result })),
  });
  const ps = photos.data ?? [];
  return (
    <>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="구역" value={`${round.zones.length} (잠금 ${round.zones.filter((z) => z.status === 'LOCKED').length})`} />
        <Kpi label="미해결 충돌" value={`${conflicts.data?.length ?? 0}건`} tone={conflicts.data?.length ? 'danger' : undefined} />
        <Kpi label="사진 검증 완료" value={`${ps.filter((p) => p.uploadState === 'VERIFIED').length}/${ps.length}`} />
        <Kpi label="재업로드 필요" value={`${ps.filter((p) => p.uploadState === 'REJECTED').length}장`} tone={ps.some((p) => p.uploadState === 'REJECTED') ? 'danger' : undefined} />
      </div>
      <Card title="구역 잠금 상태">
        <table className="simple" data-testid="fld-zones">
          <thead><tr><th>구역</th><th>담당</th><th>결함</th><th>상태</th><th>잠금을 막는 사유</th><th /></tr></thead>
          <tbody>
            {round.zones.map((z, i) => {
              const ck = checks[i]?.data;
              return (
                <tr key={z.id}>
                  <td><strong>{z.zoneName}</strong></td>
                  <td>{label(z.assigneeId)}</td>
                  <td className="num">{z.defectCount}</td>
                  <td><Badge tone={z.status === 'LOCKED' ? 'info' : z.status === 'IN_PROGRESS' ? 'warn' : undefined}>{z.status === 'LOCKED' ? '🔒 ' : ''}{ZONE_STATUS_LABEL[z.status]}</Badge></td>
                  <td>
                    {z.status === 'LOCKED' ? <span className="muted">잠김 — 수정은 PM 사유 필요</span>
                      : checks[i]?.error ? <span className="muted">확인 권한 없음</span>
                      : !ck ? '…'
                      : ck.ready ? <Badge tone="ok">잠금 가능</Badge>
                      : (
                        <ul style={{ margin: 0, paddingLeft: 16 }}>
                          {ck.issues.map((x, k) => (
                            <li key={k}><Badge tone="danger">{LOCK_ISSUE_LABEL[x.kind]}</Badge> {x.defectCode} — {x.message}</li>
                          ))}
                        </ul>
                      )}
                  </td>
                  <td>
                    {z.status !== 'LOCKED' && hasRole('PM') && (
                      <button disabled={!ck?.ready || lock.isPending} onClick={() => lock.mutate(z.id)} aria-label={`잠금 ${z.zoneName}`}>잠금</button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      <Card title="동기화 충돌(미해결)">
        {conflicts.error && <ErrorBox error={conflicts.error} />}
        <p className="muted">같은 결함의 같은 측정값을 두 기기가 서로 모르고 다르게 올리면 서버가 자동 병합하지 않고 충돌로 남깁니다. 해결하기 전에는 그 구역을 잠글 수 없습니다.</p>
        <table className="simple" data-testid="fld-conflicts">
          <thead><tr><th>구역</th><th>항목</th><th>서버 값</th><th>기기 값</th><th>기기 작성자</th><th /></tr></thead>
          <tbody>
            {(conflicts.data ?? []).map((c) => (
              <tr key={c.id}>
                <td>{zoneName(c.zoneId)}</td>
                <td className="mono">{c.field}</td>
                <td>{fmtVal(c.serverValue)}</td>
                <td>{fmtVal(c.localValue)}</td>
                <td>{label(c.localActorId)}</td>
                <td>{hasRole('PM', 'ENG') && <button onClick={() => setResolving(c)} aria-label={`해결 ${zoneName(c.zoneId)} ${c.field}`}>해결…</button>}</td>
              </tr>
            ))}
            {conflicts.data?.length === 0 && <tr><td colSpan={6} className="muted">미해결 충돌 없음</td></tr>}
          </tbody>
        </table>
      </Card>
      <Card title="사진 검증 상태">
        {photos.error && <ErrorBox error={photos.error} />}
        <table className="simple" data-testid="fld-photos">
          <thead><tr><th>결함</th><th>종류</th><th>상태</th><th>기기 해시</th><th>서버 해시</th><th>사유</th><th>검증</th></tr></thead>
          <tbody>
            {ps.map((p) => (
              <tr key={p.photoId}>
                <td>{p.defectCode ?? '—'}</td>
                <td>{p.kind}</td>
                <td><Badge tone={UPLOAD_STATE[p.uploadState].tone}>{UPLOAD_STATE[p.uploadState].label}</Badge></td>
                <td className="mono" title={p.deviceSha256}>{shortHash(p.deviceSha256)}</td>
                <td className="mono" title={p.serverSha256 ?? ''}>{shortHash(p.serverSha256)}</td>
                <td>{p.rejectReason === 'HASH_MISMATCH' ? '해시 불일치(전송 중 손상)' : p.rejectReason ?? ''}{p.rejectCount > 1 ? ` ×${p.rejectCount}` : ''}</td>
                <td>
                  {verified[p.photoId] ? <Badge tone={verified[p.photoId] === 'MATCH' ? 'ok' : 'danger'}>{verified[p.photoId]}</Badge>
                    : hasRole('PM', 'QA', 'AUDITOR') && <button onClick={() => verify.mutate(p.photoId)} aria-label={`검증 ${p.defectCode}`}>다시 계산</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted">서버는 업로드가 끝나면 SHA-256 을 다시 계산해 기기 해시와 비교하고, 같으면 위변조 방지 저장소(WORM)에 보관합니다. 「다시 계산」은 보관본을 지금 다시 읽어 확인합니다.</p>
      </Card>
      {resolving && <ResolveDialog c={resolving} zoneName={zoneName(resolving.zoneId)} onClose={() => setResolving(undefined)} />}
    </>
  );
}

/** G1 › 현장조사(관리자 데스크톱 보기) — 회차 · 구역 잠금 · 충돌 해결 · 사진 검증 */
export function FldAdminPage() {
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects() });
  const [pid, setPid] = useState('');
  const projectId = pid || projects.data?.[0]?.id || '';
  const rounds = useQuery({ queryKey: ['fld', 'rounds', projectId], queryFn: () => fldApi.rounds(projectId), enabled: !!projectId });
  const [rid, setRid] = useState('');
  const round = useMemo(() => (rounds.data ?? []).find((r) => r.id === rid) ?? rounds.data?.[0], [rounds.data, rid]);
  return (
    <>
      <h1>현장조사 (관리)</h1>
      <Banner tone="info">현장 입력은 모바일 앱이 합니다. 이 화면은 회차 진행 · 잠금 · 충돌 · 사진 검증을 관리합니다.</Banner>
      <Card
        title="회차"
        actions={
          <>
            <select aria-label="프로젝트" value={projectId} onChange={(e) => { setPid(e.target.value); setRid(''); }}>
              {(projects.data ?? []).map((p) => <option key={p.id} value={p.id}>{p.projectNo} {p.name}</option>)}
            </select>
            <select aria-label="회차" value={round?.id ?? ''} onChange={(e) => setRid(e.target.value)}>
              {(rounds.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.roundNo}회차 · {SURVEY_TYPE_LABEL[r.surveyType] ?? r.surveyType}</option>)}
            </select>
          </>
        }
      >
        {(projects.error || rounds.error) && <ErrorBox error={projects.error ?? rounds.error} />}
        {rounds.data?.length === 0 && <div className="empty">이 프로젝트에 조사 회차가 없습니다</div>}
        {round && (
          <p data-testid="fld-round">
            <strong>{round.roundNo}회차</strong> {SURVEY_TYPE_LABEL[round.surveyType] ?? round.surveyType} ·{' '}
            <Badge tone={round.status === 'COMPLETED' ? 'ok' : 'warn'}>{round.status === 'COMPLETED' ? '완료' : '진행 중'}</Badge>{' '}
            {round.completedAt && `완료 ${fmtDateTime(round.completedAt)}`} · 이벤트 seq {round.lastSeq}
            {round.evidenceMode && <> · <Badge tone="info">증거 모드</Badge></>}
          </p>
        )}
      </Card>
      {round && <RoundView key={round.id} round={round} />}
    </>
  );
}
