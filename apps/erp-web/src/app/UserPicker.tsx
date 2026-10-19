import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ROLE_LABELS, useAuth } from '@/auth/auth';
import { DEV_USERS, resolveClientOrgId } from '@/auth/devUsers';
import { etagStore } from '@/api/http';
import { useThemeContext } from './ThemeContext';

/** 상단 사용자 선택(dev: 헤더 인증) · OIDC 로그아웃. 고객(CUSTOMER)으로 바꾸면 /portal, 직원으로 바꾸면 / 로 간다. */
export function UserPicker() {
  const { mode, session, switchDevUser, logout } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  if (mode === 'oidc') {
    return (
      <div className="row">
        <span>{session?.name}</span>
        <span className="muted">{session?.roles.map((r) => ROLE_LABELS[r]).join(' · ')}</span>
        <button onClick={logout}>로그아웃</button>
      </div>
    );
  }
  return (
    <div className="row" title="개발 모드: X-Actor-Id · X-Roles(· X-Client-Org-Id) 헤더로 인증(local-noauth)">
      <span className="badge warn">DEV 헤더 인증</span>
      <select
        aria-label="개발 사용자"
        data-testid="dev-user-picker"
        value={session?.actorId ?? ''}
        onChange={async (e) => {
          const u = DEV_USERS.find((x) => x.id === e.target.value);
          if (!u) return;
          let clientOrgId: string | undefined;
          if (u.clientName) {
            clientOrgId = await resolveClientOrgId(u.clientName).catch(() => undefined);
            if (!clientOrgId) toast.error(`고객사 "${u.clientName}" 를 찾지 못했습니다(dev 시드 확인)`);
          }
          switchDevUser({ actorId: u.id, name: u.name, roles: u.roles, clientOrgId });
          etagStore.clear();
          qc.clear();
          const customer = u.roles.includes('CUSTOMER');
          const inPortal = window.location.pathname.startsWith('/portal');
          if (customer && !inPortal) navigate('/portal');
          else if (!customer && inPortal) navigate('/');
        }}
      >
        {DEV_USERS.map((u) => (
          <option key={u.id} value={u.id}>
            {u.name} ({u.roles.join(',')})
          </option>
        ))}
      </select>
    </div>
  );
}

export function ThemeToggle() {
  const { theme, toggle } = useThemeContext();
  return (
    <button onClick={toggle} aria-label="테마 전환" data-testid="theme-toggle">
      {theme === 'dark' ? '☀ Light' : '☾ Dark'}
    </button>
  );
}
