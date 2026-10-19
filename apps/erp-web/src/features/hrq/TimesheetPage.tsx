import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  hrqApi, isoWeekOf, shiftWeek, TS_STATUS_LABEL, weekDates, WORK_TYPE_LABEL, type TimesheetRow, type WorkType,
} from '@/api/hrq';
import { coreApi } from '@/api/core';
import { newIdempotencyKey } from '@/api/http';
import { useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { Badge, Card, ErrorBox, Field, fmtNum } from '@/components/ui';

const DAYS = ['월', '화', '수', '목', '금', '토', '일'];
const devName = new Map(DEV_USERS.map((u) => [u.id, u.name]));
const tone = (s: TimesheetRow['status']) =>
  s === 'APPROVED' || s === 'LOCKED' ? 'ok' : s === 'SUBMITTED' ? 'info' : s === 'SUPERSEDED' ? 'warn' : undefined;

interface Line { projectId: string; workType: WorkType; hours: string[] }

function WeekNav({ week, setWeek }: { week: string; setWeek: (w: string) => void }) {
  const d = weekDates(week);
  return (
    <div className="row">
      <button onClick={() => setWeek(shiftWeek(week, -1))} aria-label="이전 주">
        ◀
      </button>
      <strong data-testid="iso-week">{week}</strong>
      <span className="muted">
        {d[0]} ~ {d[6]}
      </span>
      <button onClick={() => setWeek(shiftWeek(week, 1))} aria-label="다음 주">
        ▶
      </button>
      <button onClick={() => setWeek(isoWeekOf(new Date()))}>이번 주</button>
    </div>
  );
}

/** 정정(리비전) — Idempotency-Key 필수: 대화상자를 열 때 키 하나를 만들어 재시도에도 같은 키 */
function CorrectDialog({ row, onDone }: { row: TimesheetRow; onDone: () => void }) {
  const { hasRole } = useAuth();
  const [hours, setHours] = useState(String(row.hours));
  const [reason, setReason] = useState('');
  const [adjustment, setAdjustment] = useState(false);
  const [key] = useState(newIdempotencyKey);
  const revisions = useQuery({ queryKey: ['hrq', 'rev', row.timesheetKey], queryFn: () => hrqApi.revisions(row.timesheetKey) });
  const correct = useMutation({
    mutationFn: () => hrqApi.correct(row.id, { hours: Number(hours), reason, adjustment: adjustment || undefined }, key),
    onSuccess: (r) => {
      toast.success(`정정 완료 — Rev.${r.revisionNo} (posting ${r.postingPeriod})`);
      onDone();
    },
  });
  return (
    <div className="card" style={{ marginTop: 8 }}>
      <h3>
        정정 {row.workDate} · Rev.{row.revisionNo} → Rev.{row.revisionNo + 1}
      </h3>
      <div className="form">
        <Field label="시간">
          <input type="number" step="0.5" min="0.5" max="24" value={hours} onChange={(e) => setHours(e.target.value)} />
        </Field>
        <Field label="정정 사유 (필수)">
          <input value={reason} onChange={(e) => setReason(e.target.value)} data-testid="correct-reason" />
        </Field>
        {hasRole('ADMIN') && (
          <label className="row">
            <input type="checkbox" checked={adjustment} onChange={(e) => setAdjustment(e.target.checked)} /> 마감월 조정
            (adjustment)
          </label>
        )}
        <button className="primary" disabled={!reason || correct.isPending} onClick={() => correct.mutate()}>
          정정 저장
        </button>
        <button onClick={onDone}>닫기</button>
      </div>
      <h3>리비전 이력</h3>
      <table className="simple">
        <thead>
          <tr>
            <th>Rev</th>
            <th>상태</th>
            <th className="num">시간</th>
            <th>사유</th>
            <th>Posting</th>
          </tr>
        </thead>
        <tbody>
          {revisions.data?.map((r) => (
            <tr key={r.id}>
              <td>{r.revisionNo}</td>
              <td>
                <Badge tone={tone(r.status)}>{TS_STATUS_LABEL[r.status]}</Badge>
              </td>
              <td className="num">{fmtNum(r.hours)}</td>
              <td>{r.correctionReason ?? ''}</td>
              <td>{r.postingPeriod}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EntriesTable({
  rows, projectName, selectable, selected, setSelected, canCorrect,
}: {
  rows: TimesheetRow[];
  projectName: (id: string) => string;
  selectable?: boolean;
  selected?: Set<string>;
  setSelected?: (s: Set<string>) => void;
  canCorrect: boolean;
}) {
  const qc = useQueryClient();
  const [correcting, setCorrecting] = useState<TimesheetRow>();
  return (
    <>
      <table className="simple" data-testid="ts-entries">
        <thead>
          <tr>
            {selectable && <th />}
            <th>일자</th>
            <th>프로젝트</th>
            <th>작업</th>
            <th className="num">시간</th>
            <th>상태</th>
            <th>Rev</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} style={r.status === 'SUPERSEDED' ? { opacity: 0.55 } : undefined}>
              {selectable && (
                <td>
                  {r.status === 'SUBMITTED' && (
                    <input
                      type="checkbox"
                      aria-label={`승인 선택 ${r.workDate}`}
                      checked={selected!.has(r.id)}
                      onChange={(e) => {
                        const s = new Set(selected);
                        if (e.target.checked) s.add(r.id);
                        else s.delete(r.id);
                        setSelected!(s);
                      }}
                    />
                  )}
                </td>
              )}
              <td>{r.workDate}</td>
              <td>{projectName(r.projectId)}</td>
              <td>{WORK_TYPE_LABEL[r.workType]}</td>
              <td className="num">{fmtNum(r.hours)}</td>
              <td>
                <Badge tone={tone(r.status)}>{TS_STATUS_LABEL[r.status]}</Badge>
              </td>
              <td>{r.revisionNo}</td>
              <td>
                {canCorrect && (r.status === 'APPROVED' || r.status === 'LOCKED') && (
                  <button onClick={() => setCorrecting(r)}>정정</button>
                )}
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={8} className="muted">
                항목 없음
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {correcting && (
        <CorrectDialog
          row={correcting}
          onDone={() => {
            setCorrecting(undefined);
            qc.invalidateQueries({ queryKey: ['hrq'] });
          }}
        />
      )}
    </>
  );
}

function MyWeek({ week, projectName, projects }: { week: string; projectName: (id: string) => string; projects: { id: string; label: string }[] }) {
  const { session } = useAuth();
  const qc = useQueryClient();
  const empId = session!.actorId;
  const rows = useQuery({ queryKey: ['hrq', 'week', empId, week], queryFn: () => hrqApi.getWeek(empId, week) });
  const dates = weekDates(week);
  const [lines, setLines] = useState<Line[]>([]);

  // 열린(DRAFT/SUBMITTED) 항목 → 편집 행으로
  useEffect(() => {
    if (!rows.data) return;
    const open = rows.data.filter((r) => r.status === 'DRAFT' || r.status === 'SUBMITTED');
    const byKey = new Map<string, Line>();
    for (const r of open) {
      const k = `${r.projectId}|${r.workType}`;
      const l = byKey.get(k) ?? { projectId: r.projectId, workType: r.workType, hours: Array(7).fill('') };
      const i = dates.indexOf(r.workDate);
      if (i >= 0) l.hours[i] = String(Number(r.hours));
      byKey.set(k, l);
    }
    const ls = [...byKey.values()];
    setLines(ls.length ? ls : [{ projectId: projects[0]?.id ?? '', workType: 'FIELD', hours: Array(7).fill('') }]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows.data, week]);

  const save = useMutation({
    mutationFn: (submit: boolean) =>
      hrqApi.putWeek(empId, week, {
        submit,
        entries: lines.flatMap((l) =>
          l.hours
            .map((h, i) => ({ projectId: l.projectId, workDate: dates[i], workType: l.workType, hours: Number(h) }))
            .filter((x) => x.projectId && x.hours > 0),
        ),
      }),
    onSuccess: (_r, submit) => {
      toast.success(submit ? '주간 타임시트 제출' : '임시저장');
      qc.invalidateQueries({ queryKey: ['hrq'] });
    },
  });
  const total = lines.reduce((s, l) => s + l.hours.reduce((a, h) => a + (Number(h) || 0), 0), 0);
  const closed = (rows.data ?? []).filter((r) => r.status !== 'DRAFT' && r.status !== 'SUBMITTED');

  return (
    <>
      <Card title="주간 입력 (열린 항목 = 임시저장 · 제출)">
        {rows.error && <ErrorBox error={rows.error} />}
        <table className="simple" data-testid="ts-week">
          <thead>
            <tr>
              <th>프로젝트</th>
              <th>작업</th>
              {dates.map((d, i) => (
                <th key={d} className="num">
                  {DAYS[i]} {d.slice(5)}
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.map((l, li) => (
              <tr key={li}>
                <td>
                  <select
                    aria-label="프로젝트"
                    value={l.projectId}
                    onChange={(e) => setLines(lines.map((x, j) => (j === li ? { ...x, projectId: e.target.value } : x)))}
                  >
                    <option value="">선택</option>
                    {projects.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.label}
                      </option>
                    ))}
                  </select>
                </td>
                <td>
                  <select
                    aria-label="작업 유형"
                    value={l.workType}
                    onChange={(e) => setLines(lines.map((x, j) => (j === li ? { ...x, workType: e.target.value as WorkType } : x)))}
                  >
                    {Object.entries(WORK_TYPE_LABEL).map(([k, v]) => (
                      <option key={k} value={k}>
                        {v}
                      </option>
                    ))}
                  </select>
                </td>
                {l.hours.map((h, i) => (
                  <td key={i}>
                    <input
                      aria-label={`${DAYS[i]} 시간`}
                      style={{ width: 56 }}
                      type="number"
                      min="0"
                      max="24"
                      step="0.5"
                      value={h}
                      onChange={(e) =>
                        setLines(lines.map((x, j) => (j === li ? { ...x, hours: x.hours.map((y, k) => (k === i ? e.target.value : y)) } : x)))
                      }
                    />
                  </td>
                ))}
                <td>
                  <button onClick={() => setLines(lines.filter((_, j) => j !== li))} aria-label="행 삭제">
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row" style={{ marginTop: 8 }}>
          <button onClick={() => setLines([...lines, { projectId: projects[0]?.id ?? '', workType: 'FIELD', hours: Array(7).fill('') }])}>
            + 행 추가
          </button>
          <span className="muted">합계 {fmtNum(total)}시간</span>
          <span style={{ flex: 1 }} />
          <button onClick={() => save.mutate(false)} disabled={save.isPending}>
            임시저장
          </button>
          <button className="primary" onClick={() => save.mutate(true)} disabled={save.isPending} data-testid="ts-submit">
            제출
          </button>
        </div>
        <p className="muted">PUT 은 열린 항목 전체를 교체합니다. 승인 · 마감된 항목은 정정(리비전)으로만 고칩니다.</p>
      </Card>
      <Card title="승인 · 마감 · 정정된 항목">
        <EntriesTable rows={closed} projectName={projectName} canCorrect />
      </Card>
    </>
  );
}

function ApprovalView({ week, projectName }: { week: string; projectName: (id: string) => string }) {
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const employees = useQuery({ queryKey: ['hrq', 'employees'], queryFn: hrqApi.employees });
  const [empId, setEmpId] = useState<string>();
  useEffect(() => {
    if (!empId && employees.data?.length) {
      setEmpId(employees.data.find((e) => e.name === '정참여')?.id ?? employees.data[0].id);
    }
  }, [employees.data, empId]);
  const rows = useQuery({
    queryKey: ['hrq', 'week', empId, week],
    queryFn: () => hrqApi.getWeek(empId!, week),
    enabled: !!empId,
  });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const approve = useMutation({
    mutationFn: () => hrqApi.approve([...selected]),
    onSuccess: (r) => {
      toast.success(`${r.approved}건 승인 — 원가(CST) 인건비로 전기됩니다`);
      setSelected(new Set());
      qc.invalidateQueries({ queryKey: ['hrq'] });
    },
  });
  const submitted = (rows.data ?? []).filter((r) => r.status === 'SUBMITTED');
  return (
    <Card
      title="승인 · 정정 (PM · ADMIN)"
      actions={
        hasRole('PM') && (
          <>
            <button onClick={() => setSelected(new Set(submitted.map((r) => r.id)))} disabled={!submitted.length}>
              제출분 전체 선택
            </button>
            <button className="primary" disabled={!selected.size || approve.isPending} onClick={() => approve.mutate()} data-testid="ts-approve">
              선택 승인 ({selected.size})
            </button>
          </>
        )
      }
    >
      <div className="form" style={{ marginBottom: 8 }}>
        <Field label="직원">
          <select value={empId ?? ''} onChange={(e) => setEmpId(e.target.value)} aria-label="직원 선택">
            {employees.data?.map((e) => (
              <option key={e.id} value={e.id}>
                {e.empNo} {e.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {employees.error && <ErrorBox error={employees.error} />}
      {rows.error && <ErrorBox error={rows.error} />}
      <EntriesTable
        rows={rows.data ?? []}
        projectName={projectName}
        selectable={hasRole('PM')}
        selected={selected}
        setSelected={setSelected}
        canCorrect={hasRole('PM', 'ADMIN')}
      />
    </Card>
  );
}

export function TimesheetPage() {
  const { hasRole, session } = useAuth();
  const [week, setWeek] = useState(() => shiftWeek(isoWeekOf(new Date()), -1));
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects(), meta: { silent: true } });
  const pmap = useMemo(() => new Map((projects.data ?? []).map((p) => [p.id, `${p.projectNo} ${p.name}`])), [projects.data]);
  const projectName = (id: string) => pmap.get(id) ?? id.slice(0, 8);
  const writer = hasRole('ENG', 'LABELER');
  const approver = hasRole('PM', 'ADMIN', 'EXEC');
  return (
    <>
      <h1>타임시트 {session && <span className="muted">— {devName.get(session.actorId) ?? session.name}</span>}</h1>
      <Card>
        <WeekNav week={week} setWeek={setWeek} />
      </Card>
      {writer && (
        <MyWeek week={week} projectName={projectName} projects={(projects.data ?? []).map((p) => ({ id: p.id, label: `${p.projectNo} ${p.name}` }))} />
      )}
      {approver && <ApprovalView week={week} projectName={projectName} />}
      {!writer && !approver && <div className="empty">타임시트 입력은 ENG · LABELER, 승인은 PM 역할입니다.</div>}
    </>
  );
}
