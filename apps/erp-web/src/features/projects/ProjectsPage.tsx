import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColDef } from 'ag-grid-community';
import { toast } from 'sonner';
import { coreApi, PROJECT_STATUS_LABEL, type Project } from '@/api/core';
import { DataGrid } from '@/components/DataGrid';
import { Card, ErrorBox, Field, fmtMoney } from '@/components/ui';
import { useAuth } from '@/auth/auth';

export function ProjectsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { hasRole } = useAuth();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => coreApi.projects() });
  const canReadClients = hasRole('EXEC', 'SALES', 'PM', 'ADMIN', 'FIN', 'AUDITOR');
  const clients = useQuery({ queryKey: ['clients'], queryFn: coreApi.clients, enabled: canReadClients });
  const clientName = useMemo(() => new Map((clients.data ?? []).map((c) => [c.id, c.name])), [clients.data]);

  const cols = useMemo<ColDef<Project>[]>(
    () => [
      { field: 'projectNo', headerName: '프로젝트 번호', width: 150 },
      { field: 'name', headerName: '프로젝트명', flex: 2 },
      { headerName: '발주처', flex: 1, valueGetter: (p) => clientName.get(p.data!.clientId) ?? p.data!.clientId.slice(0, 8) },
      { field: 'serviceType', headerName: 'Service type', width: 140 },
      { field: 'status', headerName: '상태', width: 110, valueFormatter: (p) => PROJECT_STATUS_LABEL[p.value as Project['status']] ?? p.value },
      { field: 'contractAmount', headerName: '계약금액', width: 150, type: 'rightAligned', valueFormatter: (p) => fmtMoney(p.value) },
      { field: 'startDate', headerName: '시작', width: 115 },
      { field: 'endDate', headerName: '종료', width: 115 },
    ],
    [clientName],
  );

  const [form, setForm] = useState({ name: '', clientId: '', serviceType: 'PRECISE_DIAG' });
  const create = useMutation({
    mutationFn: () => coreApi.createProject(form),
    onSuccess: (p) => {
      toast.success(`프로젝트 ${p.projectNo} 생성 — 투입 지정 전에는 PM 도 조회할 수 없습니다(EXEC/ADMIN 이 RESP 지정).`);
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  return (
    <>
      <h1>프로젝트</h1>
      <Card title="프로젝트 목록" actions={<span className="muted">{projects.data?.length ?? 0}건 · 투입 범위(PROJECT scope)로 필터됨</span>}>
        {projects.error ? (
          <ErrorBox error={projects.error} />
        ) : (
          <DataGrid<Project>
            testId="projects-grid"
            rowData={projects.data}
            columnDefs={cols}
            loading={projects.isLoading}
            onRowClicked={(e) => e.data && nav(`/projects/${e.data.id}`)}
            rowStyle={{ cursor: 'pointer' }}
            height={360}
          />
        )}
      </Card>
      {hasRole('SALES', 'PM') && (
        <Card title="새 프로젝트 (core:project:create)">
          <form
            className="form"
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate();
            }}
          >
            <Field label="프로젝트명">
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
            </Field>
            <Field label="발주처">
              <select value={form.clientId} onChange={(e) => setForm({ ...form, clientId: e.target.value })} required>
                <option value="">선택</option>
                {clients.data?.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.clientNo} {c.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Service type">
              <select value={form.serviceType} onChange={(e) => setForm({ ...form, serviceType: e.target.value })}>
                <option>PRECISE_DIAG</option>
                <option>PRECISE_INSP</option>
                <option>SEISMIC_EVAL</option>
              </select>
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
