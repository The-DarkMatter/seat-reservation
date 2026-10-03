import { expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * A fresh demo show for one test, so tests never fight over featured seats. A
 * random X-Forwarded-For keeps each test inside its own demo rate-limit bucket
 * (the proxy in front is trusted for that header; a browser can't set it).
 */
export async function demoShow(request: APIRequestContext, template: 'club' | 'arena' | 'theatre', ttl = 120): Promise<string> {
  const ip = `198.51.100.${1 + Math.floor(Math.random() * 250)}`;
  const res = await request.post('/demo/shows', {
    data: { template, hold_ttl_seconds: ttl },
    headers: { 'X-Forwarded-For': ip },
  });
  expect(res.status(), await res.text()).toBe(201);
  return (await res.json()).id;
}

export function seat(page: Page, row: string, n: number) {
  return page.locator(`circle.seat[aria-label^="Row ${row} seat ${n},"]`);
}

export async function openSeats(page: Page, showId: string) {
  await page.goto(`/events/${showId}`);
  await expect(page.locator('.seatmap-viewport svg')).toBeVisible();
}
