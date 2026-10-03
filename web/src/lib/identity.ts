// Who this tab is. Each browser tab gets its own playful guest identity (kept in
// sessionStorage), so two windows side by side are two different buyers.

import { signal } from '@preact/signals';

const KEY = 'kursi.identity';
const FIRST = ['Chai', 'Masala', 'Filmy', 'Jugaadu', 'Bindaas', 'Toofani', 'Jhakaas', 'Dhinchak', 'Mast', 'Desi'];
const SECOND = ['Tiger', 'Peacock', 'Langur', 'Mongoose', 'Cheetah', 'Myna', 'Elephant', 'Koel', 'Otter', 'Gharial'];
export const NAME_PATTERN = /^[A-Za-z0-9._@-]{1,64}$/;

type Stored = { name: string; token?: string; expiresAt?: number };

function pick<T>(list: T[]): T {
  return list[Math.floor(Math.random() * list.length)];
}

export function randomName(): string {
  return `${pick(FIRST)}-${pick(SECOND)}-${10 + Math.floor(Math.random() * 90)}`;
}

function load(): Stored {
  const params = new URLSearchParams(location.search);
  if (params.has('as')) {
    // "Open as another user" links carry ?as=new; sessionStorage is copied into
    // windows opened from this one, so start fresh explicitly and tidy the URL.
    params.delete('as');
    const query = params.toString();
    history.replaceState(null, '', location.pathname + (query ? `?${query}` : ''));
    return save({ name: randomName() });
  }
  try {
    const raw = sessionStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Stored;
      if (parsed.name && NAME_PATTERN.test(parsed.name)) return parsed;
    }
  } catch {
    // storage blocked: fall through to a fresh in-memory identity
  }
  return save({ name: randomName() });
}

function save(value: Stored): Stored {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(value));
  } catch {
    // private mode or blocked storage: the identity just won't survive a reload
  }
  return value;
}

let stored = load();
export const userName = signal(stored.name);

export function rename(name: string) {
  stored = save({ name });
  userName.value = name;
}

export function forgetToken() {
  stored = save({ name: stored.name });
}

/** A bearer token for this tab's user, minted on first use (POST /auth/token). */
export async function currentToken(): Promise<string> {
  if (stored.token && (stored.expiresAt ?? 0) > Date.now() + 60_000) return stored.token;
  const res = await fetch('/auth/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_id: stored.name }),
  });
  if (!res.ok) throw new Error(`could not sign in as ${stored.name}`);
  const body = (await res.json()) as { token: string; expires_in: number };
  stored = save({ name: stored.name, token: body.token, expiresAt: Date.now() + body.expires_in * 1000 });
  return body.token;
}
