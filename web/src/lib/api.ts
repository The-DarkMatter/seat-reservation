// Thin client for the seat-reservation API. Same origin in production; Vite
// proxies these paths to :8080 in development.

import { currentToken, forgetToken } from './identity';

export type Counts = { available: number; held: number; confirmed: number };
export type Point = [number, number];
export type RowSpec = { row: string; seats: number; aisles_after?: number[] };
export type SectionDisplay = { color?: string; points?: Point[]; curve?: number; label_at?: Point };
export type Poster = { from: string; to: string; glyph: string };

export type Section = {
  code: string;
  name: string;
  price_paise: number;
  standing: boolean;
  capacity: number;
  counts: Counts;
  rows?: RowSpec[];
  display?: SectionDisplay;
};

export type Layout = {
  canvas?: { w: number; h: number };
  stage?: { label?: string; points: Point[] };
  poster?: Poster;
  blurb?: string;
  template?: string;
  category?: string;
};

export type Show = {
  id: string;
  name: string;
  price_paise: number;
  per_user_limit: number;
  hold_ttl_seconds?: number;
  total_seats: number;
  venue?: string;
  starts_at?: string;
  counts: Counts;
  sections: Section[];
  layout?: Layout;
  seats: { seat: string; status: string }[];
};

export type ShowSummary = {
  id: string;
  name: string;
  venue?: string;
  starts_at?: string;
  price_paise: number;
  hold_ttl_seconds?: number;
  total_seats: number;
  counts: Counts;
  sections: Section[];
  layout?: Layout;
};

export type SeatMapData = {
  show_id: string;
  counts: Counts;
  sections: { code: string; counts: Counts; states?: string }[];
};

export type ReservationStatus = 'held' | 'confirmed' | 'cancelled' | 'expired';

export type Reservation = {
  reservation_id: string;
  show_id: string;
  user_id: string;
  seats: string[];
  amount_paise: number;
  status: ReservationStatus;
  expires_at?: string;
  shortfall?: { requested: number; unavailable?: string[] };
};

export type Template = { id: string; category: string; name: string; venue: string; poster?: Poster };

export type RushStatus = {
  show_id: string;
  bots: number;
  running: boolean;
  elapsed_ms: number;
  attempts: number;
  reservations: number;
  seats: number;
  paid: number;
  abandoned: number;
  replays: number;
  declined: Record<string, number>;
  errors: number;
};

export type ReserveBody =
  | { seats: string[]; allow_partial?: boolean }
  | { section: string; quantity: number; allow_partial?: boolean };

/** A response the API chose to send: 4xx/5xx with {"error", "message", ...}. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly body: Record<string, unknown> | null,
  ) {
    super(message);
  }
}

// Server clock minus ours, from each response's Date header, so countdowns
// follow the database's idea of when a hold ends rather than this laptop's.
let clockOffset = 0;
export const serverNow = () => Date.now() + clockOffset;

type Options = { body?: unknown; auth?: boolean; headers?: Record<string, string> };

async function request<T>(method: string, path: string, opts: Options = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.auth) headers.Authorization = `Bearer ${await currentToken()}`;

  const res = await fetch(path, {
    method,
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  const date = Date.parse(res.headers.get('Date') ?? '');
  if (!Number.isNaN(date)) clockOffset = date + 500 - Date.now(); // Date has 1s resolution

  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (res.status === 401 && opts.auth && !retried) {
    forgetToken();
    return request<T>(method, path, opts, true);
  }
  if (!res.ok) {
    const body = (data ?? null) as Record<string, unknown> | null;
    throw new ApiError(
      res.status,
      (body?.error as string) ?? `http_${res.status}`,
      (body?.message as string) ?? res.statusText,
      body,
    );
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  listShows: () => request<ShowSummary[]>('GET', '/shows'),
  show: (id: string) => request<Show>('GET', `/shows/${id}`),
  seatMap: (id: string) => request<SeatMapData>('GET', `/shows/${id}/seatmap`),
  mine: (showId?: string) =>
    request<Reservation[]>('GET', `/me/reservations${showId ? `?show_id=${showId}` : ''}`, { auth: true }),
  reservation: (id: string) => request<Reservation>('GET', `/reservations/${id}`, { auth: true }),
  confirm: (id: string) => request<Reservation>('POST', `/reservations/${id}/confirm`, { auth: true }),
  cancel: (id: string) => request<Reservation>('POST', `/reservations/${id}/cancel`, { auth: true }),
  templates: () => request<Template[]>('GET', '/demo/templates'),
  createDemoShow: (template: string, holdTtlSeconds: number) =>
    request<{ id: string; name: string }>('POST', '/demo/shows', {
      body: { template, hold_ttl_seconds: holdTtlSeconds },
    }),
  startRush: (showId: string, bots: number) =>
    request<RushStatus>('POST', `/demo/shows/${showId}/rush`, { body: { bots } }),
  rushStatus: (showId: string) => request<RushStatus>('GET', `/demo/shows/${showId}/rush`),

  /**
   * Reserve with an idempotency key. If the network drops before the answer
   * arrives, retry once with the SAME key: the server either finishes the
   * first attempt's work or replays its stored answer, so a flaky connection
   * can never book twice.
   */
  async reserve(showId: string, body: ReserveBody): Promise<Reservation> {
    const key = crypto.randomUUID();
    const send = () =>
      request<Reservation>('POST', `/shows/${showId}/reserve`, {
        body,
        auth: true,
        headers: { 'Idempotency-Key': key },
      });
    try {
      return await send();
    } catch (e) {
      if (e instanceof TypeError) return send(); // network failure, not an API answer
      throw e;
    }
  },
};
