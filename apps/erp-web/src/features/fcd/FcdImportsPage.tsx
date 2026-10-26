import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import {
  BATCH_STATUS_LABEL, fcdApi, safeMasked, type DedupCandidate, type ImportBatchRow, type Txn,
} from '@/api/fcd';
import { downloadCsv, downloadFile } from '@/api/download';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Badge, Banner, Card, ErrorBox, fmtDateTime, fmtMoney, fmtNum, Kpi } from '@/components/ui';

const BATCH_TONE: Record<string, 'ok' | 'warn' | 'danger' | 'info' | undefined> = {
  VALIDATED: 'info', COMMITTED: 'ok', NEEDS_MAPPING: 'warn', FAILED: 'danger', DISCARDED: undefined,
  ROLLED_BACK: 'warn', PARTIALLY_ROLLED_BACK: 'warn',
};
const FEATURE_LABEL: Record<string, string> = { time: '시간', amount: '금액', merchant: '가맹점', approval: '승인번호' };

function counts(b: ImportBatchRow): string {
  try {
    return Object.entries(JSON.parse(b.counts) as Record<string, number>).map(([k, v]) => `${k} ${v}`).join(' · ');
  } catch {
    return b.counts;
  }
}

/** CSV 몇 줄 미리보기(오류 리포트 — 수식처럼 보이는 원문이 ' 로 무력화됐는지 눈으로 확인) */
async function previewCsv(blob: Blob, lines = 6): Promise<string[]> {
  const text = await blob.text();
  return text.replace(/^﻿/, '').split(/\r?\n/).filter(Boolean).slice(0, lines);
}

function DedupQueue({ txns }: { txns: Map<string, Txn> }) {
  const qc = useQueryClient();
  const { hasRole, session } = useAuth();
  const cands = useQuery({ queryKey: ['fcd', 'dedup'], queryFn: fcdApi.dedupCandidates, enabled: hasRole('FIN') });
  const decide = useMutation({
    mutationFn: ({ c, d }: { c: DedupCandidate; d: 'MERGE' | 'SEPARATE' }) => fcdApi.decideDedup(c.id, d),
    onSuccess: (r) => {
      toast.success(r.decision === 'MERGE' ? '병합 — 중복 거래 제거 · 원천 이동 · 재채점' : '별개 거래로 유지');
      qc.invalidateQueries({ queryKey: ['fcd'] });
    },
  });
  if (!hasRole('FIN')) return <div className="empty">중복 확인 큐는 재무(FIN)만 결정합니다(fcd:dedup:decide).</div>;
  const side = (id: string) => {
    const t = txns.get(id);
    return t ? (
      <>
        <div>{t.approved_at?.slice(0, 16).replace('T', ' ')} · <strong>{t.merchant_name}</strong></div>
        <div className="muted">{fmtMoney(t.amount_krw)} · {safeMasked(t.masked_no)} · 승인 {t.approval_no ?? '없음'} · {t.status}</div>
      </>
    ) : <span className="mono muted">{id.slice(0, 8)}</span>;
  };
  return (
    <>
      <Banner tone="info">
        채널 간(승인 CSV ↔ 매입 엑셀) 퍼지 점수 0.75~0.92 는 자동 병합하지 않고 사람이 확인합니다. 병합하면 새 거래가 제거되고 원천이 기존 거래로
        옮겨집니다(재채점). 별개 유지는 두 거래를 그대로 둡니다. 조사 중 케이스나 정산 라인이 걸린 거래는 병합할 수 없습니다(FCD-409-007).
      </Banner>
      {cands.error && <ErrorBox error={cands.error} />}
      <table className="simple" data-testid="dedup-queue">
        <thead><tr><th>기존 거래</th><th>새로 들어온 거래</th><th className="num">점수</th><th>특징 점수</th><th /></tr></thead>
        <tbody>
          {(cands.data ?? []).map((c) => {
            let feats: Record<string, number> = {};
            try {
              feats = JSON.parse(c.features);
            } catch {
              /* 표시만 생략 */
            }
            // FCD-11 직무 분리: 본인 카드 거래는 다른 담당자가 결정(서버도 403 FCD-403-004)
            const own = [c.txn_id, c.new_txn_id].some((id) => {
              const e = txns.get(id)?.employee_id;
              return !!e && e === session?.actorId;
            });
            return (
              <tr key={c.id}>
                <td>{side(c.txn_id)}</td>
                <td>{side(c.new_txn_id)}</td>
                <td className="num"><Badge tone="warn">{fmtNum(c.score, 3)}</Badge></td>
                <td style={{ fontSize: 12 }}>
                  {Object.entries(feats).map(([k, v]) => `${FEATURE_LABEL[k] ?? k} ${typeof v === 'number' ? v.toFixed(2) : String(v)}`).join(' · ')}
                </td>
                <td>
                  <div className="row" style={{ flexWrap: 'nowrap' }}>
                    <button className="primary" disabled={decide.isPending || own} onClick={() => decide.mutate({ c, d: 'MERGE' })} aria-label={`병합 ${c.id.slice(0, 8)}`}>병합</button>
                    <button disabled={decide.isPending || own} onClick={() => decide.mutate({ c, d: 'SEPARATE' })} aria-label={`별개 유지 ${c.id.slice(0, 8)}`}>별개 유지</button>
                  </div>
                  {own && <div className="muted" style={{ fontSize: 12 }} data-testid={`dedup-sod-${c.id}`}>본인 카드 거래 — 다른 담당자가 결정(직무 분리)</div>}
                </td>
              </tr>
            );
          })}
          {cands.data?.length === 0 && <tr><td colSpan={5} className="muted">확인할 중복 후보가 없습니다</td></tr>}
        </tbody>
      </table>
    </>
  );
}

