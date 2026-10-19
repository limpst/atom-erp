import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  qarApi, qarLookup, REPORT_TYPE_LABEL, REV_STEPS, REVIEW_STEPS, rptApi, syntheticFreeze, type Checklist, type FreezeResult,
  type Review, type SafetyGrade,
} from '@/api/rpt';
import { ProblemError } from '@/api/http';
import { useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { Badge, Card, ErrorBox, fmtDateTime, fmtNum, Kpi, Loading, Steps } from '@/components/ui';
import { REV_STATUS_LABEL } from './ReportsPage';

const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
const who = (id?: string | null) => (id ? devName.get(id) ?? id.slice(0, 8) : '—');

/** 체크리스트의 아직 답하지 않은 항목(missingSelf / missingQa = itemNo)을 모두 PASS 로 */
async function answerMissing(c: Checklist, stage: 'SELF' | 'QA') {
  const missing = new Set(stage === 'SELF' ? c.missingSelf : c.missingQa);
  for (const it of c.items.filter((i) => missing.has(i.itemNo))) {
    await qarApi.answer(c.roundId, it.itemId, stage, 'PASS');
  }
  return missing.size;
}

function QarPanel({ reportId, revisionId, revStatus }: { reportId: string; revisionId: string; revStatus: string }) {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const reviews = useQuery({
    queryKey: ['qar', 'byReport', reportId],
    queryFn: () => qarLookup.byReport(reportId),
    // AI 단계 · 승인 이벤트는 비동기 — 진행 중이면 주기적으로 갱신
    refetchInterval: (q) => {
      const s = q.state.data?.[0]?.status;
      return s === 'SUBMITTED' || s === 'AI_REVIEWING' ? 2000 : false;
    },
  });
  const review: Review | undefined = reviews.data?.[0];
  const roundId = review?.round.id;
  const checklist = useQuery({ queryKey: ['qar', 'checklist', roundId], queryFn: () => qarApi.checklist(roundId!), enabled: !!roundId });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['qar'] });
    qc.invalidateQueries({ queryKey: ['rpt'] });
  };
  const m = <T,>(fn: () => Promise<T>, msg: (r: T) => string) =>
    useMutation({ mutationFn: fn, onSuccess: (r) => (toast.success(msg(r)), refresh()) });

  const create = m(() => qarApi.create(revisionId), () => 'QAR 검토 생성 — 자체검토(SELF_CHECK)');
  const autocheck = m(() => qarApi.autocheck(review!.id), (r) => `AUTO-CHECK ${r.ruleVersion}: BLOCKING ${r.blocking} · WARNING ${r.warning}`);
  const selfAll = m(async () => answerMissing(await qarApi.checklist(roundId!), 'SELF'), (n) => `자체검토 체크리스트 ${n}개 PASS`);
  const submit = m(async () => qarApi.submit(await qarApi.review(review!.id)), (r) => `제출 → ${r.status}`);
  const runAi = m(() => qarApi.runAiJobs(), (r) => `AI 작업 처리 ${r.processed} (skip ${r.skipped}, timeout ${r.timedOut})`);
  const assignMe = m(() => qarApi.assign(roundId!, session!.actorId), () => 'QA 검토자로 배정');
  const qaAll = m(async () => answerMissing(await qarApi.checklist(roundId!), 'QA'), (n) => `QA 체크리스트 ${n}개 PASS`);
  const pass = m(async () => qarApi.decide(await qarApi.review(review!.id), roundId!, 'PASS'), (r) => `QA 통과 → ${r.status}`);
  const [rejectSummary, setRejectSummary] = useState('');
  const reject = m(async () => qarApi.decide(await qarApi.review(review!.id), roundId!, 'REJECT', rejectSummary), (r) => `반려 → ${r.status}`);
  const approve = m(async () => qarApi.approve(await qarApi.review(review!.id)), (r) => `최종 승인 → ${r.status} (qar.review.approved → RPT)`);

  if (reviews.isLoading) return <Loading />;
  const qaAssignee = review?.round.assignments.find((a) => a.step === 'QA_REVIEW' && !a.completedAt);
  return (
    <Card
      title={
        <>
          QAR 품질 검토{' '}
          {review && (
            <Badge tone={review.status === 'APPROVED' ? 'ok' : review.status === 'REJECTED' ? 'danger' : 'info'}>
              <span data-testid="review-status">{review.status}</span>
            </Badge>
          )}
          {review?.round.aiBadge && <Badge tone="warn">{review.round.aiBadge}</Badge>}
        </>
      }
    >
      {!review ? (
        revStatus === 'IN_REVIEW' && hasRole('PM', 'ENG') ? (
          <button className="primary" onClick={() => create.mutate()} disabled={create.isPending}>
            QAR 검토 시작
          </button>
        ) : (
          <p className="muted">리비전이 검토 요청(IN_REVIEW) 상태가 되면 작성자가 QAR 검토를 시작합니다.</p>
        )
      ) : (
        <>
          <Steps steps={REVIEW_STEPS} current={review.status === 'REJECTED' ? 'QA_REVIEWING' : review.status} />
          <div className="kpis" style={{ marginTop: 10 }}>
            <Kpi label="워크플로우" value={review.workflow} />
            <Kpi label="라운드" value={`${review.currentRound} (Rev.${review.round.revNo})`} />
            <Kpi label="AI" value={review.round.aiStatus} />
            <Kpi label="QA 검토자" value={who(qaAssignee?.assigneeId)} />
            <Kpi label="승인자" value={who(review.approvedBy)} />
            <Kpi label="ETag" value={`v${review.version}`} />
          </div>
          {checklist.data && (
            <p className="muted">
              체크리스트 {checklist.data.template}: 자체 미답 {checklist.data.missingSelf.length} · QA 미답 {checklist.data.missingQa.length}
            </p>
          )}
          <div className="row" style={{ marginTop: 8 }}>
            {review.status === 'SELF_CHECK' && hasRole('PM', 'ENG') && (
              <>
                <button onClick={() => autocheck.mutate()} disabled={autocheck.isPending}>
                  ① AUTO-CHECK 실행
                </button>
                <button onClick={() => selfAll.mutate()} disabled={selfAll.isPending}>
                  ② 자체검토 체크리스트 PASS
                </button>
                <button className="primary" onClick={() => submit.mutate()} disabled={submit.isPending}>
                  ③ 제출 (If-Match)
                </button>
              </>
            )}
            {(review.status === 'SUBMITTED' || review.status === 'AI_REVIEWING') && hasRole('QA') && (
              <button onClick={() => runAi.mutate()} disabled={runAi.isPending}>
                AI 작업 실행 (ai-jobs:run)
              </button>
            )}
            {review.status === 'QA_REVIEWING' && hasRole('QA') && (
              <>
                {qaAssignee?.assigneeId !== session?.actorId && (
                  <button onClick={() => assignMe.mutate()} disabled={assignMe.isPending}>
                    나를 QA 검토자로 배정
                  </button>
                )}
                <button onClick={() => qaAll.mutate()} disabled={qaAll.isPending}>
                  QA 체크리스트 PASS
                </button>
                <button className="primary" onClick={() => pass.mutate()} disabled={pass.isPending}>
                  QA 통과 (PASS)
                </button>
                <input placeholder="반려 사유" value={rejectSummary} onChange={(e) => setRejectSummary(e.target.value)} />
                <button className="danger" disabled={!rejectSummary} onClick={() => reject.mutate()}>
                  반려
                </button>
              </>
            )}
            {review.status === 'PENDING_APPROVAL' && hasRole('PM') && (
              <button className="primary" onClick={() => approve.mutate()} disabled={approve.isPending}>
                책임기술자 최종 승인
              </button>
            )}
          </div>
          {review.round.rejectSummary && <p className="error-box">반려: {review.round.rejectSummary}</p>}
        </>
      )}
    </Card>
  );
}

