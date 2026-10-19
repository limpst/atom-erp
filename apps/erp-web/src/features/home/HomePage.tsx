import { Link } from 'react-router-dom';
import { ROLE_LABELS, useAuth } from '@/auth/auth';
import { Card } from '@/components/ui';

const SCREENS = [
  { to: '/projects', title: '프로젝트 목록 · 상세', desc: '투입 인원 · 실행예산 · EVM · 수익성 (CORE · CST)' },
  { to: '/sal/estimates', title: '견적 작성 · 발행 · 할인 승인', desc: '대가 산정 → 견적 버전 → 발행 · 임원 할인 승인 (SAL)' },
  { to: '/hrq/timesheets', title: '타임시트', desc: '주간 입력 · 제출 → PM 승인 → 정정(리비전) (HRQ)' },
  { to: '/fcd/cases', title: '법인카드 검토 큐', desc: '케이스 → 소명 요청 · 소명 → 감사 판정 (FCD)' },
  { to: '/reports', title: '보고서 · QAR', desc: '리비전 → QAR 검토 상태 → 발행 (RPT · QAR)' },
];

export function HomePage() {
  const { session, mode } = useAuth();
  return (
    <>
      <h1>대시보드</h1>
      <Card title="현재 사용자">
        <div className="row">
          <strong>{session?.name}</strong>
          <span className="muted">{session?.roles.map((r) => `${r} ${ROLE_LABELS[r]}`).join(' · ')}</span>
          <span className="badge">{mode === 'dev' ? 'dev header auth' : 'OIDC'}</span>
        </div>
      </Card>
      <div className="grid3">
        {SCREENS.map((s) => (
          <Card key={s.to} title={<Link to={s.to}>{s.title}</Link>}>
            <div className="muted">{s.desc}</div>
          </Card>
        ))}
      </div>
      <p className="muted">모든 데이터는 합성(가짜) 데이터입니다.</p>
    </>
  );
}
