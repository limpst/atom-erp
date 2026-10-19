import { expect, test } from '@playwright/test';
import { gotoAs, SHOTS } from './helpers';

/**
 * FAC: 주소 조회(dev WireMock 대역 "합성로 1000~1999" = 표제부 1건) → 등록 → 상세(위치 트리) → 법적 분류 확정 → 점검 도래 목록.
 * 주소 번지는 실행마다 달리 해서(PNU+동 중복 방지) 같은 서버를 다시 써도 통과한다.
 */
test('FAC 주소 조회 등록 → 분류 확정 → 점검 도래 목록(D-day)', async ({ page }) => {
  const bun = 1300 + Math.floor(Math.random() * 690);
  const name = `(합성) 합성로 ${bun} 빌딩`;
  await gotoAs(page, '/fac/buildings', '최책임');
  await expect(page.getByTestId('fac-grid')).toContainText('(합성) 한빛타워 별관');

  await page.getByLabel('소유 고객사').selectOption({ label: '(합성) 한빛자산관리' });
  await page.getByLabel('도로명주소', { exact: true }).fill(`합성로 ${bun}`);
  await page.getByTestId('fac-lookup').click();
  const result = page.getByTestId('fac-lookup-result');
  await expect(result).toContainText(`서울 합성구 합성로 ${bun}`);
  await expect(result).toContainText('표제부: (합성) 합성로');
  await page.getByTestId('fac-register').click();
  await expect(page.getByText(/시설물 등록 — .*대장 자동 입력/)).toBeVisible();

  // 상세: 대장 확인 · 위치 트리(층수만큼 층 노드)
  await expect(page).toHaveURL(/\/fac\/buildings\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(name);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('대장 확인');
  await expect(page.getByTestId('fac-tree')).toContainText('5F');
  await page.screenshot({ path: `${SHOTS}/12-fac-registered-tree.png`, fullPage: true });

  // 분류 확정 → 도래 재계산
  await page.getByLabel('시특법 종별').selectOption('CLASS2');
  await page.getByLabel('분류 근거').fill('(합성) 연면적 12,000㎡ 업무시설');
  await page.getByTestId('fac-classify').click();
  await expect(page.getByText('법적 분류 확정 — 점검 도래일을 다시 계산했습니다')).toBeVisible();
  await page.getByRole('tab', { name: /점검 도래/ }).click();
  await expect(page.getByTestId('fac-dues')).toContainText('정밀안전점검');
  await page.getByRole('tab', { name: /등급 이력/ }).click();
  await expect(page.getByTestId('fac-grades')).toContainText('등급 이력 없음');

  // 점검 도래 목록: 새 시설물이 D-day 배지와 함께
  await page.getByRole('link', { name: /점검 도래/ }).click();
  await page.getByLabel('도래 기간').selectOption('');
  const grid = page.getByTestId('due-grid');
  await expect(grid).toContainText(name);
  const row = grid.getByRole('row', { name: new RegExp(name.replace(/[()]/g, '\\$&')) }).first();
  await expect(row.locator('.badge')).toHaveText(/D[+-]\d+/);
  await page.screenshot({ path: `${SHOTS}/13-fac-due-list.png`, fullPage: true });

  // 다크 모드
  await page.getByTestId('theme-toggle').click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.screenshot({ path: `${SHOTS}/14-fac-due-list-dark.png`, fullPage: true });
  await page.getByTestId('theme-toggle').click();
});
