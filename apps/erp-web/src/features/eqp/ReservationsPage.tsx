import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import {
  ACTIVE_RESERVATION, EQUIPMENT_STATUS_LABEL, eqpApi, overlaps, parseQrToken, QR_TOKEN_RE, RESERVATION_STATUS_LABEL,
  type Alternative, type CheckoutResult, type Reservation, type ReturnResult, type ScanResult,
} from '@/api/eqp';
import { newIdempotencyKey, ProblemError } from '@/api/http';
import { toastError } from '@/app/errors';
import { Badge, Banner, Card, ErrorBox, Field } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { CalChip } from './EquipmentPage';

const DAYS = 21;
export function addDays(iso: string, n: number) {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
export const localToday = () => addDays(new Date().toLocaleDateString('sv-SE'), 0);

/** 예약 충돌 안내(서버 409 EQP-409-002 · 409-009 · 409-011) — 토스트 대신 화면 안에 대안 장비와 함께 */
interface Blocked { code: string; title: string; detail?: string; alternatives: Alternative[] }

function ReservationForm({ equipment, projects, existing, preset }: {
  equipment: { id: string; code: string; typeName: string }[];
  projects: { id: string; projectNo: string; name: string }[];
  existing: Reservation[];
  preset: { equipmentId: string; from: string; to: string };
}) {
  const qc = useQueryClient();
  const [f, setF] = useState({ equipmentId: preset.equipmentId, projectId: projects[0]?.id ?? '', from: preset.from, to: preset.to });
  const [blocked, setBlocked] = useState<Blocked>();
  // 달력에서 다른 칸을 고르면 폼을 그 값으로
  const [last, setLast] = useState(preset);
  if (last !== preset) {
    setLast(preset);
    setF((x) => ({ ...x, ...preset }));
    setBlocked(undefined);
  }
  const projectId = f.projectId || projects[0]?.id || '';
  const local = existing.filter(
    (r) => r.equipment_id === f.equipmentId && ACTIVE_RESERVATION.includes(r.status) && f.from && f.to && overlaps(f.from, f.to, r.from_date, r.to_date),
  );
  const reserve = useMutation({
    mutationFn: () => eqpApi.reserve({ ...f, projectId }),
    meta: { silent: true },
    onSuccess: (r) => {
      toast.success(`예약 요청 — ${r.from_date} ~ ${r.to_date} (승인 대기)`);
      setBlocked(undefined);
      qc.invalidateQueries({ queryKey: ['eqp', 'reservations'] });
    },
    onError: (e) => {
      if (e instanceof ProblemError && ['EQP-409-002', 'EQP-409-009', 'EQP-409-011', 'EQP-409-001'].includes(e.code ?? '')) {
        setBlocked({ code: e.code!, title: e.problem.title ?? '예약 불가', detail: e.problem.detail, alternatives: (e.problem.alternatives as Alternative[]) ?? [] });
      } else toastError(e);
    },
  });
  return (
    <Card title="장비 예약">
      <div className="form">
        <Field label="장비">
          <select aria-label="예약 장비" value={f.equipmentId} onChange={(e) => setF({ ...f, equipmentId: e.target.value })}>
            <option value="">선택</option>
            {equipment.map((e) => <option key={e.id} value={e.id}>{e.code} · {e.typeName}</option>)}
          </select>
        </Field>
        <Field label="프로젝트">
          <select aria-label="예약 프로젝트" value={projectId} onChange={(e) => setF({ ...f, projectId: e.target.value })}>
            {projects.map((p) => <option key={p.id} value={p.id}>{p.projectNo} {p.name}</option>)}
          </select>
        </Field>
        <Field label="시작일">
          <input aria-label="예약 시작일" type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} />
        </Field>
        <Field label="종료일(포함)">
          <input aria-label="예약 종료일" type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} />
        </Field>
        <button className="primary" disabled={!f.equipmentId || !projectId || !f.from || !f.to || f.to < f.from || reserve.isPending} onClick={() => reserve.mutate()} data-testid="eqp-reserve">
          예약 요청
        </button>
      </div>
      {local.length > 0 && !blocked && (
        <Banner tone="warn">
          이 기간에 이미 예약이 있습니다: {local.map((r) => `${r.from_date}~${r.to_date}(${RESERVATION_STATUS_LABEL[r.status]})`).join(', ')} — 서버가 겹침을 거절합니다.
        </Banner>
      )}
      {blocked && (
        <Banner tone="danger" testId="eqp-blocked">
          <strong>[{blocked.code}] {blocked.title}</strong>
          {blocked.detail && <div>{blocked.detail}</div>}
          {blocked.alternatives.length > 0 ? (
            <div style={{ marginTop: 6 }}>
              같은 종류의 대안 장비:{' '}
              {blocked.alternatives.map((a) => (
                <button key={a.equipmentId} style={{ marginRight: 6 }} onClick={() => { setF({ ...f, equipmentId: a.equipmentId }); setBlocked(undefined); }}>
                  {a.code}{a.validUntil ? ` (교정 ~${a.validUntil})` : ''}로 바꾸기
                </button>
              ))}
            </div>
          ) : (
            <div className="muted">같은 기간에 쓸 수 있는 같은 종류 장비가 없습니다. 기간을 바꿔 보세요.</div>
          )}
        </Banner>
      )}
    </Card>
  );
}

