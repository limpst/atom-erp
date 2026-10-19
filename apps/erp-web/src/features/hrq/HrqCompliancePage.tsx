import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { credentialChip, GRADE_LABEL, hrqApi, totalRequired, totalShortage } from '@/api/hrq';
import { useAuth } from '@/auth/auth';
import { Badge, Banner, Card, ErrorBox, Kpi, Loading } from '@/components/ui';
import { LineChart, type ChartSeries } from '@/components/LineChart';
import { todayIso } from '@/features/admin/common';

const FIELD_LABEL: Record<string, string> = { ARCH: '건축', CIVIL: '토목', SAFETY: '안전', STRUCT: '구조', OTHER: '기타' };

/**
 * G5 › 등록요건 — 오늘 충족 여부 + 1년 예측(변화점마다 다시 평가, 최초 미달일 표시) + 자격 유효 현황.
 * 등록요건 · 자격 규칙은 ⚠️ 예시 값(법령 최신본 확인 필요).
 */
export function HrqCompliancePage() {
  const { hasRole } = useAuth();
  const today = todayIso();
  const now = useQuery({ queryKey: ['hrq', 'compliance', 'today'], queryFn: () => hrqApi.compliance() });
  const fc = useQuery({ queryKey: ['hrq', 'compliance', 'forecast'], queryFn: () => hrqApi.forecast() });
  const canCreds = hasRole('EXEC', 'PM', 'QA', 'ADMIN', 'SYSADMIN', 'AUDITOR');
  const creds = useQuery({
    queryKey: ['hrq', 'credentials'], queryFn: () => hrqApi.credentials(), enabled: canCreds, meta: { silent: true },
  });

  const chart = useMemo(() => {
    const pts = fc.data?.series ?? [];
    // 계단식: 마지막 변화점의 값을 예측 끝날까지 이어 그린다
    const last = pts[pts.length - 1];
    const series = last && fc.data && last.date < fc.data.to ? [...pts, { ...last, date: fc.data.to }] : pts;
    const x = series.map((e) => e.date);
    const s: ChartSeries[] = [
      { label: '미달 인원(명)', values: series.map(totalShortage), color: '--danger', stepped: true },
      { label: '필요 인원(명)', values: series.map(totalRequired), color: '--text-2', stepped: true, dash: true },
    ];
    return { x, s };
  }, [fc.data]);
  const markers = useMemo(
    () => (fc.data?.firstShortfall ? [{ x: fc.data.firstShortfall, label: `최초 미달 ${fc.data.firstShortfall}` }] : []),
    [fc.data],
  );

  const credStats = useMemo(() => {
    const rows = creds.data ?? [];
    const valid = rows.filter((c) => c.status === 'VALID' || c.status === 'EXPIRING');
    return {
      holders: new Set(valid.filter((c) => credentialChip(c, today).tone !== 'danger').map((c) => c.employee_id)).size,
      valid: valid.filter((c) => credentialChip(c, today).tone !== 'danger').length,
      pending: rows.filter((c) => c.status === 'PENDING').length,
      soon: valid.filter((c) => credentialChip(c, today).tone === 'warn').length,
      expiries: valid.filter((c) => c.expires_on && c.expires_on >= today).length,
    };
  }, [creds.data, today]);

  if (now.isLoading) return <Loading />;
  const ev = now.data;
  return (
    <>
      <h1>등록요건 (안전진단전문기관)</h1>
      <Banner tone="warn">⚠️ 예시 규칙 — 등록요건 · 자격 · 교육 값은 법령 최신본 확인이 필요합니다(QA · 법무 서명 전 운영 사용 금지).</Banner>
      {now.error && <ErrorBox error={now.error} />}
      {ev && (
        <div className="kpis" style={{ marginBottom: 16 }}>
          <Kpi label={`오늘(${ev.date})`} value={<span data-testid="compliance-today">{ev.allSatisfied ? '충족' : '미달'}</span>} tone={ev.allSatisfied ? 'ok' : 'danger'} />
          <Kpi label="미달 인원" value={`${totalShortage(ev)}명`} tone={totalShortage(ev) ? 'danger' : 'ok'} />
          <Kpi
            label="최초 미달일(1년 예측)"
            value={<span data-testid="first-shortfall">{fc.data ? fc.data.firstShortfall ?? '없음' : '…'}</span>}
            tone={fc.data?.firstShortfall ? 'danger' : 'ok'}
          />
          {canCreds && (
            <>
              <Kpi label="유효 자격 보유자" value={<span data-testid="cred-holders">{credStats.holders}명</span>} />
              <Kpi label="유효 자격" value={<span data-testid="cred-valid">{credStats.valid}건</span>} />
              <Kpi label="승인 대기 자격" value={<span data-testid="cred-pending">{credStats.pending}건</span>} tone={credStats.pending ? 'warn' : undefined} />
            </>
          )}
        </div>
      )}
      <div className="grid2">
        <Card title="오늘 충족 현황 (분야 · 등급별)">
          <table className="simple" data-testid="compliance-table">
            <thead>
              <tr><th>분야</th><th>최소 등급</th><th className="num">필요</th><th>배정 사번</th><th className="num">부족</th><th>판정</th></tr>
            </thead>
            <tbody>
              {ev?.fields.flatMap((f) =>
                f.outcomes.map((o) => (
                  <tr key={o.rule.id}>
                    <td>{FIELD_LABEL[f.field] ?? f.field}</td>
                    <td>{GRADE_LABEL[o.rule.minGrade]} 이상{o.rule.requiredCredential ? ` + ${o.rule.requiredCredential}` : ''}</td>
                    <td className="num">{o.rule.minCount}</td>
                    <td className="mono">{o.assignedEmpNos.join(', ') || '—'}</td>
                    <td className="num">{o.shortage}</td>
                    <td><Badge tone={o.shortage ? 'danger' : 'ok'}>{o.shortage ? '부족' : '충족'}</Badge></td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
          <p className="muted" style={{ fontSize: 12 }}>
            높은 요건부터 · 낮은 적격 등급부터 배정(상위 등급이 하위 요건을 채울 수 있음). 휴직자는 규칙에 따라 제외.
          </p>
        </Card>
        <Card title="1년 예측 (변화점: 퇴사 예정일 · 자격 만료 · 등급 변경 · 규칙 시행일)">
          {fc.error && <ErrorBox error={fc.error} />}
          <LineChart
            testId="forecast-chart"
            ariaLabel="등록요건 미달 인원 예측"
            x={chart.x}
            series={chart.s}
            markers={markers}
            yLabel="인원"
            height={240}
          />
          <p className="muted" style={{ fontSize: 12 }} data-testid="forecast-points">
            평가 시점 {fc.data?.series.length ?? 0}개 · 예측 기간 {fc.data?.from} ~ {fc.data?.to}
            {canCreds && ` · 유효 자격 만료 예정 ${credStats.expiries}건(만료 다음 날이 변화점)`}
          </p>
          {fc.data && Object.keys(fc.data.shortage).length > 0 && (
            <p style={{ fontSize: 13 }}>
              최초 미달 시점 부족:{' '}
              {Object.entries(fc.data.shortage).map(([k, n]) => {
                const [field, grade] = k.split(':');
                return <Badge key={k} tone="danger">{`${FIELD_LABEL[field] ?? field} ${GRADE_LABEL[grade as keyof typeof GRADE_LABEL] ?? grade} ${n}명`}</Badge>;
              })}
            </p>
          )}
        </Card>
      </div>
    </>
  );
}
