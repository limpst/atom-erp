import { Link, NavLink, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ptlApi } from '@/api/ptl';
import { useAuth } from '@/auth/auth';
import { ThemeToggle, UserPicker } from '@/app/UserPicker';

/** 고객 포털 전용 레이아웃(/portal) — 내부 사이드바 없음. CUSTOMER 가 아니면 안내만 보인다. */
export function PortalLayout() {
  const { session } = useAuth();
  const customer = !!session?.roles.includes('CUSTOMER');
  const unread = useQuery({
    queryKey: ['ptl', 'notifications', 'unread'],
    queryFn: () => ptlApi.notifications(true),
    enabled: customer && !!session?.clientOrgId,
    meta: { silent: true },
  });
  return (
    <div className="portal">
      <header className="topbar">
        <span className="brand">에이톰 고객 포털</span>
        <span className="badge info">시설물 안전 정보</span>
        <span className="spacer" />
        <UserPicker />
        <ThemeToggle />
      </header>
      {customer ? (
        <>
          <nav className="ptabs" aria-label="포털 메뉴">
            <NavLink to="/portal" end>내 시설물 · 일정</NavLink>
            <NavLink to="/portal/reports">보고서</NavLink>
            <NavLink to="/portal/requests">서비스 요청</NavLink>
            <NavLink to="/portal/notifications">
              알림{unread.data && unread.data.length > 0 ? ` (${unread.data.length})` : ''}
            </NavLink>
          </nav>
          <main>
            {!session?.clientOrgId ? (
              <div className="error-box">고객사 정보(cid)가 없는 계정입니다. 사용자 선택을 다시 해 주세요.</div>
            ) : (
              <Outlet />
            )}
          </main>
        </>
      ) : (
        <main>
          <div className="empty">
            고객 포털은 고객(CUSTOMER) 계정 전용입니다. 위 사용자 선택에서 고객 사용자를 고르거나 <Link to="/">내부 ERP</Link> 로 돌아가세요.
          </div>
        </main>
      )}
    </div>
  );
}
