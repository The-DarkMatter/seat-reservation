import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { api, serverNow, type Reservation, type SeatMapData } from './api';

/**
 * Polls GET /shows/{id}/seatmap. The server caches each seat map for 500 ms, so
 * however many people are watching, MySQL sees about two reads a second.
 * Pauses while the tab is hidden.
 */
export function useSeatMap(showId: string | undefined, intervalMs = 1000) {
  const [data, setData] = useState<SeatMapData | null>(null);
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    if (!showId) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      if (document.visibilityState === 'visible') {
        try {
          const next = await api.seatMap(showId);
          if (!stopped) setData(next);
        } catch {
          // transient: the next tick tries again
        }
      }
      if (!stopped) timer = setTimeout(tick, intervalMs);
    };
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [showId, intervalMs, nonce]);
  const refresh = useCallback(() => setNonce((n) => n + 1), []);
  return [data, refresh] as const;
}

/** This tab's reservations for a show, refreshed every few seconds and on demand. */
export function useMine(showId: string | undefined, intervalMs = 5000) {
  const [list, setList] = useState<Reservation[]>([]);
  const [nonce, setNonce] = useState(0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    if (!showId) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const next = await api.mine(showId);
        if (alive.current) setList(next);
      } catch {
        // ignore; try again later
      }
      if (alive.current) timer = setTimeout(tick, intervalMs);
    };
    tick();
    return () => {
      alive.current = false;
      clearTimeout(timer);
    };
  }, [showId, intervalMs, nonce]);
  const refresh = useCallback(() => setNonce((n) => n + 1), []);
  return [list, refresh] as const;
}

/**
 * This user's seats for a show, split by state: paid (confirmed) seats, and
 * live holds still waiting for payment (soonest to expire first).
 */
export function mySeats(list: Reservation[], now = serverNow()) {
  const paid = new Set<string>();
  const holding = new Set<string>();
  const holds: Reservation[] = [];
  for (const r of list) {
    if (r.status === 'confirmed') r.seats.forEach((s) => paid.add(s));
    if (r.status === 'held' && r.expires_at && Date.parse(r.expires_at) > now) {
      holds.push(r);
      r.seats.forEach((s) => holding.add(s));
    }
  }
  holds.sort((a, b) => Date.parse(a.expires_at!) - Date.parse(b.expires_at!));
  return { paid, holding, holds, all: new Set([...paid, ...holding]) };
}
