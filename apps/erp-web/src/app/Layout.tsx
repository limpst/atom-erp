import { NavLink, Outlet } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { NAV } from './nav';
import { useThemeContext } from './ThemeContext';
import { ROLE_LABELS, useAuth } from '@/auth/auth';
import { DEV_USERS } from '@/auth/devUsers';
import { etagStore } from '@/api/http';

function UserPicker() {
  const { mode, session, switchDevUser, logout } = useAuth();
  const qc = useQueryClient();
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
    <div className="row" title="개발 모드: X-Actor-Id · X-Roles 헤더로 인증(local-noauth)">
      <span className="badge warn">DEV 헤더 인증</span>
      <select
        aria-label="개발 사용자"
        data-testid="dev-user-picker"
        value={session?.actorId ?? ''}
        onChange={(e) => {
          const u = DEV_USERS.find((x) => x.id === e.target.value);
          if (u) {
            switchDevUser(u);
            etagStore.clear();
            qc.clear();
          }
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

export function Layout() {
  const { theme, toggle } = useThemeContext();
  const { hasRole } = useAuth();
  return (
    <div className="layout">
      <nav className="sidebar" aria-label="모듈 메뉴">
        <div className="brand">에이톰 ERP</div>
        {NAV.map((g) => (
          <div key={g.id}>
            <div className="group">{g.label}</div>
            {g.items
              .filter((i) => !i.roles || hasRole(...i.roles))
              .map((i) =>
                i.path ? (
                  <NavLink key={i.label} to={i.path} end={i.path === '/'}>
                    <span>{i.label}</span>
                    <span className="mod">{i.module}</span>
                  </NavLink>
                ) : (
                  <a key={i.label} className="disabled" aria-disabled title="다음 단계">
                    <span>{i.label}</span>
                    <span className="mod">{i.module}</span>
                  </a>
                ),
              )}
          </div>
        ))}
      </nav>
      <div className="main">
        <header className="topbar">
          <span className="spacer" />
          <UserPicker />
          <button onClick={toggle} aria-label="테마 전환" data-testid="theme-toggle">
            {theme === 'dark' ? '☀ Light' : '☾ Dark'}
          </button>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
