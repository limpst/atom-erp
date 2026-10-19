import { useMemo, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { hrqApi, type EmployeeRow } from '@/api/hrq';
import { useAuth, type Role } from '@/auth/auth';

/** 직원 대장(HRQ) — 이름 표시 · 선택 상자용 */
export function useEmployees() {
  const q = useQuery({ queryKey: ['hrq', 'employees'], queryFn: () => hrqApi.employees(), meta: { silent: true } });
  const byId = useMemo(() => new Map((q.data ?? []).map((e) => [e.id, e])), [q.data]);
  const label = (id: string | null | undefined) => {
    if (!id) return '—';
    const e = byId.get(id);
    return e ? `${e.empNo} ${e.name}` : id.slice(0, 8);
  };
  return { employees: (q.data ?? []).filter((e) => e.status !== 'RESIGNED'), label, error: q.error };
}

export function EmployeeSelect({
  employees, value, onChange, exclude, label = '직원', testId,
}: {
  employees: EmployeeRow[];
  value: string;
  onChange: (id: string) => void;
  exclude?: Set<string>;
  label?: string;
  testId?: string;
}) {
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} data-testid={testId}>
      <option value="">선택</option>
      {employees
        .filter((e) => !exclude?.has(e.id))
        .map((e) => (
          <option key={e.id} value={e.id}>
            {e.empNo} {e.name}
          </option>
        ))}
    </select>
  );
}

/** 역할이 없으면 화면 대신 안내(서버도 403 으로 막는다) */
export function RoleGate({ roles, children, what }: { roles: Role[]; children: ReactNode; what: string }) {
  const { hasRole } = useAuth();
  if (!hasRole(...roles)) {
    return <div className="empty">{what} 화면은 {roles.join(' · ')} 역할만 사용할 수 있습니다.</div>;
  }
  return <>{children}</>;
}

export const todayIso = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
