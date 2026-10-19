import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  gradeTree, INDICATOR_LABEL, rptApi, type Anchor, type AnchorsDoc, type Block, type GradeTreeNode, type Section,
} from '@/api/rpt';
import { downloadFile } from '@/api/download';
import { ProblemError } from '@/api/http';
import { Badge, Banner, Card, ErrorBox, fmtDateTime, fmtNum, Kpi, Loading } from '@/components/ui';
import { REV_STATUS_LABEL } from './ReportsPage';

/** A4 세로(mm) — 앵커 좌표 단위 */
const PAGE_W = 210;
const PAGE_H = 297;
const SCALE = 1.25; // px/mm

function BlockView({ b, active, onSelect }: { b: Block; active: boolean; onSelect: () => void }) {
  return (
    <div
      className={`block ${b.locked ? 'locked' : ''} ${b.source === 'HUMAN' ? 'human' : ''} ${active ? 'highlight' : ''}`}
      onClick={onSelect}
      data-testid={`block-${b.blockId}`}
      role="button"
      tabIndex={0}
      aria-pressed={active}
    >
      <div className="meta">
        <Badge>{b.kind}</Badge>
        {b.locked && <Badge tone="warn">🔒 잠금(수치)</Badge>}
        <Badge tone={b.source === 'HUMAN' ? 'info' : undefined}>{b.source === 'HUMAN' ? '사람 편집' : '자동 생성'}</Badge>
        <span className="hash" title={b.textHash}>#{b.textHash.slice(0, 10)}</span>
      </div>
      {b.kind === 'TABLE' && b.data?.rows ? (
        <table className="simple">
          <thead><tr>{(b.data.columns ?? []).map((c) => <th key={c}>{c}</th>)}</tr></thead>
          <tbody>{b.data.rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
        </table>
      ) : b.kind === 'IMAGE' ? (
        <div className="muted">🖼 {b.text} <span className="mono" style={{ fontSize: 11 }}>({b.data?.objectKey?.split('/').pop()})</span></div>
      ) : (
        <div style={{ whiteSpace: 'pre-wrap' }}>{b.text}</div>
      )}
    </div>
  );
}

function AnchorPages({ doc, active, onSelect }: { doc: AnchorsDoc; active?: string; onSelect: (blockId: string) => void }) {
  const pages = [...new Set(doc.anchors.map((a) => a.page))].sort((a, b) => a - b);
  return (
    <div className="row" style={{ alignItems: 'flex-start' }}>
      {pages.map((p) => (
        <div key={p}>
          <div className="muted" style={{ fontSize: 12 }}>{p} / {doc.pageCount} 쪽</div>
          <div className="page-thumb" style={{ width: PAGE_W * SCALE, height: PAGE_H * SCALE }} data-testid={`anchor-page-${p}`}>
            {doc.anchors.filter((a) => a.page === p).flatMap((a) =>
              a.rects.map((r, i) => (
                <div
                  key={`${a.blockId}-${i}`}
                  className={`anchor-rect ${active === a.blockId ? 'active' : ''}`}
                  style={{ left: r.x * SCALE, top: r.y * SCALE, width: r.w * SCALE, height: r.h * SCALE }}
                  title={`${a.sectionKey} · ${a.kind}`}
                  onClick={() => onSelect(a.blockId)}
                >
                  {a.kind}
                </div>
              )),
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

function TreeNode({ n }: { n: GradeTreeNode }) {
  const [open, setOpen] = useState(true);
  return (
    <li>
      <div className="node">
        {n.children.length > 0 ? (
          <button onClick={() => setOpen(!open)} aria-label={`${open ? '접기' : '펼치기'} ${n.label}`} style={{ padding: '0 6px' }}>{open ? '−' : '+'}</button>
        ) : <span style={{ width: 22 }} />}
        <strong>{n.label}</strong>
        {n.rating && <Badge tone={n.rating >= 'd' ? 'danger' : n.rating === 'c' ? 'warn' : 'ok'}>{n.rating}</Badge>}
        <span className="muted">점수 {fmtNum(n.score, 3)}{n.weight !== undefined ? ` · 가중치 ${fmtNum(n.weight, 3)} · 부재 ${n.count}개` : ''}</span>
      </div>
      {n.evidence && (
        <table className="simple" style={{ margin: '4px 0 8px 28px', width: 'calc(100% - 28px)' }}>
          <thead><tr><th>지표</th><th className="num">값</th><th>단위</th><th>등급</th><th className="num">구간 상한</th><th>근거 결함</th></tr></thead>
          <tbody>
            {n.evidence.map((e) => (
              <tr key={e.indicator}>
                <td>{INDICATOR_LABEL[e.indicator] ?? e.indicator}</td>
                <td className="num">{fmtNum(e.value, 3)}</td>
                <td>{e.unit}</td>
                <td>{e.rating}</td>
                <td className="num">{fmtNum(e.threshold, 3)}</td>
                <td className="mono">{e.defectIds.join(', ') || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {open && n.children.length > 0 && <ul className="gtree">{n.children.map((c) => <TreeNode key={c.key} n={c} />)}</ul>}
    </li>
  );
}

/** G2 › 보고서 리비전 상세 — 블록 본문(읽기 보기) · 등급 트리(근거) · 앵커(추정 위치) · HWPX · DXF 다운로드 */
export function RevisionDetailPage() {
  const { id = '', revId = '' } = useParams();
  const [active, setActive] = useState<string>();
  const [last, setLast] = useState<string>();
  const rev = useQuery({ queryKey: ['rpt', 'revision', revId], queryFn: () => rptApi.revision(revId) });
  const report = useQuery({ queryKey: ['rpt', 'report', id], queryFn: () => rptApi.report(id), meta: { silent: true } });
  const sections = useQuery({ queryKey: ['rpt', 'sections', revId], queryFn: () => rptApi.sections(revId), meta: { silent: true } });
  const grade = useQuery({ queryKey: ['rpt', 'grade-detail', revId], queryFn: () => rptApi.gradeDetail(revId), meta: { silent: true } });
  const anchors = useQuery({ queryKey: ['rpt', 'anchors', revId], queryFn: () => rptApi.anchors(revId), meta: { silent: true } });
  const tree = useMemo(() => (grade.data ? gradeTree(grade.data) : []), [grade.data]);
  const floors = useMemo(
    () => [...new Map((grade.data?.members ?? []).map((m) => [`${m.buildingCode}/${m.floorCode}`, m])).values()],
    [grade.data],
  );
  const dl = useMutation({
    mutationFn: ({ path, name }: { path: string; name: string }) => downloadFile(path, name),
    onSuccess: (f) => {
      setLast(`${f.filename} · ${f.size.toLocaleString('ko-KR')} bytes`);
      toast.success(`다운로드 — ${f.filename}`);
    },
  });
  const blockAnchor = (blockId: string): Anchor | undefined => anchors.data?.anchors.find((a) => a.blockId === blockId);

  if (rev.isLoading) return <Loading />;
  if (rev.error) return <ErrorBox error={rev.error} />;
  const r = rev.data!;
  const notFound = (e: unknown) => e instanceof ProblemError && e.status === 404;
  return (
    <>
      <p><Link to={`/reports/${id}`}>← 보고서로</Link></p>
      <h1>
        {report.data?.title ?? '보고서'} — Rev.{r.revNo} <Badge tone={r.status === 'PUBLISHED' || r.status === 'DELIVERED' ? 'ok' : 'info'}>
          <span data-testid="rev-detail-status">{REV_STATUS_LABEL[r.status]}</span></Badge>
      </h1>
      {r.status === 'SUPERSEDED' && <Banner tone="warn">이 리비전은 정정본으로 대체되었습니다(읽기 전용).</Banner>}
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="쪽수" value={r.pageCount ?? '—'} />
        <Kpi label="발행" value={fmtDateTime(r.publishedAt)} />
        <Kpi label="최종 등급" value={grade.data?.finalGrade ?? grade.data?.computedGrade ?? '—'} />
        <Kpi label="가중 점수" value={fmtNum(grade.data?.weightedScore, 3)} />
      </div>
      <Card title="파일 다운로드">
        <div className="row">
          <button className="primary" onClick={() => dl.mutate({ path: rptApi.hwpxPath(revId), name: 'report.hwpx' })} disabled={dl.isPending} data-testid="dl-hwpx">
            HWPX 내려받기
          </button>
          {floors.map((m) => (
            <button key={`${m.buildingCode}/${m.floorCode}`} onClick={() => dl.mutate({ path: rptApi.dxfPath(revId, m.buildingCode, m.floorCode), name: `${m.buildingCode}-${m.floorCode}.dxf` })} disabled={dl.isPending} data-testid={`dl-dxf-${m.buildingCode}-${m.floorCode}`}>
              DXF 망도 {m.buildingCode} {m.floorCode}
            </button>
          ))}
        </div>
        <p className="muted" style={{ fontSize: 12 }}>
          HWPX SHA-256 <span className="hash">{r.hwpxSha256 ?? '렌더 전'}</span> · PDF SHA-256 <span className="hash">{r.pdfSha256 ?? '—'}</span>
        </p>
        {last && <p data-testid="dl-result">저장됨: {last}</p>}
        {dl.error && <ErrorBox error={dl.error} />}
      </Card>
      <div className="grid2">
        <Card title="본문 (블록 읽기 보기)">
          {sections.error && <ErrorBox error={sections.error} />}
          <div className="blocks" data-testid="block-view">
            {(sections.data ?? []).map((s: Section) => (
              <section key={s.sectionKey}>
                <h3>{s.title} <span className="muted mono" style={{ fontSize: 11 }}>{s.sectionKey} · v{s.version} · {s.source}</span></h3>
                {s.blocks.map((b) => (
                  <BlockView key={b.blockId} b={b} active={active === b.blockId} onSelect={() => setActive(b.blockId)} />
                ))}
              </section>
            ))}
          </div>
          <p className="muted" style={{ fontSize: 12 }}>편집은 보고서 화면(작성 단계)에서 합니다. 🔒 수치 블록은 수정 · 삭제할 수 없습니다(RPT-422-002).</p>
        </Card>
        <div>
          <Card title={<>앵커 오버레이 <Badge tone="warn">추정 위치(ESTIMATED)</Badge></>}>
            {anchors.error && (notFound(anchors.error) ? <p className="muted">렌더 전이라 앵커가 없습니다.</p> : <ErrorBox error={anchors.error} />)}
            {anchors.data && (
              <>
                <p className="muted" style={{ fontSize: 12 }}>
                  렌더러가 실제 좌표를 주기 전까지는 블록 순서로 추정한 위치(mm)입니다 — QAR 검토 의견을 본문 블록에 붙이는 기준. 상자를 누르면 블록이 강조됩니다.
                  anchors.json SHA-256 <span className="hash">{anchors.data.sha256.slice(0, 16)}…</span>
                </p>
                <AnchorPages doc={anchors.data} active={active} onSelect={setActive} />
                <table className="simple" data-testid="anchor-list" style={{ marginTop: 8 }}>
                  <thead><tr><th>블록</th><th>섹션</th><th>종류</th><th className="num">쪽</th><th>위치(mm)</th></tr></thead>
                  <tbody>
                    {anchors.data.anchors.map((a) => (
                      <tr key={a.blockId} onClick={() => setActive(a.blockId)} style={{ cursor: 'pointer', background: active === a.blockId ? 'var(--surface-2)' : undefined }}>
                        <td className="mono">{a.blockId.slice(-8)}</td><td>{a.sectionKey}</td><td>{a.kind}</td><td className="num">{a.page}</td>
                        <td className="mono" style={{ fontSize: 11 }}>{a.rects.map((x) => `(${x.x},${x.y}) ${x.w}×${x.h}`).join(' ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
            {active && !blockAnchor(active) && anchors.data && <p className="muted">선택한 블록의 앵커가 없습니다.</p>}
          </Card>
          <Card title="등급 트리 (동 → 층 → 부재 · 근거)">
            {grade.error && (notFound(grade.error) ? <p className="muted">등급 산정 전입니다(compose 필요).</p> : <ErrorBox error={grade.error} />)}
            {grade.data && (
              <>
                <p className="muted" style={{ fontSize: 12 }}>
                  규칙 {Object.values(grade.data.ruleSet ?? {}).filter((v, i, a) => a.indexOf(v) === i).join(', ')} · 산정 {grade.data.computedGrade}
                  {grade.data.overridden && ` → 확정 ${grade.data.finalGrade} (사유: ${grade.data.overrideReason})`} · 결과 SHA-256{' '}
                  <span className="hash">{grade.data.resultSha256.slice(0, 16)}…</span>
                </p>
                <ul className="gtree" style={{ paddingLeft: 0 }} data-testid="grade-tree">
                  {tree.map((n) => <TreeNode key={n.key} n={n} />)}
                </ul>
              </>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}
