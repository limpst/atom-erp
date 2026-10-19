import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  BUILDING_STATUS_LABEL, DATA_SOURCE_LABEL, DUE_STATUS_LABEL, dDay, facApi, flattenTree, INSPECTION_TYPE_LABEL,
  type Building, type MergeReport, type SitbeopClass,
} from '@/api/fac';
import { Badge, Banner, Card, ErrorBox, Field, Kpi, Loading, Modal, fmtNum } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { statusTone } from './BuildingsPage';

const daysFromToday = (d: string) => {
  const t = new Date();
  const today = Date.UTC(t.getFullYear(), t.getMonth(), t.getDate());
  return Math.round((Date.parse(d) - today) / 86_400_000);
};

function MergeDialog({ source, onClose }: { source: Building; onClose: () => void }) {
  const qc = useQueryClient();
  const nav = useNavigate();
  const list = useQuery({ queryKey: ['fac', 'buildings', ''], queryFn: () => facApi.list() });
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  const [report, setReport] = useState<MergeReport>();
  const targets = (list.data?.content ?? []).filter((b) => b.id !== source.id && !['MERGED', 'DEMOLISHED'].includes(b.status));
  const merge = useMutation({
    mutationFn: () => facApi.merge(source.id, target, reason),
    onSuccess: (r) => {
      setReport(r);
      toast.success('시설물 병합 완료');
      qc.invalidateQueries({ queryKey: ['fac'] });
    },
  });
  return (
    <Modal
      title="중복 시설물 병합"
      onClose={onClose}
      footer={
        report ? (
          <button className="primary" onClick={() => nav(`/fac/buildings/${report.targetId}`)}>존속 시설물로 이동</button>
        ) : (
          <>
            <button onClick={onClose}>취소</button>
            <button className="danger" disabled={!target || reason.trim().length < 2 || merge.isPending} onClick={() => merge.mutate()} data-testid="merge-confirm">
              병합 실행
            </button>
          </>
        )
      }
    >
      {report ? (
        <div data-testid="merge-report">
          <p>병합되었습니다. 이 시설물은 「병합됨」 상태로 남고(삭제 아님) 이력은 존속 시설물로 옮겨졌습니다.</p>
          <table className="simple">
            <tbody>
              <tr><th>위치 노드</th><td className="num">{report.nodes}</td></tr>
              <tr><th>등급 이력</th><td className="num">{report.grades}</td></tr>
              <tr><th>취소된 도래</th><td className="num">{report.duesCancelled}</td></tr>
              <tr><th>도면 · 보수 · 필지</th><td className="num">{report.drawings} · {report.repairs} · {report.parcels}</td></tr>
            </tbody>
          </table>
        </div>
      ) : (
        <>
          <Banner tone="warn">
            「{source.name}」(병합 대상)의 위치 노드 · 등급 이력 · 도면을 아래 존속 시설물로 옮깁니다. 되돌릴 수 없으며 감사 로그에 남습니다.
          </Banner>
          <div className="form">
            <Field label="존속 시설물">
              <select aria-label="존속 시설물" value={target} onChange={(e) => setTarget(e.target.value)}>
                <option value="">선택</option>
                {targets.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name} {b.dongName} · {b.roadAddress ?? '주소 없음'}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="병합 사유">
              <input aria-label="병합 사유" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
            </Field>
          </div>
        </>
      )}
    </Modal>
  );
}

function ClassificationForm({ building }: { building: Building }) {
  const qc = useQueryClient();
  const [cls, setCls] = useState<SitbeopClass>('CLASS2');
  const [note, setNote] = useState('');
  const save = useMutation({
    mutationFn: () => facApi.classify(building.id, cls, note),
    onSuccess: () => {
      toast.success('법적 분류 확정 — 점검 도래일을 다시 계산했습니다');
      qc.invalidateQueries({ queryKey: ['fac'] });
    },
  });
  return (
    <div className="form">
      <Field label="시특법 종별">
        <select aria-label="시특법 종별" value={cls} onChange={(e) => setCls(e.target.value as SitbeopClass)}>
          <option value="CLASS1">1종</option>
          <option value="CLASS2">2종</option>
          <option value="CLASS3">3종</option>
          <option value="NONE">해당 없음</option>
        </select>
      </Field>
      <Field label="분류 근거">
        <input aria-label="분류 근거" value={note} onChange={(e) => setNote(e.target.value)} placeholder="(합성) 연면적 기준" />
      </Field>
      <button className="primary" disabled={!note.trim() || save.isPending} onClick={() => save.mutate()} data-testid="fac-classify">
        분류 확정
      </button>
    </div>
  );
}

