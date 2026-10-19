import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { fcdApi, type RuleSetRow } from '@/api/fcd';
import { useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { Badge, Banner, Card, ErrorBox, Field, Kpi } from '@/components/ui';

const RS_TONE: Record<string, 'ok' | 'warn' | 'danger' | 'info' | undefined> = {
  ACTIVE: 'ok', DRAFT: 'info', PENDING_APPROVAL: 'warn', RETIRED: undefined,
};
const RS_LABEL: Record<string, string> = { ACTIVE: '승인(ACTIVE)', DRAFT: '초안', PENDING_APPROVAL: '승인 대기', RETIRED: '퇴역' };
const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
/** 작성자 · 승인자: API 초안은 직원 UUID, 시드 룰셋은 'FIN-001' 같은 합성 코드 */
export const who = (v: string | null) => (v ? devName.get(v) ?? (v.length > 20 ? v.slice(0, 8) : v) : '—');

/** 4-eyes: 승인 가능한가(작성자 본인 금지) — 서버(FCD-403-002 · DB CHECK)와 같은 규칙을 화면에서 먼저 보여 준다 */
export function approvalBlock(r: Pick<RuleSetRow, 'status' | 'authored_by'>, me: string | undefined, canApprove: boolean): string | null {
  if (r.status !== 'DRAFT' && r.status !== 'PENDING_APPROVAL') return '승인할 상태가 아님';
  if (!canApprove) return '승인 권한 없음(AUDITOR · EXEC)';
  if (me && r.authored_by === me) return '작성자 본인 — 승인 불가(4-eyes)';
  return null;
}

/** 오늘 기준 적용 중인 룰셋(시행일 ≤ 오늘 < 종료일, 같은 시행일이면 상위 버전) — 서버 선택 규칙과 같은 순서 */
export function effectiveVersion(rows: RuleSetRow[], today: string): string | undefined {
  return rows
    .filter((r) => r.status === 'ACTIVE' && r.effective_from <= today && (!r.effective_to || today < r.effective_to))
    .sort((a, b) => (a.effective_from === b.effective_from
      ? b.version.localeCompare(a.version, undefined, { numeric: true })
      : b.effective_from.localeCompare(a.effective_from)))[0]?.version;
}

/** G3 › 탐지 룰셋 — 버전 목록 · YAML 초안 등록(FIN · AUDITOR) · 4-eyes 승인(AUDITOR · EXEC, 작성자 본인 금지) */
export function FcdRulesetsPage() {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const canWrite = hasRole('FIN', 'AUDITOR');
  const canApprove = hasRole('AUDITOR', 'EXEC');
  const [yaml, setYaml] = useState('');
  const list = useQuery({ queryKey: ['fcd', 'rulesets'], queryFn: fcdApi.ruleSets });
  const rows = useMemo(() => [...(list.data ?? [])].reverse(), [list.data]);
  const today = new Date().toISOString().slice(0, 10);
  const current = effectiveVersion(list.data ?? [], today);
  const draft = useMutation({
    mutationFn: () => fcdApi.draftRuleSet(yaml),
    onSuccess: (r) => {
      toast.success(`초안 등록 — ${r.version} (규칙 ${r.rules}개)`);
      setYaml('');
      qc.invalidateQueries({ queryKey: ['fcd', 'rulesets'] });
    },
  });
  const approve = useMutation({
    mutationFn: (r: RuleSetRow) => fcdApi.approveRuleSet(r.version, r.name),
    onSuccess: (r) => {
      toast.success(`승인 — ${r.version} ${r.status}`);
      qc.invalidateQueries({ queryKey: ['fcd', 'rulesets'] });
    },
  });

  return (
    <>
      <h1>부정사용 탐지 룰셋</h1>
      <Banner tone="info">
        룰셋은 YAML 로 관리하고 4-eyes 로 승인합니다: 작성(FIN · AUDITOR) → 다른 사람이 승인(AUDITOR · EXEC). 작성자 본인은 승인할 수 없습니다(서버
        FCD-403-002 · DB CHECK). 시행일이 오늘 이후인 버전은 승인돼도 그날부터 적용됩니다.
      </Banner>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="오늘 적용 버전" value={<span className="mono" data-testid="rs-current">{current ?? '—'}</span>} />
        <Kpi label="승인 대기" value={`${rows.filter((r) => r.status === 'DRAFT' || r.status === 'PENDING_APPROVAL').length}건`} />
        <Kpi label="전체 버전" value={`${rows.length}개`} />
      </div>
      {canWrite && (
        <Card title="새 버전 초안 등록 (YAML)">
          <div className="row" style={{ marginBottom: 8 }}>
            <label className="btn">
              YAML 파일 열기
              <input
                type="file" accept=".yaml,.yml,text/yaml" style={{ display: 'none' }} aria-label="룰셋 YAML 파일"
                onChange={async (e) => {
                  const f = e.target.files?.[0];
                  if (f) setYaml(await f.text());
                  e.target.value = '';
                }}
              />
            </label>
            <span className="muted" style={{ fontSize: 12 }}>서버가 YAML 을 검증합니다(근거 법령 · 가중치 · 표현식 화이트리스트, 실패 시 FCD-422-004).</span>
          </div>
          <Field label="룰셋 YAML">
            <textarea value={yaml} onChange={(e) => setYaml(e.target.value)} aria-label="룰셋 YAML" style={{ minHeight: 140, fontFamily: 'ui-monospace, monospace', fontSize: 12 }} />
          </Field>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="primary" disabled={!yaml.trim() || draft.isPending} onClick={() => draft.mutate()} data-testid="rs-draft">
              초안 등록
            </button>
            {yaml && <span className="muted">{yaml.split('\n').length}줄</span>}
          </div>
        </Card>
      )}
      <Card title="버전">
        {list.error && <ErrorBox error={list.error} />}
        <table className="simple" data-testid="rs-list">
          <thead>
            <tr><th>버전</th><th>상태</th><th>시행</th><th>작성</th><th>승인</th><th>원천</th><th>SHA-256</th><th>4-eyes 승인</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const block = approvalBlock(r, session?.actorId, canApprove);
              const pending = r.status === 'DRAFT' || r.status === 'PENDING_APPROVAL';
              return (
                <tr key={`${r.name}@${r.version}`} data-testid={`rs-${r.version}`}>
                  <td className="mono">{r.version}{r.version === current && <> <Badge tone="ok">적용 중</Badge></>}</td>
                  <td><Badge tone={RS_TONE[r.status]}>{RS_LABEL[r.status] ?? r.status}</Badge></td>
                  <td>{r.effective_from}{r.effective_to ? ` ~ ${r.effective_to}` : ' ~'}</td>
                  <td>{who(r.authored_by)}</td>
                  <td>{who(r.approved_by)}</td>
                  <td>{r.source}</td>
                  <td className="hash" title={r.sha256}>{r.sha256.slice(0, 12)}…</td>
                  <td>
                    {pending && (
                      <div className="row" style={{ gap: 6 }}>
                        <button
                          className="primary"
                          disabled={!!block || approve.isPending}
                          title={block ?? '승인'}
                          onClick={() => approve.mutate(r)}
                          aria-label={`승인 ${r.version}`}
                        >
                          승인
                        </button>
                        {block && <span className="muted" style={{ fontSize: 12 }} data-testid={`rs-block-${r.version}`}>{block}</span>}
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
    </>
  );
}
