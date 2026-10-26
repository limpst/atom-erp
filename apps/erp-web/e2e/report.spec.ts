import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

test('RPT 리비전 → QAR 검토 → 발행', async ({ page }) => {
  test.setTimeout(240_000);
  await gotoAs(page, '/reports', '최책임');
  await page.getByRole('button', { name: '생성' }).click();
  const rev = page.getByTestId('revision-status');
  await expect(rev).toHaveText('초안');
  await page.getByRole('button', { name: /합성 스냅샷 동결/ }).click();
  await expect(rev).toHaveText('스냅샷 동결');
  await page.getByRole('button', { name: /작성\(compose\)/ }).click();
  await expect(rev).toHaveText('작성 중');
  await page.getByRole('button', { name: /최종 등급 확정/ }).click();
  await expect(page.getByText(/최종 등급 [A-E] 확정/)).toBeVisible();
  await page.getByRole('button', { name: /렌더 · 검토 요청/ }).click();
  await expect(rev).toHaveText('QAR 검토 중');

  // 작성자 자체검토 → 제출
  await page.getByRole('button', { name: 'QAR 검토 시작' }).click();
  const rs = page.getByTestId('review-status');
  await expect(rs).toHaveText('SELF_CHECK');
  await page.getByRole('button', { name: /AUTO-CHECK 실행/ }).click();
  await expect(page.getByText(/AUTO-CHECK .*BLOCKING 0/)).toBeVisible();
  await page.getByRole('button', { name: /자체검토 체크리스트 PASS/ }).click();
  await expect(page.getByText(/자체검토 체크리스트 \d+개 PASS/)).toBeVisible();
  await page.getByRole('button', { name: /제출 \(If-Match\)/ }).click();
  await expect(rs).not.toHaveText('SELF_CHECK');

  // QA: AI(미설정 → 건너뜀) → 배정 → 체크리스트 → 통과
  await loginAs(page, '한품질');
  await expect(rs).toBeVisible();
  for (let i = 0; i < 20 && (await rs.innerText()) !== 'QA_REVIEWING'; i++) {
    const ai = page.getByRole('button', { name: /AI 작업 실행/ });
    if (await ai.isVisible()) await ai.click();
    await page.waitForTimeout(1500);
  }
  await expect(rs).toHaveText('QA_REVIEWING');
  // QAR-03b: 자기 배정 금지 — 한품질은 검토자 프로필로 자동 배정된다(수동 "나를 배정" 버튼 없음)
  await expect(page.getByRole('button', { name: '나를 QA 검토자로 배정' })).toHaveCount(0);
  await page.getByRole('button', { name: 'QA 체크리스트 PASS' }).click();
  await expect(page.getByText(/QA 체크리스트 \d+개 PASS/)).toBeVisible();
  await page.getByRole('button', { name: /QA 통과/ }).click();
  await expect(rs).toHaveText('PENDING_APPROVAL');
  await page.screenshot({ path: `${SHOTS}/08-qar-pending-approval.png`, fullPage: true });

  // QAR-01: 작성 · 제출 · 등급 결정한 최책임은 최종 승인 불가(가드 QAR_SOD_VIOLATION) → 두 번째 PM 문책임2 가 승인
  await loginAs(page, '최책임');
  await page.getByRole('button', { name: '책임기술자 최종 승인' }).click();
  await expect(page.getByText(/QAR_SOD_VIOLATION/).first()).toBeVisible();
  await expect(rs).toHaveText('PENDING_APPROVAL');
  await loginAs(page, '문책임2');
  await page.getByRole('button', { name: '책임기술자 최종 승인' }).click();
  await expect(rs).toHaveText('APPROVED');
  await loginAs(page, '최책임');
  await expect(rev).toHaveText('검토 승인', { timeout: 30_000 });
  await page.getByTestId('publish').click();
  await expect(rev).toHaveText('발행');
  await page.screenshot({ path: `${SHOTS}/09-report-published.png`, fullPage: true });
});
