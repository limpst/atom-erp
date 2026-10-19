import { Navigate, NavLink, Outlet } from 'react-router-dom';
import { NAV } from './nav';
import { useAuth } from '@/auth/auth';
import { ThemeToggle, UserPicker } from './UserPicker';

export function Layout() {
  const { hasRole, session } = useAuth();
  // 고객 포털 사용자는 내부 화면 대신 /portal (서버도 내부 API 를 403 으로 막는다)
  if (session?.roles.includes('CUSTOMER')) return <Navigate to="/portal" replace />;
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
          <ThemeToggle />
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
