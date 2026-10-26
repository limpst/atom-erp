import { useMemo, useState, type ChangeEvent, type DragEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { ProblemError } from '@/api/http';
import {
  BOARD_STAGES, CAPABILITY_LABEL, canMove, CREDIT_GRADES, LOST_REASON_LABEL, NOTICE_STATUS_LABEL, parseBidRates,
  salApi, salBoardApi, STAGE_LABEL, STAGE_NEXT, type BidNotice, type BidNoticeStatus, type LostReason, type Opportunity,
  type OpportunityStage, type SimulationResult,
} from '@/api/sal';
import { toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, fmtMoney, fmtNum, Kpi, Loading, Modal } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { todayIso, useEmployees } from '@/features/admin/common';

const TERMINAL: OpportunityStage[] = ['WON', 'LOST', 'DROPPED', 'CONTRACTED'];
const pctText = (r: number | null | undefined) => (r == null ? '—' : `${(Number(r) * 100).toFixed(2)}%`);

/** 서버 거부(403 권한 · 409 전이 · 412 버전 · 422 규칙)를 화면 안 문구로 */
function refusalText(e: ProblemError) {
  const head = `[${e.code ?? `HTTP ${e.status}`}] ${e.problem.title ?? ''}`.trim();
  const hint =
    e.status === 403 ? '이 역할로는 영업기회 단계를 바꿀 수 없습니다(서버 권한 검사).'
      : e.status === 412 ? '다른 사용자가 먼저 바꿨습니다 — 보드를 새로 읽었습니다. 다시 시도하세요.'
        : e.status === 409 ? '단계 전이표에 없는 이동입니다.'
          : undefined;
  return { head, detail: [e.problem.detail, hint].filter(Boolean).join(' — ') };
}

// ================================================================== 파이프라인 보드
function MoveDialog({ opp, to, onClose, onConfirm }: {
  opp: Opportunity; to: OpportunityStage; onClose: () => void; onConfirm: (v: { note?: string; lostReason?: LostReason }) => void;
}) {
  const [reason, setReason] = useState<LostReason | ''>('');
  const [note, setNote] = useState('');
  return (
    <Modal
      title={`${STAGE_LABEL[to]}(으)로 이동`}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>취소</button>
          <button className="primary" data-testid="move-confirm"
            onClick={() => onConfirm({ note: note || undefined, lostReason: reason || undefined })}>
            이동
          </button>
        </>
      }
    >
      <p>{opp.oppNo} {opp.title} — {STAGE_LABEL[opp.stage]} → <strong>{STAGE_LABEL[to]}</strong></p>
      {to === 'LOST' && (
        <Field label="실주 사유(필수 — BR-SAL-010)">
          <select aria-label="실주 사유" value={reason} onChange={(e) => setReason(e.target.value as LostReason)}>
            <option value="">선택</option>
            {(Object.keys(LOST_REASON_LABEL) as LostReason[]).map((r) => <option key={r} value={r}>{LOST_REASON_LABEL[r]}</option>)}
          </select>
        </Field>
      )}
      <Field label="메모">
        <input aria-label="이동 메모" value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
    </Modal>
  );
}