function CheckoutPanel({ projects }: { projects: { id: string; projectNo: string; name: string }[] }) {
  const qc = useQueryClient();
  const [raw, setRaw] = useState('');
  const [projectId, setProjectId] = useState('');
  const [anvil, setAnvil] = useState('');
  const [abnormal, setAbnormal] = useState(false);
  const [symptom, setSymptom] = useState('');
  const [scan, setScan] = useState<ScanResult>();
  const [out, setOut] = useState<CheckoutResult>();
  const [ret, setRet] = useState<ReturnResult>();
  const [opId, setOpId] = useState(newIdempotencyKey);
  const token = parseQrToken(raw);
  const valid = QR_TOKEN_RE.test(token);
  const pid = projectId || projects[0]?.id || '';
  const refresh = () => qc.invalidateQueries({ queryKey: ['eqp'] });
  const doScan = useMutation({ mutationFn: () => eqpApi.scan(token), onSuccess: (s) => setScan(s) });
  const checkout = useMutation({
    mutationFn: () => eqpApi.checkout({
      clientOpId: opId, qrToken: token, projectId: pid,
      preCheck: { exterior: 'OK', ...(scan?.typeCode === 'NDT-RH' ? { anvilValue: Number(anvil) } : {}) },
    }),
    onSuccess: (r) => {
      setOut(r);
      setOpId(newIdempotencyKey());
      if (r.result === 'CONFLICT_RECORDED') toast.warning('이미 반출 중인 장비 — 충돌로 기록했습니다(관리자 확인 필요)');
      else toast.success(`반출 완료 — ${scan?.code ?? ''}${r.reservationId ? ' (예약 연결)' : ''}`);
      doScan.mutate();
      refresh();
    },
  });
  const giveBack = useMutation({
    mutationFn: () => eqpApi.return((out?.checkoutId ?? scan?.activeCheckout?.id)!, { abnormal, symptom: abnormal ? symptom : undefined }),
    onSuccess: (r) => {
      setRet(r);
      toast.success(`반납 완료 — 사용 ${r.usageDays}일, 장비 상태 ${EQUIPMENT_STATUS_LABEL[r.equipmentStatus]}`);
      setScan(undefined);
      setOut(undefined);
      refresh();
    },
  });
  const activeId = out?.checkoutId ?? scan?.activeCheckout?.id;
  return (
    <Card title="QR 반출 · 반납">
      <div className="form">
        <Field label="QR 토큰(라벨 URL 또는 토큰)">
          <input aria-label="QR 토큰" value={raw} onChange={(e) => { setRaw(e.target.value); setScan(undefined); }} placeholder="eqp1.EQ-…" className="mono" />
        </Field>
        <button onClick={() => { setOut(undefined); setRet(undefined); doScan.mutate(); }} disabled={!valid || doScan.isPending} data-testid="eqp-scan">스캔 조회</button>
      </div>
      {raw && !valid && <p className="muted">토큰 형식: eqp1.&lt;장비코드&gt;.&lt;발급시각&gt;.&lt;서명 16자&gt;</p>}
      {scan && (
        <div style={{ marginTop: 12 }} data-testid="eqp-scan-result">
          <p>
            <strong className="mono">{scan.code}</strong> {scan.typeName} · <Badge tone={scan.status === 'AVAILABLE' ? 'ok' : scan.status === 'CHECKED_OUT' ? 'info' : 'warn'}>{EQUIPMENT_STATUS_LABEL[scan.status]}</Badge>
            {scan.activeCheckout && <span className="muted"> · 반출 중(반출 {scan.activeCheckout.out_at.slice(0, 16)})</span>}
          </p>
          {scan.actions.includes('CHECKOUT') && !activeId && (
            <div className="form">
              <Field label="사용 프로젝트">
                <select aria-label="반출 프로젝트" value={pid} onChange={(e) => setProjectId(e.target.value)}>
                  {projects.map((p) => <option key={p.id} value={p.id}>{p.projectNo} {p.name}</option>)}
                </select>
              </Field>
              {scan.typeCode === 'NDT-RH' && (
                <Field label="테스트 앤빌 값(80±2)">
                  <input aria-label="앤빌 값" type="number" step="0.1" value={anvil} onChange={(e) => setAnvil(e.target.value)} />
                </Field>
              )}
              <button className="primary" onClick={() => checkout.mutate()} disabled={!pid || checkout.isPending || (scan.typeCode === 'NDT-RH' && !anvil)} data-testid="eqp-checkout">
                반출(사전 점검 이상 없음)
              </button>
            </div>
          )}
          {activeId && (
            <div className="form">
              <label className="row" style={{ gap: 6 }}>
                <input type="checkbox" checked={abnormal} onChange={(e) => setAbnormal(e.target.checked)} aria-label="이상 있음" /> 반납 점검 이상 있음
              </label>
              {abnormal && (
                <Field label="증상">
                  <input aria-label="증상" value={symptom} onChange={(e) => setSymptom(e.target.value)} />
                </Field>
              )}
              <button className="primary" onClick={() => giveBack.mutate()} disabled={giveBack.isPending} data-testid="eqp-return">
                반납
              </button>
            </div>
          )}
        </div>
      )}
      {out && (
        <p data-testid="eqp-checkout-result">
          반출 {out.result} · 예약 {out.reservationId ? '연결됨' : '없음'}
          {out.calibration?.validUntil && ` · 교정 유효 ~${out.calibration.validUntil}(D-${out.calibration.daysLeft})`}
        </p>
      )}
      {ret && <p data-testid="eqp-return-result">반납 · 사용 {ret.usageDays}일 · {EQUIPMENT_STATUS_LABEL[ret.equipmentStatus]}</p>}
      <p className="muted">현장 앱이 QR 을 읽는 대신 토큰을 붙여 넣습니다. 같은 반출 요청을 다시 보내면 서버가 같은 결과(REPLAYED)를 돌려줍니다.</p>
    </Card>
  );
}

