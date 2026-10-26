import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import {
  ESTIMATE_STATUS_LABEL, salApi, STAGE_LABEL, STAGE_NEXT, type Estimate, type EstimateStatus, type FeeCalcRequest,
  type FeeCalcResult, type Opportunity, type OpportunityStage,
} from '@/api/sal';
import { coreApi } from '@/api/core';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Card, ErrorBox, Field, fmtMoney, fmtNum, Kpi } from '@/components/ui';

const statusTone = (s: EstimateStatus) =>
  s === 'ISSUED' || s === 'ACCEPTED' ? 'ok' : s === 'PENDING_APPROVAL' ? 'warn' : s === 'REJECTED' || s === 'EXPIRED' ? 'danger' : undefined;

const today = () => new Date().toISOString().slice(0, 10);

interface FeeForm {
  asOfDate: string; grossAreaM2: string; structure: string; floors: string; basementFloors: string;
  completionYear: string; inUse: boolean; travelQty: string; discountPct: string; overheadRate: string; techFeeRate: string;
}
const defaultFee: FeeForm = {
  asOfDate: today(), grossAreaM2: '12000', structure: 'RC', floors: '5', basementFloors: '1', completionYear: '2001',
  inUse: true, travelQty: '4', discountPct: '3', overheadRate: '1.1', techFeeRate: '0.2',
};
function toRequest(f: FeeForm, serviceType: string): FeeCalcRequest {
  return {
    asOfDate: f.asOfDate,
    serviceType,
    facility: {
      facilityClass: 'BUILDING', grossAreaM2: Number(f.grossAreaM2), structure: f.structure, floors: Number(f.floors),
      basementFloors: Number(f.basementFloors), completionYear: Number(f.completionYear), inUse: f.inUse,
    },
    overheadRate: Number(f.overheadRate),
    techFeeRate: Number(f.techFeeRate),
    expenses: Number(f.travelQty) > 0 ? [{ item: 'TRAVEL', quantity: Number(f.travelQty), unitPrice: 150000 }] : [],
    discountRate: Number(f.discountPct) / 100,
  };
}