function Board() {
  const qc = useQueryClient();
  const { label, employees } = useEmployees();
  const owner = (id: string | null) => (employees.length ? label(id) : id ? '담당 지정됨' : '담당 없음');
  const opps = useQuery({ queryKey: ['sal', 'opps', 'board'], queryFn: () => salApi.listOpportunities({ size: 300 }) });
  const [dragging, setDragging] = useState<Opportunity>();
  const [asking, setAsking] = useState<{ opp: Opportunity; to: OpportunityStage }>();
  const [refused, setRefused] = useState<{ oppId: string; head: string; detail: string }>();
  const move = useMutation({
    mutationFn: (v: { opp: Opportunity; to: OpportunityStage; note?: string; lostReason?: LostReason }) =>
      salApi.moveOpportunity(v.opp, { toStage: v.to, note: v.note, lostReason: v.lostReason }),
    meta: { silent: true },
    onSuccess: (o) => {
      setRefused(undefined);
      toast.success(`${o.oppNo} → ${STAGE_LABEL[o.stage]}`);
      qc.invalidateQueries({ queryKey: ['sal'] });
    },
    onError: (e, v) => {
      if (e instanceof ProblemError && [403, 409, 412, 422, 428].includes(e.status)) {
        setRefused({ oppId: v.opp.id, ...refusalText(e) });
        if (e.status === 412) qc.invalidateQueries({ queryKey: ['sal', 'opps'] });
      } else toastError(e);
    },
  });
  const request = (opp: Opportunity, to: OpportunityStage) => {
    if (to === 'LOST' || to === 'DROPPED') setAsking({ opp, to });
    else move.mutate({ opp, to });
  };
  const byStage = useMemo(() => {
    const m = new Map<OpportunityStage, Opportunity[]>();
    BOARD_STAGES.forEach((s) => m.set(s, []));
    (opps.data?.content ?? []).forEach((o) => m.get(o.stage)?.push(o));
    return m;
  }, [opps.data]);
  const onDrop = (e: DragEvent, to: OpportunityStage) => {
    e.preventDefault();
    const o = dragging;
    setDragging(undefined);
    if (!o || o.stage === to) return;
    // 전이표 밖이어도 서버 판정을 그대로 보여 준다(화면 판단은 안내용)
    request(o, to);
  };
  if (opps.error) return <ErrorBox error={opps.error} />;
  if (!opps.data) return <Loading />;
  const open = opps.data.content.filter((o) => !TERMINAL.includes(o.stage));
  const weighted = open.reduce((s, o) => s + (Number(o.expectedAmount ?? 0) * Number(o.probability ?? 0)) / 100, 0);
  return (
    <>
      <div className="kpis" style={{ marginBottom: 12 }}>
        <Kpi label="진행 중 기회" value={`${open.length}건`} />
        <Kpi label="예상 금액(가중)" value={fmtMoney(Math.round(weighted))} />
        <Kpi label="수주 · 계약" value={`${(byStage.get('WON')?.length ?? 0) + (byStage.get('CONTRACTED')?.length ?? 0)}건`} tone="ok" />
      </div>
      <p className="muted">카드를 다른 열로 끌어 놓거나 카드의 단계 버튼을 누르면 단계가 바뀝니다(If-Match 낙관적 잠금). 초록 테두리 = 갈 수 있는 열.</p>
      {refused && (
        <Banner tone="danger" testId="board-refused">
          <strong>{refused.head}</strong> {refused.detail}
        </Banner>
      )}
      <div className="kanban" data-testid="sal-board">
        {BOARD_STAGES.map((stage) => {
          const list = byStage.get(stage) ?? [];
          const dropClass = dragging ? (dragging.stage === stage ? '' : canMove(dragging.stage, stage) ? 'drop-ok' : 'drop-no') : '';
          return (
            <div key={stage} className={`col ${dropClass}`} data-testid={`col-${stage}`}
              onDragOver={(e) => e.preventDefault()} onDrop={(e) => onDrop(e, stage)}>
              <h4><span>{STAGE_LABEL[stage]}</span><span>{list.length}</span></h4>
              {list.map((o) => (
                <div key={o.id} className={`opp ${refused?.oppId === o.id ? 'sel' : ''}`} draggable data-testid={`opp-${o.oppNo}`}
                  onDragStart={() => setDragging(o)} onDragEnd={() => setDragging(undefined)}>
                  <div className="t">{o.title}</div>
                  <div className="muted">{o.oppNo} · {owner(o.ownerId)}</div>
                  <div>{fmtMoney(o.expectedAmount)}{o.probability != null && ` · ${o.probability}%`}</div>
                  {o.criteriaChanged && <Badge tone="warn">공고 정정됨</Badge>}
                  {o.lostReason && <Badge tone="danger">{LOST_REASON_LABEL[o.lostReason]}</Badge>}
                  <div className="moves">
                    {STAGE_NEXT[o.stage].map((to) => (
                      <button key={to} disabled={move.isPending} onClick={() => request(o, to)} aria-label={`${o.oppNo} → ${STAGE_LABEL[to]}`}>
                        → {STAGE_LABEL[to]}
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          );
        })}
      </div>
      {asking && (
        <MoveDialog opp={asking.opp} to={asking.to} onClose={() => setAsking(undefined)}
          onConfirm={(v) => { move.mutate({ opp: asking.opp, to: asking.to, ...v }); setAsking(undefined); }} />
      )}
    </>
  );
}

// ================================================================== 공고 수집
function daysUntil(iso: string | null) {
  if (!iso) return null;
  return Math.ceil((Date.parse(iso) - Date.now()) / 86_400_000);
}

export function filterNotices(rows: BidNotice[], f: { closingWithin?: number; minPrice?: number; hideClosed?: boolean }) {
  return rows.filter((n) => {
    const d = daysUntil(n.closeAt);
    if (f.hideClosed && (n.status === 'SUPERSEDED' || n.status === 'CANCELLED')) return false;
    if (f.closingWithin != null && (d == null || d < 0 || d > f.closingWithin)) return false;
    if (f.minPrice != null && Number(n.estPrice ?? 0) < f.minPrice) return false;
    return true;
  });
}

function Notices() {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const [status, setStatus] = useState<BidNoticeStatus | ''>('');
  const [q, setQ] = useState('');
  const [keyword, setKeyword] = useState('');
  const [within, setWithin] = useState('');
  const [minPrice, setMinPrice] = useState('');
  const [hideClosed, setHideClosed] = useState(false);
  const notices = useQuery({ queryKey: ['sal', 'notices', status, keyword], queryFn: () => salBoardApi.notices({ status: status || undefined, q: keyword || undefined }) });
  const logs = useQuery({ queryKey: ['sal', 'collect-logs'], queryFn: () => salBoardApi.collectLogs(5), meta: { silent: true } });
  const collect = useMutation({
    mutationFn: salBoardApi.collectNow,
    onSuccess: (r) => {
      if (r.success) toast.success(`공고 수집 — 받은 ${r.fetched}건 · 신규 ${r.inserted}건 · 갱신 ${r.upserted}건`);
      else toast.warning(`공고 수집 실패: ${r.error}`);
      qc.invalidateQueries({ queryKey: ['sal'] });
    },
  });
  const convert = useMutation({
    mutationFn: (n: BidNotice) => salApi.createOpportunity({
      source: 'G2B', bidNoticeId: n.id, serviceType: 'PRECISE_INSPECTION', title: n.title,
      expectedAmount: n.estPrice ?? undefined, ownerId: session?.actorId,
    }),
    onSuccess: (o) => { toast.success(`영업기회 ${o.oppNo} 생성(리드)`); qc.invalidateQueries({ queryKey: ['sal'] }); },
  });
  const rows = filterNotices(notices.data ?? [], {
    closingWithin: within ? Number(within) : undefined, minPrice: minPrice ? Number(minPrice) : undefined, hideClosed,
  });
  const last = logs.data?.[0];
  return (
    <>
      <Card
        title="수집 이력(나라장터)"
        actions={hasRole('ADMIN', 'SYSADMIN') && (
          <button className="primary" disabled={collect.isPending} onClick={() => collect.mutate()} data-testid="collect-now">지금 수집</button>
        )}
      >
        {last ? (
          <p data-testid="collect-last">
            마지막 수집 {fmtDateTime(last.startedAt)} · {last.jobType} ·{' '}
            {last.success ? <Badge tone="ok">성공</Badge> : last.success === false ? <Badge tone="danger">실패 {last.error}</Badge> : <Badge tone="warn">진행 중</Badge>}
            {' '}받은 {last.fetched ?? 0} · 신규 {last.inserted ?? 0} · 갱신 {last.upserted ?? 0}
          </p>
        ) : <p className="muted">수집 이력이 없습니다.</p>}
        <p className="muted">구간은 직전 성공 수집의 끝 − 10분부터 지금까지입니다(빠짐 방지). ⚠️ 공공 API 명세는 확인 필요 — 개발 서버는 합성 대역을 씁니다.</p>
      </Card>
      <Card title="수집된 공고">
        <div className="form" style={{ marginBottom: 8 }}>
          <Field label="상태">
            <select aria-label="공고 상태" value={status} onChange={(e) => setStatus(e.target.value as BidNoticeStatus | '')}>
              <option value="">전체</option>
              {(Object.keys(NOTICE_STATUS_LABEL) as BidNoticeStatus[]).map((s) => <option key={s} value={s}>{NOTICE_STATUS_LABEL[s]}</option>)}
            </select>
          </Field>
          <Field label="제목 검색(서버)">
            <input aria-label="공고 검색어" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && setKeyword(q.trim())} />
          </Field>
          <Field label="마감까지(일 이내)">
            <input aria-label="마감 일수" type="number" min={0} value={within} onChange={(e) => setWithin(e.target.value)} />
          </Field>
          <Field label="추정가격 이상(원)">
            <input aria-label="최소 추정가격" type="number" min={0} value={minPrice} onChange={(e) => setMinPrice(e.target.value)} />
          </Field>
          <label className="row" style={{ gap: 4 }}>
            <input type="checkbox" aria-label="정정 · 취소 숨기기" checked={hideClosed} onChange={(e) => setHideClosed(e.target.checked)} />
            정정 전 차수 · 취소 숨기기
          </label>
          <button onClick={() => setKeyword(q.trim())}>검색</button>
        </div>
        {notices.error && <ErrorBox error={notices.error} />}
        <table className="simple" data-testid="notices">
          <thead><tr><th>공고번호</th><th>제목</th><th>공고 · 수요기관</th><th className="num">추정가격</th><th>마감</th><th>구분</th><th>상태</th><th /></tr></thead>
          <tbody>
            {rows.map((n) => {
              const d = daysUntil(n.closeAt);
              return (
                <tr key={n.id} data-testid={`notice-${n.bidNo}-${n.bidSeq}`}>
                  <td className="mono">{n.bidNo}-{n.bidSeq}</td>
                  <td>{n.title}</td>
                  <td>{n.orgName}<div className="muted" style={{ fontSize: 12 }}>{n.demandOrgName}</div></td>
                  <td className="num">{fmtMoney(n.estPrice)}</td>
                  <td>{fmtDateTime(n.closeAt)} {d != null && d >= 0 && <Badge tone={d <= 3 ? 'danger' : d <= 7 ? 'warn' : 'ok'}>D-{d}</Badge>}</td>
                  <td>{n.noticeKind ?? '—'}</td>
                  <td><Badge tone={n.status === 'CANCELLED' ? 'danger' : n.status === 'SUPERSEDED' ? undefined : 'info'}>{NOTICE_STATUS_LABEL[n.status] ?? n.status}</Badge></td>
                  <td>
                    {hasRole('SALES') && (n.status === 'NEW' || n.status === 'WATCHING') && (
                      <button onClick={() => convert.mutate(n)} disabled={convert.isPending} aria-label={`기회 만들기 ${n.bidNo}-${n.bidSeq}`}>기회 만들기</button>
                    )}
                  </td>
                </tr>
              );
            })}
            {notices.data && rows.length === 0 && <tr><td colSpan={8} className="muted">조건에 맞는 공고가 없습니다</td></tr>}
          </tbody>
        </table>
        <p className="muted">{rows.length} / {notices.data?.length ?? 0}건 표시</p>
      </Card>
    </>
  );
}

// ================================================================== 적격심사 시뮬레이션
function SimulationResultView({ r }: { r: SimulationResult }) {
  const best = r.scenarios.find((s) => s.bidRate === r.recommendedBidRate);
  return (
    <div data-testid="sim-result">
      <div className="kpis" style={{ margin: '12px 0' }}>
        <Kpi label="통과 기준" value={`${fmtNum(r.passScore)}점`} />
        <Kpi label="수행능력 점수" value={`${fmtNum(r.capability.total)}점`} />
        <Kpi label="낙찰하한율" value={pctText(r.lowerLimitRate)} />
        <Kpi label="추천 투찰률(참고)" value={pctText(r.recommendedBidRate)} tone={best?.pass ? 'ok' : 'warn'} />
      </div>
      <h3>수행능력 항목별</h3>
      <table className="simple" data-testid="sim-capability">
        <thead><tr><th>항목</th><th>입력값</th><th className="num">비율</th><th className="num">점수 / 배점</th></tr></thead>
        <tbody>
          {r.capability.items.map((i) => (
            <tr key={i.key}>
              <td>{CAPABILITY_LABEL[i.key] ?? i.key}</td>
              <td>{typeof i.raw === 'number' ? fmtNum(i.raw, 0) : String(i.raw ?? '—')}</td>
              <td className="num">{i.ratio == null ? '—' : fmtNum(i.ratio, 3)}</td>
              <td className="num">{fmtNum(i.score)} / {fmtNum(i.max)}</td>
            </tr>
          ))}
          <tr><th>합계</th><td /><td /><th className="num">{fmtNum(r.capability.total)}</th></tr>
        </tbody>
      </table>
      <h3>투찰률 시나리오</h3>
      <table className="simple" data-testid="sim-scenarios">
        <thead><tr><th className="num">투찰률</th><th className="num">가격 점수</th><th className="num">총점</th><th>판정</th></tr></thead>
        <tbody>
          {r.scenarios.map((s) => (
            <tr key={s.bidRate} style={{ fontWeight: s.bidRate === r.recommendedBidRate ? 600 : undefined }}>
              <td className="num">{pctText(s.bidRate)}</td>
              <td className="num">{fmtNum(s.priceScore)}</td>
              <td className="num">{fmtNum(s.total)}</td>
              <td>
                {s.belowLowerLimit ? <Badge tone="danger">하한율 미만 — 탈락</Badge>
                  : s.pass ? <Badge tone="ok">통과</Badge> : <Badge tone="warn">미달</Badge>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>반영한 실적({r.inputs.similarYears}년 · sal.performance_record)</h3>
      {r.inputs.performanceRecords.length === 0 ? <p className="muted">해당 기간 같은 용역 실적이 없습니다(유사 실적 0원).</p> : (
        <table className="simple">
          <thead><tr><th>증명서</th><th>종료일</th><th className="num">금액</th><th className="num">지분</th><th className="num">인정 금액</th></tr></thead>
          <tbody>
            {r.inputs.performanceRecords.map((p) => (
              <tr key={p.certNo}><td>{p.certNo}</td><td>{p.endDate}</td><td className="num">{fmtMoney(p.amountKrw)}</td><td className="num">{p.jointShare}%</td><td className="num">{fmtMoney(p.creditedKrw)}</td></tr>
            ))}
          </tbody>
        </table>
      )}
      {r.notice && <Banner tone="warn">{r.notice}</Banner>}
    </div>
  );
}

function Simulation() {
  const [params] = useSearchParams();
  const opps = useQuery({ queryKey: ['sal', 'opps', 'board'], queryFn: () => salApi.listOpportunities({ size: 300 }) });
  const candidates = (opps.data?.content ?? []).filter((o) => !TERMINAL.includes(o.stage));
  const [oppId, setOppId] = useState(params.get('opp') ?? '');
  const opp = candidates.find((o) => o.id === oppId) ?? candidates.find((o) => o.bidNoticeId) ?? candidates[0];
  const [f, setF] = useState({
    noticeDate: todayIso(), estPrice: '', bidRates: '87.7, 88, 88.5, 90', engineersQualified: '6', engineersRequired: '5',
    creditGrade: 'A0', disabled: false,
  });
  const [refused, setRefused] = useState<string>();
  const sim = useMutation({
    mutationFn: () => salBoardApi.simulate(opp!.id, {
      noticeDate: f.noticeDate || undefined, estPrice: f.estPrice ? Number(f.estPrice) : undefined,
      bidRates: parseBidRates(f.bidRates), engineersQualified: Number(f.engineersQualified),
      engineersRequired: Number(f.engineersRequired), creditGrade: f.creditGrade, disabledStandardWorkplace: f.disabled,
    }),
    meta: { silent: true },
    onSuccess: () => setRefused(undefined),
    onError: (e) => {
      if (e instanceof ProblemError && e.status >= 400 && e.status < 500) setRefused(`[${e.code ?? e.status}] ${e.problem.title ?? ''} — ${e.problem.detail ?? ''}`);
      else toastError(e);
    },
  });
  const rates = parseBidRates(f.bidRates);
  const set = (k: keyof typeof f) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? (e.target as HTMLInputElement).checked : e.target.value });
  return (
    <Card title="적격심사 시뮬레이션">
      <Banner tone="warn">⚠️ 예시 기준(합성) — 실제 세부기준 · 가격점수 산식은 최신 고시 확인 필요. 결과는 참고용이며 입력은 기회별로 저장됩니다.</Banner>
      {opps.error && <ErrorBox error={opps.error} />}
      <div className="form">
        <Field label="영업기회">
          <select aria-label="시뮬레이션 기회" value={opp?.id ?? ''} onChange={(e) => setOppId(e.target.value)}>
            {candidates.map((o) => <option key={o.id} value={o.id}>{o.oppNo} {o.title}{o.bidNoticeId ? ' (공고 연결)' : ''}</option>)}
          </select>
        </Field>
        <Field label="공고일"><input aria-label="공고일" type="date" value={f.noticeDate} onChange={set('noticeDate')} /></Field>
        <Field label="추정가격(원, 비우면 공고 값)"><input aria-label="추정가격" type="number" value={f.estPrice} onChange={set('estPrice')} /></Field>
        <Field label="투찰률 목록(%, 쉼표)"><input aria-label="투찰률 목록" value={f.bidRates} onChange={set('bidRates')} /></Field>
        <Field label="보유 기술자(명)"><input aria-label="보유 기술자" type="number" value={f.engineersQualified} onChange={set('engineersQualified')} /></Field>
        <Field label="요구 기술자(명)"><input aria-label="요구 기술자" type="number" value={f.engineersRequired} onChange={set('engineersRequired')} /></Field>
        <Field label="신용 등급">
          <select aria-label="신용 등급" value={f.creditGrade} onChange={set('creditGrade')}>
            {CREDIT_GRADES.map((g) => <option key={g} value={g}>{g}</option>)}
          </select>
        </Field>
        <label className="row" style={{ gap: 4 }}>
          <input type="checkbox" aria-label="장애인 표준사업장" checked={f.disabled} onChange={set('disabled')} /> 장애인 표준사업장(가점)
        </label>
        <button className="primary" disabled={!opp || rates.length === 0 || sim.isPending} onClick={() => sim.mutate()} data-testid="sim-run">계산</button>
      </div>
      <p className="muted">투찰률 {rates.length}개: {rates.map((r) => pctText(r)).join(', ')}</p>
      {refused && <Banner tone="danger" testId="sim-refused">{refused}</Banner>}
      {sim.data && <SimulationResultView r={sim.data} />}
    </Card>
  );
}

// ================================================================== 페이지
type Tab = 'board' | 'notices' | 'sim';
export function SalPipelinePage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'board';
  const go = (t: Tab) => setParams((p) => { p.set('tab', t); return p; });
  return (
    <>
      <h1>영업 파이프라인 · 공고</h1>
      <Card>
        <div className="tabs" role="tablist">
          <button role="tab" className={tab === 'board' ? 'active' : ''} onClick={() => go('board')}>파이프라인 보드</button>
          <button role="tab" className={tab === 'notices' ? 'active' : ''} onClick={() => go('notices')}>공고 수집</button>
          <button role="tab" className={tab === 'sim' ? 'active' : ''} onClick={() => go('sim')}>적격심사 시뮬레이션</button>
        </div>
        {tab === 'board' && <Board />}
        {tab === 'notices' && <Notices />}
        {tab === 'sim' && <Simulation />}
      </Card>
    </>
  );
}
