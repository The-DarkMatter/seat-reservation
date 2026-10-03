import { expect, test } from '@playwright/test';
import { demoShow, openSeats, seat } from './helpers';

test('booking works on a phone', async ({ page, request }) => {
  const show = await demoShow(request, 'club');
  await openSeats(page, show);
  await page.getByRole('button', { name: 'Zoom in' }).click();
  await seat(page, 'B', 3).click();
  await expect(page.locator('.bottom-bar.open')).toBeVisible();
  await page.screenshot({ path: 'test-results/phone-seats.png' });
  await page.getByRole('button', { name: 'PROCEED' }).click();
  await expect(page).toHaveURL(/\/checkout\//);
  await page.screenshot({ path: 'test-results/phone-checkout.png', fullPage: true });
});

test('home page fits a phone screen', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.hero-title')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