export function EstimatesPage() {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const opps = useQuery({ queryKey: ['sal', 'opps'], queryFn: () => salApi.listOpportunities() });
  const [params] = useSearchParams();
  // "내 할 일" 링크(?opp=) 로 들어오면 그 기회를 바로 연다
  const [oppId, setOppId] = useState<string | undefined>(params.get('opp') ?? undefined);
  const opp = opps.data?.content.find((o) => o.id === oppId);
  const estimates = useQuery({
    queryKey: ['sal', 'estimates', oppId],
    queryFn: () => salApi.listEstimates(oppId!),
    enabled: !!oppId,
  });
  const [estId, setEstId] = useState<string>();
  // ?opp= 로 들어오면 최신 견적 버전을 바로 연다(할인 승인 처리)
  useEffect(() => {
    if (!estId && oppId && oppId === params.get('opp') && estimates.data?.length) {
      setEstId([...estimates.data].sort((a, b) => b.versionNo - a.versionNo)[0].id);
    }
  }, [estId, oppId, params, estimates.data]);
  const estimate = useQuery({ queryKey: ['sal', 'estimate', estId], queryFn: () => salApi.getEstimate(estId!), enabled: !!estId });

  const refresh = () => qc.invalidateQueries({ queryKey: ['sal'] });
  const setEst = (e: Estimate) => {
    qc.setQueryData(['sal', 'estimate', e.id], e);
    setEstId(e.id);
    refresh();
  };

  const oppCols = useMemo<ColDef<Opportunity>[]>(
    () => [
      { field: 'oppNo', headerName: '기회 번호', width: 140 },
      { field: 'title', headerName: '제목', flex: 2 },
      { field: 'stage', headerName: '단계', width: 100, valueFormatter: (p) => STAGE_LABEL[p.value as OpportunityStage] },
      { field: 'serviceType', headerName: 'Service type', width: 130 },
      { field: 'expectedAmount', headerName: '예상 금액', width: 140, type: 'rightAligned', valueFormatter: (p) => fmtMoney(p.value) },
    ],
    [],
  );

  // ---- 영업기회 생성 · 단계 이동
  const clients = useQuery({ queryKey: ['clients'], queryFn: coreApi.clients, enabled: hasRole('SALES', 'EXEC', 'ADMIN') });
  const [newOpp, setNewOpp] = useState({ title: '', clientId: '', serviceType: 'PRECISE_DIAG' });
  const createOpp = useMutation({
    mutationFn: () =>
      salApi.createOpportunity({ source: 'INQUIRY', ...newOpp, ownerId: session?.actorId }),
    onSuccess: (o) => {
      toast.success(`영업기회 ${o.oppNo} 생성`);
      setOppId(o.id);
      setNewOpp({ ...newOpp, title: '' });
      refresh();
    },
  });
  const move = useMutation({
    mutationFn: (to: OpportunityStage) => salApi.moveOpportunity(opp!, { toStage: to, ownerId: opp!.ownerId ?? session?.actorId }),
    onSuccess: (o) => {
      toast.success(`단계 → ${STAGE_LABEL[o.stage]}`);
      refresh();
    },
  });

  // ---- 견적
  const [fee, setFee] = useState<FeeForm>(defaultFee);
  const [preview, setPreview] = useState<FeeCalcResult>();
  const calc = useMutation({
    mutationFn: () => salApi.calculateFee(toRequest(fee, opp!.serviceType)),
    onSuccess: setPreview,
  });
  const createEst = useMutation({
    mutationFn: () => salApi.createEstimate(oppId!, toRequest(fee, opp!.serviceType)),
    onSuccess: (e) => {
      toast.success(`견적 ${e.estimateNo} v${e.versionNo} 작성`);
      setEst(e);
    },
  });
  const issue = useMutation({
    mutationFn: () => salApi.issue(estId!),
    onSuccess: (e) => {
      if (e.status === 'PENDING_APPROVAL') {
        toast.warning(`할인율 ${fmtNum(e.discountRate * 100)}% 가 발행자 한도 ${fmtNum((e.discountLimit ?? 0) * 100)}% 를 넘어 임원 승인 대기`);
      } else toast.success(`견적 발행 (${ESTIMATE_STATUS_LABEL[e.status]})`);
      setEst(e);
    },
  });
  const [comment, setComment] = useState('');
  const decide = useMutation({
    mutationFn: (decision: 'APPROVE' | 'REJECT') => salApi.approveDiscount(estimate.data!, { decision, comment: comment || undefined }),
    onSuccess: (e) => {
      toast.success(e.approvedBy ? '할인 승인 — 영업이 다시 발행하면 ISSUED' : '할인 반려 — 작성 중(DRAFT)으로 복귀');
      setEst(e);
    },
  });
  const act = useMutation({
    mutationFn: (a: 'send' | 'accept' | 'reject') =>
      a === 'send' ? salApi.send(estId!) : a === 'accept' ? salApi.accept(estId!) : salApi.reject(estId!),
    onSuccess: (e) => {
      toast.success(`견적 ${ESTIMATE_STATUS_LABEL[e.status]}`);
      setEst(e);
    },
  });

  const e = estimate.data;
  const canCreateEstimate = !!opp && ['QUALIFYING', 'PROPOSING', 'NEGOTIATING'].includes(opp.stage) && hasRole('SALES', 'PM');
  const F = (k: keyof FeeForm, label: string, type = 'number') => (
    <Field label={label}>
      <input
        type={type}
        step="any"
        value={fee[k] as string}
        onChange={(ev) => setFee({ ...fee, [k]: ev.target.value })}
        data-testid={`fee-${k}`}
      />
    </Field>
  );

  return (
    <>
      <h1>영업기회 · 견적</h1>
      <Card title="영업기회 (SAL opportunities)">
        {opps.error ? (
          <ErrorBox error={opps.error} />
        ) : (
          <DataGrid<Opportunity>
            testId="opps-grid"
            rowData={opps.data?.content}
            columnDefs={oppCols}
            height={240}
            getRowId={(p) => p.data.id}
            rowSelection={{ mode: 'singleRow', checkboxes: false, enableClickSelection: true }}
            onRowClicked={(ev) => {
              setOppId(ev.data?.id);
              setEstId(undefined);
              setPreview(undefined);
            }}
          />
        )}
        {hasRole('SALES') && (
          <form
            className="form"
            style={{ marginTop: 12 }}
            onSubmit={(ev) => {
              ev.preventDefault();
              createOpp.mutate();
            }}
          >
            <Field label="새 영업기회 제목">
              <input value={newOpp.title} onChange={(ev) => setNewOpp({ ...newOpp, title: ev.target.value })} required />
            </Field>
            <Field label="발주처">
              <select value={newOpp.clientId} onChange={(ev) => setNewOpp({ ...newOpp, clientId: ev.target.value })}>
                <option value="">(미정)</option>
                {clients.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Service type">
              <select value={newOpp.serviceType} onChange={(ev) => setNewOpp({ ...newOpp, serviceType: ev.target.value })}>
                <option>PRECISE_DIAG</option>
                <option>PRECISE_INSP</option>
                <option>SEISMIC_EVAL</option>
              </select>
            </Field>
            <button disabled={createOpp.isPending}>영업기회 등록</button>
          </form>
        )}
      </Card>

      {opp && (
        <Card
          title={
            <>
              {opp.oppNo} {opp.title} <Badge tone="info">{STAGE_LABEL[opp.stage]}</Badge>
            </>
          }
          actions={
            hasRole('SALES') &&
            STAGE_NEXT[opp.stage]
              .filter((s) => s !== 'LOST')
              .map((s) => (
                <button key={s} onClick={() => move.mutate(s)} disabled={move.isPending}>
                  → {STAGE_LABEL[s]}
                </button>
              ))
          }
        >
          <h3>견적 버전</h3>
          <table className="simple" data-testid="estimate-versions">
            <thead>
              <tr>
                <th>번호</th>
                <th>버전</th>
                <th>상태</th>
                <th className="num">용역비(할인 전)</th>
                <th className="num">할인율</th>
                <th className="num">제시 금액</th>
                <th>기준일</th>
              </tr>
            </thead>
            <tbody>
              {estimates.data?.map((x) => (
                <tr key={x.id} onClick={() => setEstId(x.id)} style={{ cursor: 'pointer', fontWeight: x.id === estId ? 600 : undefined }}>
                  <td className="mono">{x.estimateNo}</td>
                  <td>v{x.versionNo}</td>
                  <td>
                    <Badge tone={statusTone(x.status)}>{ESTIMATE_STATUS_LABEL[x.status]}</Badge>
                  </td>
                  <td className="num">{fmtMoney(x.subtotal)}</td>
                  <td className="num">{fmtNum(x.discountRate * 100)}%</td>
                  <td className="num">{fmtMoney(x.offerAmount)}</td>
                  <td>{x.asOfDate}</td>
                </tr>
              ))}
              {estimates.data?.length === 0 && (
                <tr>
                  <td colSpan={7} className="muted">
                    견적 없음
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          {canCreateEstimate ? (
            <>
              <h3>견적 작성 (대가 산정 — 요율표 기준일 적용, ⚠️ 예시 요율)</h3>
              <form
                className="form"
                onSubmit={(ev) => {
                  ev.preventDefault();
                  createEst.mutate();
                }}
              >
                {F('asOfDate', '기준일', 'date')}
                {F('grossAreaM2', '연면적 (m²)')}
                <Field label="구조">
                  <select value={fee.structure} onChange={(ev) => setFee({ ...fee, structure: ev.target.value })}>
                    <option>RC</option>
                    <option>STEEL</option>
                    <option>SRC</option>
                  </select>
                </Field>
                {F('floors', '지상 층수')}
                {F('basementFloors', '지하 층수')}
                {F('completionYear', '준공 연도')}
                <Field label="사용 중">
                  <select value={fee.inUse ? 'Y' : 'N'} onChange={(ev) => setFee({ ...fee, inUse: ev.target.value === 'Y' })}>
                    <option value="Y">예</option>
                    <option value="N">아니오</option>
                  </select>
                </Field>
                {F('travelQty', '출장 경비(회, 150,000원)')}
                {F('overheadRate', '제경비율 (1.1~1.2)')}
                {F('techFeeRate', '기술료율 (0.2~0.4)')}
                {F('discountPct', '할인율 (%)')}
                <div className="row">
                  <button type="button" onClick={() => calc.mutate()} disabled={calc.isPending}>
                    대가 미리 계산
                  </button>
                  <button className="primary" disabled={createEst.isPending} data-testid="create-estimate">
                    견적 작성
                  </button>
                </div>
              </form>
              {preview && (
                <div className="kpis" style={{ marginTop: 12 }}>
                  <Kpi label="요율 버전" value={preview.ruleVersion} />
                  <Kpi label="직접인건비" value={fmtMoney(preview.directLabor)} />
                  <Kpi label="직접경비" value={fmtMoney(preview.directExpense)} />
                  <Kpi label="제경비" value={fmtMoney(preview.overhead)} />
                  <Kpi label="기술료" value={fmtMoney(preview.techFee)} />
                  <Kpi label="합계(천원 절사)" value={fmtMoney(preview.subtotal)} />
                </div>
              )}
            </>
          ) : (
            <p className="muted">견적 작성은 검토 · 제안 · 협상 단계에서만 가능합니다(SALES · PM).</p>
          )}
        </Card>
      )}

      {e && (
        <Card
          title={
            <>
              견적 {e.estimateNo} v{e.versionNo}{' '}
              <Badge tone={statusTone(e.status)}>
                <span data-testid="estimate-status">{ESTIMATE_STATUS_LABEL[e.status]}</span>
              </Badge>
            </>
          }
          actions={
            <>
              {['DRAFT', 'PENDING_APPROVAL'].includes(e.status) && hasRole('SALES', 'EXEC') && (
                <button className="primary" onClick={() => issue.mutate()} disabled={issue.isPending} data-testid="issue-estimate">
                  발행 (:issue)
                </button>
              )}
              {e.status === 'ISSUED' && hasRole('SALES') && <button onClick={() => act.mutate('send')}>고객 송부</button>}
              {e.status === 'SENT' && hasRole('SALES') && (
                <>
                  <button onClick={() => act.mutate('accept')}>수락 기록</button>
                  <button className="danger" onClick={() => act.mutate('reject')}>
                    거절 기록
                  </button>
                </>
              )}
            </>
          }
        >
          <div className="kpis">
            <Kpi label="용역비 합계" value={fmtMoney(e.subtotal)} />
            <Kpi label="할인율" value={`${fmtNum(e.discountRate * 100)}%`} />
            <Kpi label="제시 금액" value={fmtMoney(e.offerAmount)} />
            <Kpi label="부가세" value={fmtMoney(e.vat)} />
            <Kpi label="유효기한" value={e.validUntil} />
            <Kpi label="할인 한도(발행자)" value={e.discountLimit == null ? '—' : `${fmtNum(e.discountLimit * 100)}%`} />
            <Kpi label="승인자" value={e.approvedBy ? e.approvedBy.slice(-4) : '—'} />
            <Kpi label="요율" value={e.ruleVersion} />
          </div>
          {e.status === 'PENDING_APPROVAL' && (
            <div className="card" style={{ marginTop: 12 }}>
              {e.approvedBy ? (
                <p>임원 승인 완료 — 영업이 다시 발행하면 ISSUED 가 됩니다.</p>
              ) : hasRole('EXEC') ? (
                <div className="row">
                  <strong>할인 승인 (EXEC, If-Match v{e.version})</strong>
                  <input placeholder="의견" value={comment} onChange={(ev) => setComment(ev.target.value)} />
                  <button className="primary" onClick={() => decide.mutate('APPROVE')} data-testid="approve-discount">
                    승인
                  </button>
                  <button className="danger" onClick={() => decide.mutate('REJECT')}>
                    반려
                  </button>
                </div>
              ) : (
                <p className="muted">할인 한도 초과 — 임원(EXEC) 승인 대기 중입니다.</p>
              )}
            </div>
          )}
          <h3>산출 내역</h3>
          <table className="simple">
            <thead>
              <tr>
                <th>#</th>
                <th>구분</th>
                <th>작업</th>
                <th>등급</th>
                <th className="num">인·일</th>
                <th className="num">단가</th>
                <th className="num">금액</th>
              </tr>
            </thead>
            <tbody>
              {e.lines.map((l) => (
                <tr key={l.seq}>
                  <td>{l.seq}</td>
                  <td>{l.lineType}</td>
                  <td>{l.workItem}</td>
                  <td>{l.grade ?? ''}</td>
                  <td className="num">{fmtNum(l.manDays ?? l.quantity)}</td>
                  <td className="num">{fmtMoney(l.unitPrice)}</td>
                  <td className="num">{fmtMoney(l.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}
