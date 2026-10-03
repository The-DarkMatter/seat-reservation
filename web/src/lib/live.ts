import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { api, type Reservation, type SeatMapData } from './api';

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

/** Labels this user currently holds or owns. */
export function liveSeats(list: Reservation[]): Set<string> {
  const out = new Set<string>();
  for (const r of list) if (r.status === 'held' || r.status === 'confirmed') r.seats.forEach((s) => out.add(s));
  return out;
}
