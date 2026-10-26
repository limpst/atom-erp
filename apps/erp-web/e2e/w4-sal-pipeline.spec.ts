import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

/** SAL 4차: 파이프라인 칸반(버튼 · 끌어 놓기 → :move If-Match, 서버 403 · 409 화면 안) · 나라장터 공고(대역) · 적격심사 */
test('SAL 파이프라인 보드 — 버튼 · 끌어 놓기 이동, 전이표 밖 409 · EXEC 403 을 보드 위에', async ({ page }) => {
  await gotoAs(page, '/sal/pipeline', '박영업');
  const lead = page.getByTestId('col-LEAD');
  const card = lead.locator('.opp', { hasText: '(합성) 예시빌딩 정기점검 문의' });
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: /→ 검토$/ }).click();
  const qual = page.getByTestId('col-QUALIFYING').locator('.opp', { hasText: '(합성) 예시빌딩 정기점검 문의' });
  await expect(qual).toBeVisible();
  // 끌어 놓기: 검토 → 제안
  await qual.dragTo(page.getByTestId('col-PROPOSING'));
  const proposing = page.getByTestId('col-PROPOSING').locator('.opp', { hasText: '(합성) 예시빌딩 정기점검 문의' });
  await expect(proposing).toBeVisible();
  // 전이표 밖(제안 → 수주)으로 끌면 서버가 409 SAL-409-001 — 보드 위 배너
  await proposing.dragTo(page.getByTestId('col-WON'));
  await expect(page.getByTestId('board-refused')).toContainText('SAL-409-001');
  await expect(proposing).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/48-sal-pipeline-board.png`, fullPage: true });

  // EXEC 는 읽기만 — 이동은 서버 403 을 화면 안에
  await loginAs(page, '김임원');
  const card2 = page.getByTestId('col-PROPOSING').locator('.opp', { hasText: '(합성) 예시빌딩 정기점검 문의' });
  await card2.getByRole('button', { name: /→ 협상$/ }).click();
  await expect(page.getByTestId('board-refused')).toContainText('CORE-403-001');
});

test('SAL 공고 수집(나라장터 대역) — 목록 · 필터 · ADMIN 지금 수집', async ({ page }) => {
  await gotoAs(page, '/sal/pipeline?tab=notices', '박영업');
  const table = page.getByTestId('notices');
  await expect(table).toContainText('(합성) 합성시 청사 정밀안전점검 용역');
  await expect(page.getByTestId('notice-R4-0003-000')).toContainText('정정됨(이전 차수)');
  await expect(page.getByTestId('notice-R4-0004-000')).toContainText('취소');
  await page.getByLabel('정정 · 취소 숨기기').check();
  await expect(page.getByTestId('notice-R4-0003-000')).toHaveCount(0);
  await expect(page.getByTestId('notice-R4-0003-001')).toBeVisible();
  await page.getByLabel('공고 상태').selectOption('NEW');
  await page.getByLabel('최소 추정가격').fill('90000000');
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table).toContainText('R4-0003-001');
  await page.screenshot({ path: `${SHOTS}/49-sal-bid-notices.png`, fullPage: true });

  await loginAs(page, '서경영');
  await page.getByTestId('collect-now').click();
  await expect(page.getByText(/공고 수집 — 받은 5건/)).toBeVisible();
  await expect(page.getByTestId('collect-last')).toContainText('성공');
});

test('SAL 적격심사 시뮬레이션 — 공고 연결 기회 · 항목별 점수 · 투찰률 판정', async ({ page }) => {
  await gotoAs(page, '/sal/pipeline?tab=sim', '박영업');
  const sel = page.getByLabel('시뮬레이션 기회');
  await expect(sel.locator('option:checked')).toContainText('합성시 청사 정밀안전점검 입찰');
  await page.getByLabel('투찰률 목록').fill('86, 87.8, 88.2, 90');
  await page.getByTestId('sim-run').click();
  const result = page.getByTestId('sim-result');
  await expect(result).toContainText('유사 용역 실적(5년)');
  await expect(result).toContainText('신용 등급');
  await expect(page.getByTestId('sim-scenarios')).toContainText('하한율 미만 — 탈락');
  await expect(page.getByTestId('sim-scenarios').locator('tbody tr')).toHaveCount(4);
  await page.screenshot({ path: `${SHOTS}/50-sal-qualification-sim.png`, fullPage: true });
});