/** 시설물 상세 — 위치 트리(ltree) · 등급 이력(읽기 전용) · 점검 도래 · 병합 */
export function BuildingDetailPage() {
  const { id = '' } = useParams();
  const { hasRole } = useAuth();
  const [tab, setTab] = useState<'tree' | 'grades' | 'dues'>('tree');
  const [merging, setMerging] = useState(false);
  const b = useQuery({ queryKey: ['fac', 'building', id], queryFn: () => facApi.get(id) });
  const tree = useQuery({ queryKey: ['fac', 'tree', id], queryFn: () => facApi.tree(id), meta: { silent: true } });
  const grades = useQuery({ queryKey: ['fac', 'grades', id], queryFn: () => facApi.grades(id), meta: { silent: true } });
  const dues = useQuery({ queryKey: ['fac', 'dues', id], queryFn: () => facApi.dues(id), meta: { silent: true } });
  const flat = useMemo(() => flattenTree(tree.data ?? []), [tree.data]);

  if (b.error) return <ErrorBox error={b.error} />;
  if (!b.data) return <Loading />;
  const x = b.data;
  const latest = grades.data?.[0];
  return (
    <>
      <p><Link to="/fac/buildings">← 시설물 목록</Link></p>
      <h1>
        {x.name} {x.dongName} <Badge tone={statusTone(x.status)}>{BUILDING_STATUS_LABEL[x.status]}</Badge>
      </h1>
      {x.useRestrictionFlag && <Banner tone="danger">안전등급 D 이하 — 사용 제한 권고 대상</Banner>}
      {x.status === 'MERGED' && <Banner tone="info">다른 시설물로 병합된 기록입니다(읽기 전용).</Banner>}
      <Card
        actions={
          hasRole('PM', 'SYSADMIN') && x.status !== 'MERGED' ? (
            <button onClick={() => setMerging(true)} data-testid="fac-merge-open">중복 병합…</button>
          ) : undefined
        }
      >
        <div className="kpis">
          <Kpi label="도로명주소" value={x.roadAddress ?? '—'} />
          <Kpi label="PNU" value={<span className="mono" style={{ fontSize: 13 }}>{x.pnu ?? '—'}</span>} />
          <Kpi label="출처" value={DATA_SOURCE_LABEL[x.dataSource]} />
          <Kpi label="용도 · 구조" value={`${x.mainPurposeNm ?? '—'} · ${x.structureNm ?? '—'}`} />
          <Kpi label="연면적" value={x.grossFloorArea ? `${fmtNum(x.grossFloorArea)}㎡` : '—'} />
          <Kpi label="층수" value={`지상 ${x.floorsAbove ?? '—'} / 지하 ${x.floorsBelow ?? '—'}`} />
          <Kpi label="사용승인" value={x.approvalDate ?? '—'} />
          <Kpi label="최근 안전등급" value={latest ? `${latest.grade} (${latest.performedOn})` : '—'} tone={latest && 'DE'.includes(latest.grade) ? 'danger' : undefined} />
        </div>
      </Card>
      {hasRole('PM') && ['DRAFT', 'VERIFIED', 'CLASSIFIED', 'RECLASSIFY_REQUIRED', 'ACTIVE'].includes(x.status) && (
        <Card title="법적 분류(시특법) 확정 — 확정하면 점검 도래일을 계산합니다">
          <ClassificationForm building={x} />
        </Card>
      )}
      <Card>
        <div className="tabs" role="tablist">
          <button role="tab" className={tab === 'tree' ? 'active' : ''} onClick={() => setTab('tree')}>위치 트리 ({flat.length})</button>
          <button role="tab" className={tab === 'grades' ? 'active' : ''} onClick={() => setTab('grades')}>등급 이력 ({grades.data?.length ?? 0})</button>
          <button role="tab" className={tab === 'dues' ? 'active' : ''} onClick={() => setTab('dues')}>점검 도래 ({dues.data?.length ?? 0})</button>
        </div>
        {tab === 'tree' && (
          <>
            {tree.error && <ErrorBox error={tree.error} />}
            <ul className="tree" data-testid="fac-tree">
              {flat.map(({ node, depth }) => (
                <li key={node.id} style={{ paddingLeft: depth * 20 }}>
                  <Badge tone={node.nodeType === 'FLOOR' ? 'info' : undefined}>{node.nodeType === 'FLOOR' ? '층' : node.nodeType === 'ZONE' ? '구역' : '부재'}</Badge>
                  <strong>{node.code}</strong>
                  {node.name && <span>{node.name}</span>}
                  {node.gridRef && <span className="muted">그리드 {node.gridRef}</span>}
                  <span className="path" title="ltree 경로">{node.path}</span>
                </li>
              ))}
              {flat.length === 0 && <li className="muted">위치 노드가 없습니다</li>}
            </ul>
          </>
        )}
        {tab === 'grades' && (
          <>
            <p className="muted">등급 이력은 보고서 발행으로만 쌓이는 추가 전용(append-only) 기록입니다. 화면에서 고칠 수 없습니다.</p>
            {grades.error && <ErrorBox error={grades.error} />}
            <table className="simple" data-testid="fac-grades">
              <thead><tr><th>수행일</th><th>등급</th><th>점검 종류</th><th>출처</th><th>정정</th></tr></thead>
              <tbody>
                {(grades.data ?? []).map((g) => (
                  <tr key={g.id}>
                    <td>{g.performedOn}</td>
                    <td><Badge tone={'DE'.includes(g.grade) ? 'danger' : g.grade === 'C' ? 'warn' : 'ok'}>{g.grade}</Badge></td>
                    <td>{INSPECTION_TYPE_LABEL[g.inspectionType] ?? g.inspectionType}</td>
                    <td>{g.source === 'REPORT' ? '보고서 발행' : g.source}</td>
                    <td>{g.correctionOf ? '정정본' : ''}</td>
                  </tr>
                ))}
                {grades.data?.length === 0 && <tr><td colSpan={5} className="muted">등급 이력 없음</td></tr>}
              </tbody>
            </table>
          </>
        )}
        {tab === 'dues' && (
          <>
            {dues.error && <ErrorBox error={dues.error} />}
            <table className="simple" data-testid="fac-dues">
              <thead><tr><th>점검 종류</th><th>기준일</th><th>도래일</th><th>D-day</th><th>상태</th><th>근거 규칙</th></tr></thead>
              <tbody>
                {(dues.data ?? []).map((d) => {
                  const dd = dDay(daysFromToday(d.dueDate));
                  return (
                    <tr key={d.id}>
                      <td>{INSPECTION_TYPE_LABEL[d.inspectionType] ?? d.inspectionType}</td>
                      <td>{d.baseDate ?? '사용승인일'}</td>
                      <td>{d.dueDate}</td>
                      <td><Badge tone={dd.tone}>{dd.label}</Badge></td>
                      <td>{DUE_STATUS_LABEL[d.status] ?? d.status}</td>
                      <td className="mono" style={{ fontSize: 11 }}>{String(d.calcTrace?.ruleCode ?? '')}</td>
                    </tr>
                  );
                })}
                {dues.data?.length === 0 && <tr><td colSpan={6} className="muted">도래 없음 — 법적 분류를 확정하면 계산됩니다</td></tr>}
              </tbody>
            </table>
          </>
        )}
      </Card>
      {merging && <MergeDialog source={x} onClose={() => setMerging(false)} />}
    </>
  );
}
