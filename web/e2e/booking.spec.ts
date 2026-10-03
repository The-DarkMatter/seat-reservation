import { expect, test } from '@playwright/test';
import { demoShow, openSeats, seat } from './helpers';

test('two buyers race for one seat: exactly one gets it', async ({ browser, request }) => {
  const show = await demoShow(request, 'club');
  // Separate contexts = separate sessionStorage = two different guest buyers.
  const [a, b] = await Promise.all([browser.newContext(), browser.newContext()]);
  const [pa, pb] = await Promise.all([a.newPage(), b.newPage()]);
  await Promise.all([openSeats(pa, show), openSeats(pb, show)]);

  await seat(pa, 'C', 5).click();
  await seat(pb, 'C', 5).click();
  await expect(pa.locator('.bottom-bar.open')).toBeVisible();
  await expect(pb.locator('.bottom-bar.open')).toBeVisible();

  await Promise.all([pa.getByRole('button', { name: 'PROCEED' }).click(), pb.getByRole('button', { name: 'PROCEED' }).click()]);

  const outcome = async (p: typeof pa) => {
    const won = p.waitForURL(/\/checkout\//).then(() => 'won' as const);
    const lost = p.getByRole('heading', { name: 'Someone beat you to it' }).waitFor().then(() => 'lost' as const);
    return Promise.race([won, lost]);
  };
  const results = await Promise.all([outcome(pa), outcome(pb)]);
  expect(results.sort()).toEqual(['lost', 'won']);

  // The seat map is cached for 500 ms, so give it a moment to catch up.
  await expect
    .poll(async () => {
      const map = await (await request.get(`/shows/${show}/seatmap`)).json();
      return map.counts.held + map.counts.confirmed;
    })
    .toBe(1);
  await Promise.all([a.close(), b.close()]);
});

test('book, pay (after one failed attempt), get a ticket, cancel it', async ({ page, request }) => {
  const show = await demoShow(request, 'club');
  await openSeats(page, show);

  await seat(page, 'A', 7).click();
  await seat(page, 'A', 8).click();
  await expect(page.locator('.bottom-bar-seats')).toContainText('House seating (A7, A8)');
  await expect(page.locator('.bottom-bar-total strong')).toHaveText('₹1,598');
  await page.getByRole('button', { name: 'PROCEED' }).click();

  await expect(page).toHaveURL(/\/checkout\//);
  await expect(page.getByRole('timer')).toContainText(/[0-2]:\d\d/);
  await expect(page.locator('.total-line')).toContainText('₹1,598');

  await page.getByRole('switch', { name: 'Simulate a failed payment' }).click();
  await page.getByRole('button', { name: 'PAY ₹1,598' }).click();
  await expect(page.getByRole('alert')).toContainText('Payment declined');

  await page.getByRole('switch', { name: 'Simulate a failed payment' }).click();
  await page.getByRole('button', { name: 'PAY ₹1,598' }).click();
  await expect(page).toHaveURL(/\/tickets\//);
  await expect(page.locator('.status-stamp')).toHaveText('CONFIRMED');
  await expect(page.locator('.ticket')).toContainText('A7, A8');

  await page.getByRole('button', { name: 'Cancel booking' }).click();
  await page.getByRole('button', { name: 'Yes, cancel' }).click();
  await expect(page.locator('.status-stamp')).toHaveText('CANCELLED');
  const map = await (await request.get(`/shows/${show}/seatmap`)).json();
  expect(map.counts.available).toBe(map.counts.available + map.counts.held + map.counts.confirmed);
});

test('coming back during a hold: seats stay selected and payment picks up where it left off', async ({ page, request }) => {
  const show = await demoShow(request, 'club');
  await openSeats(page, show);
  await seat(page, 'D', 3).click();
  await seat(page, 'D', 4).click();
  await page.getByRole('button', { name: 'PROCEED' }).click();
  await expect(page).toHaveURL(/\/checkout\//);
  const checkoutUrl = page.url();

  await page.goBack();
  await expect(page.locator('.seatmap-viewport svg')).toBeVisible();

  // The held seats look selected (ticked), not "yours", and the bar offers to continue.
  await expect(seat(page, 'D', 3)).toHaveAttribute('aria-label', /on hold for you/);
  await expect(seat(page, 'D', 3)).toHaveAttribute('aria-pressed', 'true');
  const bar = page.locator('.hold-bar');
  await expect(bar).toBeVisible();
  await expect(bar).toContainText('House seating (D3, D4)');
  await expect(bar.getByRole('timer')).toContainText(/On hold for you · [0-2]:\d\d/);
  await expect(bar).toContainText('₹1,598');

  // Tapping a held seat doesn't unselect it or start a second booking.
  await seat(page, 'D', 3).click();
  await expect(page.locator('.toast')).toContainText('continue to payment');

  await bar.getByRole('button', { name: 'CONTINUE TO PAYMENT' }).click();
  await expect(page).toHaveURL(checkoutUrl);
  await expect(page.getByRole('timer')).toBeVisible();

  // Releasing from the seat map frees them for everyone.
  await page.goBack();
  await page.locator('.hold-bar').getByRole('button', { name: 'release' }).click();
  await expect(page.locator('.hold-bar')).toHaveCount(0);
  await expect(seat(page, 'D', 3)).toHaveAttribute('aria-label', /available/);
});

test('standing section: pick a quantity, not a seat', async ({ page, request }) => {
  const show = await demoShow(request, 'arena');
  await page.goto(`/events/${show}`);
  await expect(page.locator('.overview svg')).toBeVisible();

  await page.getByRole('button', { name: /^Gold \(standing\)/ }).click();
  await expect(page.getByRole('dialog')).toContainText('first come, first served');
  await page.getByRole('button', { name: 'One more' }).click(); // 2 -> 3
  await page.getByRole('button', { name: 'BOOK 3 PLACES' }).click();

  await expect(page).toHaveURL(/\/checkout\//);
  await expect(page.locator('.line-items')).toContainText('Gold (standing) × 3');
  await expect(page.locator('.total-line')).toContainText('₹8,997');
});

test('the rush lab sells every seat once and the totals always add up', async ({ page }) => {
  await page.goto('/lab');
  await page.getByRole('button', { name: /The Chai Room/ }).click();
  await page.getByRole('button', { name: 'Create my show' }).click();
  await expect(page.locator('.invariant')).toContainText('= 140 of 140');

  await page.getByRole('button', { name: 'START THE RUSH' }).click();
  await expect(page.locator('.pill', { hasText: /^Done in/ })).toBeVisible({ timeout: 45_000 });
  await expect(page.locator('.invariant.ok')).toBeVisible();
  await expect(page.locator('.stat', { hasText: 'Errors (5xx)' })).toContainText('0');
  await expect(page.locator('.stat', { hasText: '409 seat_taken' })).toBeVisible();
});

test('curl still gets JSON at the root while browsers get the app', async ({ request, page }) => {
  const res = await request.get('/', { headers: { Accept: '*/*' } });
  expect((await res.json()).service).toBe('seat-reservation');
  await page.goto('/');
  await expect(page.locator('.hero-title')).toBeVisible();
});
