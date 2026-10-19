import { Route, Routes } from 'react-router-dom';
import { Toaster } from 'sonner';
import { ThemeProvider, useThemeContext } from './ThemeContext';
import { Layout } from './Layout';
import { useAuth } from '@/auth/auth';
import { HomePage } from '@/features/home/HomePage';
import { ProjectsPage } from '@/features/projects/ProjectsPage';
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage';
import { EstimatesPage } from '@/features/sal/EstimatesPage';
import { TimesheetPage } from '@/features/hrq/TimesheetPage';
import { FcdCasesPage } from '@/features/fcd/FcdCasesPage';
import { ReportsPage } from '@/features/rpt/ReportsPage';
import { ReportDetailPage } from '@/features/rpt/ReportDetailPage';

function Themed() {
  const { theme } = useThemeContext();
  const { ready, session, mode, login } = useAuth();
  if (!ready) return <div className="empty">인증 확인 중…</div>;
  if (!session && mode === 'oidc') {
    return (
      <div className="empty">
        <p>Keycloak(OIDC) 로그인이 필요합니다.</p>
        <button className="primary" onClick={login}>
          로그인
        </button>
      </div>
    );
  }
  return (
    <>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<HomePage />} />
          <Route path="projects" element={<ProjectsPage />} />
          <Route path="projects/:id" element={<ProjectDetailPage />} />
          <Route path="sal/estimates" element={<EstimatesPage />} />
          <Route path="hrq/timesheets" element={<TimesheetPage />} />
          <Route path="fcd/cases" element={<FcdCasesPage />} />
          <Route path="reports" element={<ReportsPage />} />
          <Route path="reports/:id" element={<ReportDetailPage />} />
          <Route path="*" element={<div className="empty">페이지를 찾을 수 없습니다</div>} />
        </Route>
      </Routes>
      <Toaster richColors closeButton position="top-right" theme={theme} />
    </>
  );
}

export function App() {
  return (
    <ThemeProvider>
      <Themed />
    </ThemeProvider>
  );
}