/** G3 › 법인카드 가져오기 — 배치 목록 · 행 오류 리포트(CSV, 수식 주입 무력화) · 확정/폐기 · 채널 간 중복 확인 큐 */
export function FcdImportsPage() {
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [tab, setTab] = useState<'batches' | 'dedup'>('batches');
  const [preview, setPreview] = useState<{ filename: string; size: number; lines: string[] }>();
  const canWrite = hasRole('FIN', 'ADMIN');
  const batches = useQuery({ queryKey: ['fcd', 'imports'], queryFn: fcdApi.imports });
  const txns = useQuery({ queryKey: ['fcd', 'txns', 'all'], queryFn: () => fcdApi.transactions(), meta: { silent: true }, enabled: hasRole('FIN') });
  const dedup = useQuery({ queryKey: ['fcd', 'dedup'], queryFn: fcdApi.dedupCandidates, enabled: hasRole('FIN'), meta: { silent: true } });
  const txnMap = useMemo(() => new Map((txns.data ?? []).map((t) => [t.id, t])), [txns.data]);
  const refresh = () => qc.invalidateQueries({ queryKey: ['fcd'] });

  const upload = useMutation({
    mutationFn: (f: File) => fcdApi.uploadStatement(f),
    onSuccess: (b) => {
      toast.success(`업로드 — ${b.filename}: ${b.status} · 행 ${b.rows_total} · 오류 ${b.rows_error}`);
      refresh();
    },
  });
  const commit = useMutation({
    mutationFn: (id: string) => fcdApi.commitImport(id),
    onSuccess: () => (toast.success('확정 — 거래 반영 · 자동 채점'), refresh()),
  });
  const discard = useMutation({
    mutationFn: (id: string) => fcdApi.discardImport(id),
    onSuccess: () => (toast.success('폐기'), refresh()),
  });
  const errors = useMutation({
    mutationFn: (b: ImportBatchRow) => downloadFile(`/fcd/imports/${b.id}/errors.csv`, `errors-${b.id}.csv`, 'text/csv'),
    onSuccess: async (f) => {
      setPreview({ filename: f.filename, size: f.size, lines: await previewCsv(f.blob) });
      toast.success(`오류 리포트 저장 — ${f.filename} (${f.size} bytes)`);
    },
  });

  const rows = batches.data ?? [];
  const cols = useMemo<ColDef<ImportBatchRow>[]>(
    () => [
      { field: 'uploaded_at', headerName: '업로드', width: 160, valueFormatter: (p) => fmtDateTime(p.value) },
      { field: 'filename', headerName: '파일', flex: 1, minWidth: 200 },
      { field: 'channel', headerName: '채널', width: 80 },
      { field: 'profile_code', headerName: '매핑 프로파일', width: 210 },
      {
        field: 'status', headerName: '상태', width: 160, valueFormatter: (p) => BATCH_STATUS_LABEL[p.value] ?? p.value,
        cellRenderer: (p: { data: ImportBatchRow }) => <Badge tone={BATCH_TONE[p.data.status]}>{BATCH_STATUS_LABEL[p.data.status] ?? p.data.status}</Badge>,
      },
      { field: 'rows_total', headerName: '행', width: 70, type: 'numericColumn' },
      {
        field: 'rows_error', headerName: '오류', width: 80, type: 'numericColumn',
        cellRenderer: (p: { data: ImportBatchRow }) => (p.data.rows_error ? <Badge tone="danger">{p.data.rows_error}</Badge> : '0'),
      },
      { headerName: '분류', width: 170, valueGetter: (p) => counts(p.data!) },
      {
        headerName: '', width: 250, sortable: false,
        cellRenderer: (p: { data: ImportBatchRow }) => (
          <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
            {p.data.rows_error > 0 && (
              <button onClick={() => errors.mutate(p.data)} aria-label={`오류 리포트 ${p.data.filename}`}>오류 CSV</button>
            )}
            {canWrite && p.data.status === 'VALIDATED' && (
              <>
                <button className="primary" onClick={() => commit.mutate(p.data.id)} aria-label={`확정 ${p.data.filename}`}>확정</button>
                <button onClick={() => discard.mutate(p.data.id)} aria-label={`폐기 ${p.data.filename}`}>폐기</button>
              </>
            )}
          </span>
        ),
      },
    ],
    [canWrite, errors, commit, discard],
  );

  return (
    <>
      <h1>법인카드 가져오기 · 중복 확인</h1>
      <div className="kpis" style={{ marginBottom: 16 }}>
        <Kpi label="배치" value={`${rows.length}건`} />
        <Kpi label="확정 전" value={`${rows.filter((b) => b.status === 'VALIDATED').length}건`} />
        <Kpi label="행 오류" value={`${rows.reduce((a, b) => a + b.rows_error, 0)}행`} tone={rows.some((b) => b.rows_error) ? 'warn' : undefined} />
        <Kpi label="중복 확인 대기" value={<span data-testid="dedup-count">{dedup.data?.length ?? '—'}건</span>} tone={dedup.data?.length ? 'warn' : undefined} />
      </div>
      <Card>
        <div className="tabs" role="tablist">
          <button role="tab" className={tab === 'batches' ? 'active' : ''} onClick={() => setTab('batches')}>가져오기 배치</button>
          <button role="tab" className={tab === 'dedup' ? 'active' : ''} onClick={() => setTab('dedup')}>중복 확인 큐 ({dedup.data?.length ?? 0})</button>
        </div>
        {tab === 'batches' ? (
          <>
            <div className="row" style={{ marginBottom: 8 }}>
              {canWrite && (
                <label className="btn">
                  명세서 업로드(CSV · XLSX · XLS)
                  <input
                    type="file" accept=".csv,.xlsx,.xls,.html" style={{ display: 'none' }} aria-label="명세서 파일"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (f) upload.mutate(f);
                      e.target.value = '';
                    }}
                  />
                </label>
              )}
              <button
                onClick={() => downloadCsv('fcd-import-batches.csv', ['업로드', '파일', '채널', '프로파일', '상태', '행', '오류'],
                  rows.map((b) => [b.uploaded_at, b.filename, b.channel, b.profile_code, b.status, b.rows_total, b.rows_error]))}
              >
                목록 CSV 내보내기
              </button>
              <span className="muted" style={{ fontSize: 12 }}>
                CSV 는 수식 주입 방지 — = + - @ 로 시작하는 칸은 앞에 ' 를 붙여 엑셀이 수식으로 실행하지 않습니다.
              </span>
            </div>
            {batches.error && <ErrorBox error={batches.error} />}
            <DataGrid<ImportBatchRow> testId="import-grid" rowData={rows} columnDefs={cols} height={320} getRowId={(p) => p.data.id} />
            {preview && (
              <div className="card" style={{ marginTop: 12 }} data-testid="error-csv-preview">
                <strong>{preview.filename}</strong> <span className="muted">({preview.size} bytes · 앞 {preview.lines.length}줄)</span>
                <pre className="mono" style={{ whiteSpace: 'pre-wrap', margin: '6px 0 0' }}>{preview.lines.join('\n')}</pre>
              </div>
            )}
          </>
        ) : (
          <DedupQueue txns={txnMap} />
        )}
      </Card>
    </>
  );
}
