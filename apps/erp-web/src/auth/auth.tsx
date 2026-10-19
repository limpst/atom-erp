import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { setAuthHeaderProvider } from '@/api/http';
import { DEV_USERS, type DevUser } from './devUsers';

export type Role =
  | 'EXEC' | 'SALES' | 'PM' | 'ENG' | 'QA' | 'ADMIN' | 'LABELER'
  | 'CUSTOMER' | 'SYSADMIN' | 'EMPLOYEE' | 'FIN' | 'AUDITOR';

export const ROLE_LABELS: Record<Role, string> = {
  EXEC: '임원', SALES: '영업', PM: '책임기술자', ENG: '참여기술자', QA: '품질검토자', ADMIN: '경영지원',
  LABELER: '라벨러', CUSTOMER: '고객', SYSADMIN: '시스템 관리자', EMPLOYEE: '직원', FIN: '재무', AUDITOR: '감사',
};

export type AuthMode = 'dev' | 'oidc';

export interface Session {
  /** 직원 ID(X-Actor-Id / 토큰 eid) */
  actorId: string;
  name: string;
  roles: Role[];
  clientOrgId?: string;
}

interface AuthContextValue {
  mode: AuthMode;
  session: Session | null;
  ready: boolean;
  /** dev: 사용자 전환 */
  switchDevUser: (u: DevUser | Session) => void;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const AUTH_MODE: AuthMode = (import.meta.env.VITE_AUTH_MODE as AuthMode) === 'oidc' ? 'oidc' : 'dev';
const DEV_KEY = 'atom-erp.dev-session';

/** Keycloak(atom-internal realm, 공개 PKCE 클라이언트 erp-web). 비밀값 없음 — 자리표시자 기본값. */
export function createUserManager() {
  return new UserManager({
    authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/atom-internal',
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'erp-web',
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
}

function sessionFromOidc(user: User): Session {
  const p = user.profile as Record<string, unknown>;
  // 역할은 access token 의 roles 클레임(atom-claims 스코프). id_token profile 에 없으면 access token 을 디코드한다.
  let roles = (p.roles as string[] | undefined) ?? [];
  let eid = p.eid as string | undefined;
  try {
    const payload = JSON.parse(atob(user.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    roles = payload.roles ?? roles;
    eid = payload.eid ?? eid;
  } catch {
    /* 불투명 토큰 */
  }
  return {
    actorId: eid ?? user.profile.sub,
    name: (user.profile.name as string) ?? user.profile.preferred_username ?? user.profile.sub,
    roles: roles.filter((r): r is Role => r in ROLE_LABELS),
  };
}

function loadDevSession(): Session {
  try {
    const raw = localStorage.getItem(DEV_KEY);
    if (raw) return JSON.parse(raw) as Session;
  } catch {
    /* 저장소 사용 불가 */
  }
  const d = DEV_USERS[0];
  return { actorId: d.id, name: d.name, roles: d.roles };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(AUTH_MODE === 'dev' ? loadDevSession() : null);
  const [ready, setReady] = useState(AUTH_MODE === 'dev');
  const umRef = useRef<UserManager | null>(null);
  const tokenRef = useRef<string | null>(null);
  const sessionRef = useRef(session);
  sessionRef.current = session;

  // 자식 컴포넌트의 첫 쿼리(자식 effect 가 부모 effect 보다 먼저 실행)보다 앞서야 하므로 렌더 중에 한 번 등록한다
  const registered = useRef(false);
  if (!registered.current) {
    registered.current = true;
    setAuthHeaderProvider(() => {
      if (AUTH_MODE === 'oidc') {
        return tokenRef.current ? { Authorization: `Bearer ${tokenRef.current}` } : {};
      }
      const s = sessionRef.current;
      if (!s) return {};
      const h: Record<string, string> = { 'X-Actor-Id': s.actorId, 'X-Roles': s.roles.join(',') };
      if (s.clientOrgId) h['X-Client-Org-Id'] = s.clientOrgId;
      return h;
    });
  }

  useEffect(() => {
    if (AUTH_MODE !== 'oidc') return;
    const um = createUserManager();
    umRef.current = um;
    const apply = (u: User | null) => {
      tokenRef.current = u?.access_token ?? null;
      setSession(u && !u.expired ? sessionFromOidc(u) : null);
    };
    um.events.addUserLoaded(apply);
    um.events.addUserUnloaded(() => apply(null));
    (async () => {
      try {
        if (window.location.pathname === '/auth/callback') {
          const u = await um.signinRedirectCallback();
          apply(u);
          window.history.replaceState({}, '', '/');
        } else {
          apply(await um.getUser());
        }
      } catch (e) {
        console.error('OIDC 오류', e);
      } finally {
        setReady(true);
      }
    })();
  }, []);

  const switchDevUser = useCallback((u: DevUser | Session) => {
    const s: Session = 'actorId' in u ? u : { actorId: u.id, name: u.name, roles: u.roles };
    try {
      localStorage.setItem(DEV_KEY, JSON.stringify(s));
    } catch {
      /* 무시 */
    }
    setSession(s);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      mode: AUTH_MODE,
      session,
      ready,
      switchDevUser,
      login: async () => {
        if (AUTH_MODE === 'oidc') await umRef.current?.signinRedirect();
      },
      logout: async () => {
        if (AUTH_MODE === 'oidc') await umRef.current?.signoutRedirect();
        else setSession(null);
      },
      hasRole: (...roles) => !!session && roles.some((r) => session.roles.includes(r)),
    }),
    [session, ready, switchDevUser],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('AuthProvider 밖에서 useAuth 호출');
  return ctx;
}