export function ReportDetailPage() {
  const { id = '' } = useParams();
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const report = useQuery({ queryKey: ['rpt', 'report', id], queryFn: () => rptApi.report(id) });
  const revId = report.data?.currentRevisionId;
  const revision = useQuery({
    queryKey: ['rpt', 'revision', revId],
    queryFn: () => rptApi.revision(revId!),
    enabled: !!revId,
    // QAR 승인 이벤트가 Relay 로 도착하면 IN_REVIEW → APPROVED (비동기)
    refetchInterval: (q) => (q.state.data?.status === 'IN_REVIEW' ? 2000 : false),
  });
  const rev = revision.data;
  const grade = useQuery({
    queryKey: ['rpt', 'grade', revId],
    queryFn: () => rptApi.grade(revId!),
    enabled: !!rev && !['DRAFT', 'SNAPSHOT_FROZEN'].includes(rev.status),
    meta: { silent: true },
    retry: false,
  });
  const [freeze, setFreeze] = useState<FreezeResult>();
  const refresh = () => qc.invalidateQueries({ queryKey: ['rpt'] });
  const m = <T,>(fn: () => Promise<T>, msg: (r: T) => string, after?: (r: T) => void) =>
    useMutation({ mutationFn: fn, onSuccess: (r) => (toast.success(msg(r)), after?.(r), refresh()) });

  const doFreeze = m(() => rptApi.freeze(id, syntheticFreeze()), (r) => `스냅샷 동결 — 결함 ${r.defectCount} · BLOCKING ${r.blockingCount}`, setFreeze);
  const compose = m(() => rptApi.compose(revId!), (r) => `작성(compose) — 산정 등급 ${r.computedGrade} (점수 ${fmtNum(r.weightedScore)})`);
  const [finalGrade, setFinalGrade] = useState<SafetyGrade>();
  const decide = m(
    async () => {
      await rptApi.revision(revId!); // 최신 ETag 확보 → PUT If-Match 자동
      const g = finalGrade ?? grade.data!.computedGrade;
      return rptApi.decideGrade(revId!, { finalGrade: g, reason: '', evidenceDefectIds: [] });
    },
    (r) => `최종 등급 ${r.finalGrade} 확정`,
  );
  const renderReq = m(
    async () => {
      const r = await rptApi.render(revId!);
      await rptApi.reviewRequest(revId!);
      return r;
    },
    (r) => `렌더(${r.converter}, ${r.pageCount}쪽) → 검토 요청(IN_REVIEW)`,
  );
  const publish = m(() => rptApi.publish(revId!), (r) => `발행 완료 — Rev.${r.revNo} PUBLISHED`);
  const deliver = m(() => rptApi.deliver(revId!, '(합성) 발주처 담당자'), () => '납품 확인 기록');

  if (report.isLoading) return <Loading />;
  if (report.error) return <ErrorBox error={report.error} />;
  const r = report.data!;
  const status = rev?.status ?? r.status;
  const canWrite = hasRole('PM', 'ENG');
  const publishBlocked = publish.error instanceof ProblemError ? publish.error : undefined;

  return (
    <>
      <Link to={`/reports?projectId=${r.projectId}`}>← 목록</Link>
      <h1>{r.title}</h1>
      <Card
        title={
          <>
            Rev.{rev?.revNo ?? r.currentRev}{' '}
            <Badge tone={status === 'PUBLISHED' || status === 'DELIVERED' ? 'ok' : 'info'}>
              <span data-testid="revision-status">{REV_STATUS_LABEL[status]}</span>
            </Badge>
          </>
        }
      >
        <Steps steps={REV_STEPS} current={status === 'SIGNING' ? 'APPROVED' : status} />
        <div className="kpis" style={{ marginTop: 10 }}>
          <Kpi label="유형" value={REPORT_TYPE_LABEL[r.reportType]} />
          <Kpi label="점검일" value={r.inspectionDate} />
          <Kpi label="산정 등급" value={grade.data?.computedGrade ?? rev?.grade?.computedGrade} />
          <Kpi label="최종 등급" value={grade.data?.finalGrade ?? rev?.grade?.finalGrade} />
          <Kpi label="PDF" value={rev?.pdfSha256 ? `${rev.pdfSha256.slice(0, 10)}… (${rev.pageCount}쪽)` : '—'} />
          <Kpi label="발행" value={fmtDateTime(rev?.publishedAt)} />
        </div>
        <div className="row" style={{ marginTop: 12 }}>
          {canWrite && ['DRAFT', 'SNAPSHOT_FROZEN'].includes(status) && (
            <button onClick={() => doFreeze.mutate()} disabled={doFreeze.isPending}>
              ① 합성 스냅샷 동결
            </button>
          )}
          {canWrite && ['SNAPSHOT_FROZEN', 'COMPOSING'].includes(status) && (
            <button onClick={() => compose.mutate()} disabled={compose.isPending}>
              ② 작성(compose) · 등급 산정
            </button>
          )}
          {status === 'COMPOSING' && hasRole('PM') && grade.data && (
            <>
              <select aria-label="최종 등급" value={finalGrade ?? grade.data.computedGrade} onChange={(e) => setFinalGrade(e.target.value as SafetyGrade)}>
                {(['A', 'B', 'C', 'D', 'E'] as SafetyGrade[]).map((g) => (
                  <option key={g}>{g}</option>
                ))}
              </select>
              <button onClick={() => decide.mutate()} disabled={decide.isPending}>
                ③ 최종 등급 확정 (PUT If-Match)
              </button>
            </>
          )}
          {canWrite && status === 'COMPOSING' && (
            <button className="primary" onClick={() => renderReq.mutate()} disabled={renderReq.isPending}>
              ④ 렌더 · 검토 요청
            </button>
          )}
          {hasRole('PM', 'EXEC') && ['IN_REVIEW', 'APPROVED'].includes(status) && (
            <button className="primary" onClick={() => publish.mutate()} disabled={publish.isPending || status !== 'APPROVED'} data-testid="publish">
              발행 (publish)
            </button>
          )}
          {hasRole('PM', 'ADMIN') && status === 'PUBLISHED' && <button onClick={() => deliver.mutate()}>납품 확인</button>}
        </div>
        {status === 'IN_REVIEW' && <p className="muted">발행은 QAR 최종 승인(qar.review.approved) 이후에만 가능합니다.</p>}
        {status === 'COMPOSING' && grade.data && grade.data.finalGrade && grade.data.finalGrade !== (finalGrade ?? grade.data.finalGrade) && (
          <p className="muted">산정 등급과 다르게 바꾸려면 사유 100자 + 근거 결함이 필요합니다(RPT-422-003).</p>
        )}
        {publishBlocked && <ErrorBox error={publishBlocked} />}
        {freeze && freeze.issues.length > 0 && (
          <ul>
            {freeze.issues.map((i, k) => (
              <li key={k}>
                <Badge tone={i.severity === 'BLOCKING' ? 'danger' : 'warn'}>{i.ruleCode}</Badge> {i.message}
              </li>
            ))}
          </ul>
        )}
      </Card>

      {revId && <QarPanel reportId={r.id} revisionId={revId} revStatus={status} />}

      <Card title="리비전 이력">
        <table className="simple">
          <thead>
            <tr>
              <th>Rev</th>
              <th>상태</th>
              <th>발행</th>
              <th>납품</th>
              <th>PDF sha256</th>
            </tr>
          </thead>
          <tbody>
            {r.revisions.map((x) => (
              <tr key={x.id}>
                <td>{x.revNo}</td>
                <td>{REV_STATUS_LABEL[x.status]}</td>
                <td>{fmtDateTime(x.publishedAt)}</td>
                <td>{fmtDateTime(x.deliveredAt)}</td>
                <td className="mono">{x.pdfSha256?.slice(0, 16) ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
