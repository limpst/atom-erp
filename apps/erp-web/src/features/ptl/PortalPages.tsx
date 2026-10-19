import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { INSPECTION_TYPE_LABEL } from '@/api/fac';
import { REPORT_TYPE_LABEL, type ReportType } from '@/api/rpt';
import { newIdempotencyKey, ProblemError } from '@/api/http';
import {
  KIND_LABEL, ptlApi, REASON_LABEL, saveBlob, SEVERITY_TONE, type PortalReport, type ServiceRequestKind,
} from '@/api/ptl';
import { toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field, fmtDateTime, Kpi } from '@/components/ui';

const dDayLabel = (d: number) => (d < 0 ? `D+${-d} 경과` : d === 0 ? 'D-Day' : `D-${d}`);
const gradeTone = (g: string | null) => (!g ? undefined : 'DE'.includes(g) ? 'danger' : g === 'C' ? 'warn' : 'ok');

/** 내 시설물 · 점검 일정 */
export function PortalHomePage() {
  const buildings = useQuery({ queryKey: ['ptl', 'buildings'], queryFn: ptlApi.buildings });
  const schedule = useQuery({ queryKey: ['ptl', 'schedule'], queryFn: ptlApi.schedule });
  const overdue = (schedule.data ?? []).filter((s) => s.dDay < 0).length;
  return (
    <>
      <h1>내 시설물</h1>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="시설물" value={`${buildings.data?.length ?? 0}동`} />
        <Kpi label="예정 점검" value={`${schedule.data?.length ?? 0}건`} />
        <Kpi label="기한 지난 점검" value={`${overdue}건`} tone={overdue ? 'danger' : undefined} />
      </div>
      {buildings.error && <ErrorBox error={buildings.error} />}
      <Card title="시설물">
        <table className="simple" data-testid="ptl-buildings">
          <thead><tr><th>건물</th><th>주소</th><th>안전등급</th><th>다음 점검</th></tr></thead>
          <tbody>
            {(buildings.data ?? []).map((b) => (
              <tr key={b.id}>
                <td>{b.name} {b.dongName}</td>
                <td>{b.roadAddress ?? '—'}</td>
                <td>{b.currentGrade ? <Badge tone={gradeTone(b.currentGrade)}>{b.currentGrade}등급</Badge> : <span className="muted">미평가</span>} {b.gradePerformedOn && <span className="muted">({b.gradePerformedOn})</span>}</td>
                <td>
                  {b.nextInspection ? (
                    <>{INSPECTION_TYPE_LABEL[b.nextInspection.inspectionType] ?? b.nextInspection.inspectionType} {b.nextInspection.dueDate}{' '}
                      <Badge tone={SEVERITY_TONE[b.nextInspection.severity]}>{dDayLabel(b.nextInspection.dDay)}</Badge></>
                  ) : '—'}
                </td>
              </tr>
            ))}
            {buildings.data?.length === 0 && <tr><td colSpan={4} className="muted">등록된 시설물이 없습니다</td></tr>}
          </tbody>
        </table>
      </Card>
      <Card title="점검 일정">
        <table className="simple" data-testid="ptl-schedule">
          <thead><tr><th>도래일</th><th>D-day</th><th>건물</th><th>점검 종류</th></tr></thead>
          <tbody>
            {(schedule.data ?? []).map((s, i) => (
              <tr key={`${s.buildingId}-${s.inspectionType}-${i}`}>
                <td>{s.dueDate}</td>
                <td><Badge tone={SEVERITY_TONE[s.severity]}>{dDayLabel(s.dDay)}</Badge></td>
                <td>{s.buildingName}</td>
                <td>{INSPECTION_TYPE_LABEL[s.inspectionType] ?? s.inspectionType}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

function ReportRow({ r }: { r: PortalReport }) {
  const [last, setLast] = useState<{ downloadId: string | null; sha256: string | null; filename: string }>();
  const dl = useMutation({
    mutationFn: () => ptlApi.download(r.id),
    meta: { silent: true },
    onSuccess: (d) => {
      saveBlob(d.blob, d.filename);
      setLast(d);
      toast.success(`워터마크 PDF 내려받기 — download-id ${d.downloadId?.slice(0, 8)}`);
    },
    onError: (e) => {
      if (e instanceof ProblemError && e.code === 'PTL-409-001') toast.error('정정본으로 대체된 보고서는 내려받을 수 없습니다');
      else toastError(e);
    },
  });
  return (
    <Card>
      {r.status === 'SUPERSEDED' && (
        <Banner tone="warn" testId="ptl-superseded-banner">
          {r.banner ?? '이 보고서는 정정본으로 대체되었습니다.'} {r.supersededReason && <>— 사유: {r.supersededReason}</>}
        </Banner>
      )}
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div>
          <strong>{r.title}</strong> <span className="muted">Rev.{r.revNo}</span>{' '}
          <Badge tone={r.status === 'PUBLISHED' ? 'ok' : 'warn'}>{r.status === 'PUBLISHED' ? '최신본' : r.status === 'SUPERSEDED' ? '대체됨' : '회수됨'}</Badge>
          <div className="muted">
            {REPORT_TYPE_LABEL[r.reportType as ReportType] ?? r.reportType} · 점검일 {r.inspectionDate} · 납품 {fmtDateTime(r.deliveredAt)} · {r.pageCount ?? '—'}쪽
          </div>
        </div>
        <div className="row">
          {r.safetyGrade && <Badge tone={gradeTone(r.safetyGrade)}>안전등급 {r.safetyGrade}</Badge>}
          <button className="primary" disabled={!r.downloadable || dl.isPending} onClick={() => dl.mutate()} aria-label={`내려받기 ${r.title} Rev.${r.revNo}`}>
            {r.downloadable ? 'PDF 내려받기(워터마크)' : '열람만 가능'}
          </button>
        </div>
      </div>
      {last && (
        <p className="muted mono" style={{ fontSize: 11 }} data-testid="ptl-download-info">
          {last.filename} · download-id {last.downloadId} · SHA-256 {last.sha256?.slice(0, 16)}…
        </p>
      )}
    </Card>
  );
}

/** 납품 보고서 — 대체본 배너 · 워터마크 다운로드 */
export function PortalReportsPage() {
  const list = useQuery({ queryKey: ['ptl', 'reports'], queryFn: ptlApi.reports });
  return (
    <>
      <h1>납품 보고서</h1>
      <p className="muted">내려받는 PDF 에는 고객사 · 내려받은 시각 · 다운로드 번호가 워터마크로 찍히고 기록이 남습니다. 재배포하지 마세요.</p>
      {list.error && <ErrorBox error={list.error} />}
      <div data-testid="ptl-reports">
        {(list.data ?? []).map((r) => <ReportRow key={r.id} r={r} />)}
        {list.data?.length === 0 && <div className="empty">납품된 보고서가 없습니다</div>}
      </div>
    </>
  );
}

/** 서비스 요청 */
export function PortalRequestsPage() {
  const qc = useQueryClient();
  const buildings = useQuery({ queryKey: ['ptl', 'buildings'], queryFn: ptlApi.buildings });
  const list = useQuery({ queryKey: ['ptl', 'requests'], queryFn: ptlApi.serviceRequests });
  const [f, setF] = useState({ kind: 'QUOTE' as ServiceRequestKind, facilityId: '', reasonCode: '', description: '', contactName: '' });
  const [key, setKey] = useState(newIdempotencyKey);
  const submit = useMutation({
    mutationFn: () => ptlApi.submitRequest({
      kind: f.kind, facilityId: f.facilityId || undefined, reasonCode: f.kind === 'EMERGENCY' ? f.reasonCode : undefined,
      description: f.description, contactName: f.contactName || undefined,
    }, key),
    onSuccess: (r) => {
      toast.success(`${KIND_LABEL[r.kind]} 요청 접수${r.slaDueAt ? ` — 응답 기한 ${fmtDateTime(r.slaDueAt)}` : ''}`);
      setKey(newIdempotencyKey());
      setF({ ...f, description: '' });
      qc.invalidateQueries({ queryKey: ['ptl', 'requests'] });
    },
  });
  const valid = f.description.trim().length > 0 && (f.kind !== 'EMERGENCY' || !!f.reasonCode);
  return (
    <>
      <h1>서비스 요청</h1>
      <Card title="새 요청">
        <div className="form">
          <Field label="요청 종류">
            <select aria-label="요청 종류" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as ServiceRequestKind })}>
              {(Object.keys(KIND_LABEL) as ServiceRequestKind[]).map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
            </select>
          </Field>
          <Field label="대상 시설물">
            <select aria-label="대상 시설물" value={f.facilityId} onChange={(e) => setF({ ...f, facilityId: e.target.value })}>
              <option value="">선택 안 함</option>
              {(buildings.data ?? []).map((b) => <option key={b.id} value={b.id}>{b.name} {b.dongName}</option>)}
            </select>
          </Field>
          {f.kind === 'EMERGENCY' && (
            <Field label="긴급 사유">
              <select aria-label="긴급 사유" value={f.reasonCode} onChange={(e) => setF({ ...f, reasonCode: e.target.value })}>
                <option value="">선택</option>
                {Object.entries(REASON_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </Field>
          )}
          <Field label="담당자">
            <input aria-label="담당자" value={f.contactName} maxLength={50} onChange={(e) => setF({ ...f, contactName: e.target.value })} />
          </Field>
        </div>
        <Field label="요청 내용">
          <textarea aria-label="요청 내용" value={f.description} maxLength={4000} onChange={(e) => setF({ ...f, description: e.target.value })} />
        </Field>
        {f.kind === 'EMERGENCY' && <Banner tone="danger">긴급 점검 요청은 2시간 안에 담당자가 연락드립니다.</Banner>}
        <button className="primary" style={{ marginTop: 8 }} disabled={!valid || submit.isPending} onClick={() => submit.mutate()} data-testid="ptl-submit">
          요청 보내기
        </button>
      </Card>
      <Card title="요청 내역">
        {list.error && <ErrorBox error={list.error} />}
        <table className="simple" data-testid="ptl-requests">
          <thead><tr><th>접수</th><th>종류</th><th>내용</th><th>상태</th></tr></thead>
          <tbody>
            {(list.data ?? []).map((r) => (
              <tr key={r.id}>
                <td>{fmtDateTime(r.createdAt)}</td>
                <td>{KIND_LABEL[r.kind]}{r.reasonCode ? ` · ${REASON_LABEL[r.reasonCode] ?? r.reasonCode}` : ''}</td>
                <td>{r.description}</td>
                <td><Badge tone="info">{r.status === 'SUBMITTED' ? '접수' : r.status}</Badge></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

/** 알림 */
export function PortalNotificationsPage() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['ptl', 'notifications'], queryFn: () => ptlApi.notifications() });
  const read = useMutation({
    mutationFn: (id: string) => ptlApi.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ptl', 'notifications'] }),
  });
  return (
    <>
      <h1>알림</h1>
      {list.error && <ErrorBox error={list.error} />}
      <Card>
        <ul className="list-plain" data-testid="ptl-notifications">
          {(list.data ?? []).map((n) => (
            <li key={n.id} className="row" style={{ justifyContent: 'space-between' }}>
              <div>
                {!n.readAt && <Badge tone="info">새 알림</Badge>} <strong>{n.title}</strong>
                <div className="muted">{n.body}</div>
                <div className="muted" style={{ fontSize: 12 }}>{fmtDateTime(n.createdAt)}</div>
              </div>
              {!n.readAt && <button onClick={() => read.mutate(n.id)} aria-label={`읽음 ${n.title}`}>읽음</button>}
            </li>
          ))}
          {list.data?.length === 0 && <li className="muted">알림이 없습니다</li>}
        </ul>
      </Card>
    </>
  );
}
