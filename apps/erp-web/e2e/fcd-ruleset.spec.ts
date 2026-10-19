import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';
import { gotoAs, loginAs, SHOTS } from './helpers';

const AUDITOR_ID = '00000000-0000-7000-8000-000000000007';

/** 저장소 룰셋 YAML 을 새 버전(2027 시행 — 현재 채점에 영향 없음)으로 바꾼 합성 초안 */
function draftYaml(version: string) {
  const src = readFileSync(resolve(process.cwd(), '../../modules/fcd/src/main/resources/fcd/rulesets/fcd-2026.5.0.yaml'), 'utf-8');
  return src
    .replace(/^version: .*$/m, `version: ${version}`)
    .replace(/^effective_from: .*$/m, "effective_from: '2027-01-01'")
    .replace(/^status: .*$/m, 'status: DRAFT');
}

test('FCD 룰셋 4-eyes: 작성자(AUDITOR) 승인 차단 → 다른 승인자(EXEC) 승인', async ({ page }) => {
  const version = `2026.9.${Date.now() % 100000}-e2e`;
  await gotoAs(page, '/fcd/rulesets', '오감사');
  await page.getByLabel('룰셋 YAML', { exact: true }).fill(draftYaml(version));
  await page.getByTestId('rs-draft').click();
  await expect(page.getByText(new RegExp(`초안 등록 — ${version.replace(/\./g, '\.')}`))).toBeVisible();
  const row = page.getByTestId(`rs-${version}`);
  await expect(row).toContainText('초안');
  await expect(row).toContainText('오감사');
  // 화면: 작성자 본인은 승인 버튼 비활성 + 이유
  await expect(row.getByRole('button', { name: `승인 ${version}` })).toBeDisabled();
  await expect(row.getByTestId(`rs-block-${version}`)).toHaveText('작성자 본인 — 승인 불가(4-eyes)');
  // 서버: 화면을 우회해 작성자 헤더로 직접 승인해도 403 FCD-403-002
  const direct = await page.request.post(`/api/v1/fcd/rule-sets/${version}:approve?name=fcd`, {
    headers: { 'X-Actor-Id': AUDITOR_ID, 'X-Roles': 'AUDITOR', 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(direct.status()).toBe(403);
  expect((await direct.json()).code).toBe('FCD-403-002');
  await row.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${SHOTS}/29-fcd-ruleset-author-blocked.png`, fullPage: true });

  // 다른 승인자(EXEC)
  await loginAs(page, '김임원');
  const row2 = page.getByTestId(`rs-${version}`);
  await row2.getByRole('button', { name: `승인 ${version}` }).click();
  await expect(page.getByText(`승인 — ${version} ACTIVE`)).toBeVisible();
  await expect(row2).toContainText('승인(ACTIVE)');
  await expect(row2).toContainText('김임원');
  // 2027 시행이라 오늘 적용 버전은 그대로
  await expect(page.getByTestId('rs-current')).toHaveText('2026.5.0');
  await page.screenshot({ path: `${SHOTS}/30-fcd-ruleset-approved.png`, fullPage: true });
});