/** G5 › 장비 예약 · 반출 — 예약 달력(겹침 표시) · 예약 승인 · QR 반출/반납 */
export function ReservationsPage() {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const today = localToday();
  const days = useMemo(() => Array.from({ length: DAYS }, (_, i) => addDays(today, i)), [today]);
  const cal = useQuery({ queryKey: ['eqp', 'cal'], queryFn: () => eqpApi.calibrationStatus() });
  const res = useQuery({ queryKey: ['eqp', 'reservations'], queryFn: () => eqpApi.reservations() });
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects(), meta: { silent: true } });
  const [preset, setPreset] = useState({ equipmentId: '', from: today, to: today });
  const equipment = (cal.data ?? []).map((c) => ({ id: c.equipmentId, code: c.code, typeName: c.typeName, cal: c }));
  const projectList = projects.data ?? [];
  const projectNo = new Map(projectList.map((p) => [p.id, p.projectNo]));
  const resRows = res.data ?? [];

  const act = useMutation({
    mutationFn: ({ r, cmd }: { r: Reservation; cmd: 'approve' | 'cancel' }) => (cmd === 'approve' ? eqpApi.approve(r.id) : eqpApi.cancel(r.id)),
    onSuccess: (r) => {
      toast.success(`예약 ${RESERVATION_STATUS_LABEL[r.status]} — ${r.from_date} ~ ${r.to_date}`);
      qc.invalidateQueries({ queryKey: ['eqp', 'reservations'] });
    },
  });

  const cellOf = (eqId: string, day: string) =>
    resRows.find((r) => r.equipment_id === eqId && ACTIVE_RESERVATION.includes(r.status) && r.from_date <= day && day <= r.to_date);

  const pick = (eqId: string, day: string) => {
    setPreset((p) => (p.equipmentId === eqId && day >= p.from && p.from === p.to ? { equipmentId: eqId, from: p.from, to: day } : { equipmentId: eqId, from: day, to: day }));
  };

  return (
    <>
      <h1>장비 예약 · 반출</h1>
      <Card title={`예약 달력 (${today} 부터 ${DAYS}일)`}>
        {(cal.error || res.error) && <ErrorBox error={cal.error ?? res.error} />}
        <div className="cal" style={{ ['--days' as string]: DAYS }} data-testid="eqp-calendar">
          <div className="head">장비</div>
          {days.map((d) => (
            <div key={d} className={`head ${d === today ? 'today' : ''}`}>{d.slice(8)}<br />{'일월화수목금토'[new Date(`${d}T00:00:00`).getDay()]}</div>
          ))}
          {equipment.map((e) => (
            <div key={e.id} style={{ display: 'contents' }}>
              <div className="eq" title={`${e.code} ${e.typeName}`}>
                {e.code} <CalChip s={e.cal} />
              </div>
              {days.map((d) => {
                const r = cellOf(e.id, d);
                const sel = preset.equipmentId === e.id && preset.from <= d && d <= preset.to;
                return (
                  <div
                    key={d}
                    className={`cell ${r ? `res-${r.status}` : ''} ${sel ? 'sel' : ''}`}
                    title={r ? `${projectNo.get(r.project_id) ?? ''} ${RESERVATION_STATUS_LABEL[r.status]} ${r.from_date}~${r.to_date}` : '클릭: 예약 시작/종료 선택'}
                    onClick={() => pick(e.id, d)}
                    role="gridcell"
                    aria-label={`${e.code} ${d}${r ? ` ${RESERVATION_STATUS_LABEL[r.status]}` : ''}`}
                  />
                );
              })}
            </div>
          ))}
        </div>
        <div className="legend">
          <span><i style={{ background: 'color-mix(in srgb, var(--warn) 30%, transparent)' }} />승인 대기</span>
          <span><i style={{ background: 'color-mix(in srgb, var(--primary) 35%, transparent)' }} />확정</span>
          <span><i style={{ background: 'color-mix(in srgb, var(--danger) 35%, transparent)' }} />사용 중</span>
          <span>칸을 두 번 눌러 기간 선택(같은 장비)</span>
        </div>
      </Card>
      {hasRole('PM', 'ENG') && (
        <ReservationForm equipment={equipment} projects={projectList} existing={resRows} preset={preset} />
      )}
      <Card title="예약 목록">
        <table className="simple" data-testid="eqp-reservations">
          <thead><tr><th>장비</th><th>프로젝트</th><th>기간</th><th>상태</th><th /></tr></thead>
          <tbody>
            {resRows.map((r) => {
              const code = equipment.find((e) => e.id === r.equipment_id)?.code ?? r.equipment_id.slice(0, 8);
              return (
                <tr key={r.id}>
                  <td className="mono">{code}</td>
                  <td>{projectNo.get(r.project_id) ?? r.project_id.slice(0, 8)}</td>
                  <td>{r.from_date} ~ {r.to_date}</td>
                  <td><Badge tone={r.status === 'CONFIRMED' ? 'info' : r.status === 'REQUESTED' ? 'warn' : r.status === 'IN_USE' ? 'danger' : undefined}>{RESERVATION_STATUS_LABEL[r.status]}</Badge></td>
                  <td className="row" style={{ gap: 6 }}>
                    {/* EQP-01: 본인 신청 예약은 승인 불가(서버 403 EQP-403-003) · 승인자는 그 프로젝트 PM 또는 ADMIN */}
                    {r.status === 'REQUESTED' && hasRole('PM', 'ADMIN') && r.requested_by !== session?.actorId && (
                      <button onClick={() => act.mutate({ r, cmd: 'approve' })} aria-label={`승인 ${code} ${r.from_date}`}>승인</button>
                    )}
                    {['REQUESTED', 'CONFIRMED'].includes(r.status) && (r.requested_by === session?.actorId || hasRole('PM')) && (
                      <button onClick={() => act.mutate({ r, cmd: 'cancel' })} aria-label={`취소 ${code} ${r.from_date}`}>취소</button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      {hasRole('ENG') ? (
        <CheckoutPanel projects={projectList} />
      ) : (
        <p className="muted">QR 반출 · 반납은 참여기술자(ENG)가 현장에서 합니다.</p>
      )}
    </>
  );
}
