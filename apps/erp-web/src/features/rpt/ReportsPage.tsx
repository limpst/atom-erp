import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import { REPORT_TYPE_LABEL, rptApi, type Report, type ReportType, type RevisionStatus } from '@/api/rpt';
import { newIdempotencyKey } from '@/api/http';
import { useAuth } from '@/auth/auth';
import { DataGrid } from '@/components/DataGrid';
import { Card, ErrorBox, Field } from '@/components/ui';

export const REV_STATUS_LABEL: Record<RevisionStatus, string> = {
  DRAFT: '초안', SNAPSHOT_FROZEN: '스냅샷 동결', COMPOSING: '작성 중', IN_REVIEW: 'QAR 검토 중', APPROVED: '검토 승인',
  SIGNING: '서명', PUBLISHED: '발행', DELIVERED: '납품', SUPERSEDED: '대체됨',
};

export function ReportsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const [params, setParams] = useSearchParams();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects() });
  const projectId = params.get('projectId') ?? '';
  useEffect(() => {
    if (!projectId && projects.data?.length) setParams({ projectId: projects.data[0].id }, { replace: true });
  }, [projects.data, projectId, setParams]);
  const reports = useQuery({ queryKey: ['rpt', 'reports', projectId], queryFn: () => rptApi.reports(projectId), enabled: !!projectId });

  const cols = useMemo<ColDef<Report>[]>(
    () => [
      { field: 'title', headerName: '보고서', flex: 2 },
      { field: 'reportType', headerName: '유형', width: 140, valueFormatter: (p) => REPORT_TYPE_LABEL[p.value as ReportType] },
      { field: 'currentRev', headerName: 'Rev', width: 70 },
      { field: 'status', headerName: '상태', width: 130, valueFormatter: (p) => REV_STATUS_LABEL[p.value as RevisionStatus] },
      { field: 'inspectionDate', headerName: '점검일', width: 115 },
      { headerName: '템플릿', width: 120, valueGetter: (p) => p.data!.templateVersion?.semver },
    ],
    [],
  );

  const [form, setForm] = useState({ title: '(합성) 정밀안전점검 보고서', reportType: 'RT_PRC' as ReportType, inspectionDate: new Date().toISOString().slice(0, 10) });
  const create = useMutation({
    // facilityId: FAC 미등록 합성 시설물 ID(서버는 FAC 가 모르는 ID 를 허용)
    mutationFn: () => rptApi.create({ projectId, facilityId: newIdempotencyKey(), ...form }),
    onSuccess: (r) => {
      toast.success('보고서 생성 — Rev.1 초안');
      qc.invalidateQueries({ queryKey: ['rpt'] });
      nav(`/reports/${r.id}`);
    },
  });

  return (
    <>
      <h1>보고서 · QAR 검토</h1>
      <Card>
        <Field label="프로젝트">
          <select value={projectId} onChange={(e) => setParams({ projectId: e.target.value })} aria-label="프로젝트 선택">
            {projects.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.projectNo} {p.name}
              </option>
            ))}
          </select>
        </Field>
        {projects.data?.length === 0 && <p className="muted">투입된 프로젝트가 없습니다.</p>}
      </Card>
      <Card title="보고서 목록">
        {reports.error ? (
          <ErrorBox error={reports.error} />
        ) : (
          <DataGrid<Report> testId="reports-grid" rowData={reports.data} columnDefs={cols} height={260} onRowClicked={(e) => e.data && nav(`/reports/${e.data.id}`)} />
        )}
      </Card>
      {hasRole('PM', 'ENG') && projectId && (
        <Card title="새 보고서 (rpt:report:create)">
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate();
            }}
          >
            <Field label="제목">
              <input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} required />
            </Field>
            <Field label="유형">
              <select value={form.reportType} onChange={(e) => setForm({ ...form, reportType: e.target.value as ReportType })}>
                {Object.entries(REPORT_TYPE_LABEL).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v} ({k})
                  </option>
                ))}
              </select>
            </Field>
            <Field label="점검일">
              <input type="date" value={form.inspectionDate} onChange={(e) => setForm({ ...form, inspectionDate: e.target.value })} />
            </Field>
            <button className="primary" disabled={create.isPending}>
              생성
            </button>
          </form>
        </Card>
      )}
    </>
  );
}
