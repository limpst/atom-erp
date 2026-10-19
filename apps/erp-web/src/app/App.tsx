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
import { SalesGroupPage } from '@/features/admin/SalesGroupPage';
import { ReviewerProfilesPage } from '@/features/admin/ReviewerProfilesPage';
import { LaborRatesPage } from '@/features/admin/LaborRatesPage';
import { BuildingsPage } from '@/features/fac/BuildingsPage';
import { BuildingDetailPage } from '@/features/fac/BuildingDetailPage';
import { DuesPage } from '@/features/fac/DuesPage';
import { EquipmentPage } from '@/features/eqp/EquipmentPage';
import { ReservationsPage } from '@/features/eqp/ReservationsPage';
import { DemPage } from '@/features/dem/DemPage';
import { FldAdminPage } from '@/features/fld/FldAdminPage';
import { HrqPeoplePage } from '@/features/hrq/HrqPeoplePage';
import { HrqCompliancePage } from '@/features/hrq/HrqCompliancePage';
import { HrqMonthClosePage } from '@/features/hrq/HrqMonthClosePage';
import { CstPeriodsPage } from '@/features/cst/CstPeriodsPage';
import { CostLedgerPage } from '@/features/cst/CostLedgerPage';
import { FcdImportsPage } from '@/features/fcd/FcdImportsPage';
import { FcdRulesetsPage } from '@/features/fcd/FcdRulesetsPage';
import { FcdCardsPage } from '@/features/fcd/FcdCardsPage';
import { AipPage } from '@/features/aip/AipPage';
import { RevisionDetailPage } from '@/features/rpt/RevisionDetailPage';
import { PortalLayout } from '@/features/ptl/PortalLayout';
import { PortalHomePage, PortalNotificationsPage, PortalReportsPage, PortalRequestsPage } from '@/features/ptl/PortalPages';

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
          <Route path="admin/sales-group" element={<SalesGroupPage />} />
          <Route path="admin/qa-reviewers" element={<ReviewerProfilesPage />} />
          <Route path="admin/labor-rates" element={<LaborRatesPage />} />
          <Route path="fac/buildings" element={<BuildingsPage />} />
          <Route path="fac/buildings/:id" element={<BuildingDetailPage />} />
          <Route path="fac/dues" element={<DuesPage />} />
          <Route path="eqp/equipment" element={<EquipmentPage />} />
          <Route path="eqp/reservations" element={<ReservationsPage />} />
          <Route path="dem" element={<DemPage />} />
          <Route path="fld" element={<FldAdminPage />} />
          <Route path="hrq/people" element={<HrqPeoplePage />} />
          <Route path="hrq/compliance" element={<HrqCompliancePage />} />
          <Route path="hrq/month-close" element={<HrqMonthClosePage />} />
          <Route path="cst/periods" element={<CstPeriodsPage />} />
          <Route path="cst/ledger" element={<CostLedgerPage />} />
          <Route path="fcd/imports" element={<FcdImportsPage />} />
          <Route path="fcd/rulesets" element={<FcdRulesetsPage />} />
          <Route path="fcd/cards" element={<FcdCardsPage />} />
          <Route path="aip" element={<AipPage />} />
          <Route path="reports/:id/revisions/:revId" element={<RevisionDetailPage />} />
          <Route path="*" element={<div className="empty">페이지를 찾을 수 없습니다</div>} />
        </Route>
        <Route path="portal" element={<PortalLayout />}>
          <Route index element={<PortalHomePage />} />
          <Route path="reports" element={<PortalReportsPage />} />
          <Route path="requests" element={<PortalRequestsPage />} />
          <Route path="notifications" element={<PortalNotificationsPage />} />
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
