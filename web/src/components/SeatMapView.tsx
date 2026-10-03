import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { Section } from '../lib/api';
import { rowAndSeat, rupees } from '../lib/format';
import { neighbour, shade, type SectionGeometry, type SeatPos } from '../lib/layout';

type Props = {
  section: Section;
  geometry: SectionGeometry;
  /** One character per seat from the live seat map: a(vailable) h(eld) c(onfirmed). */
  states?: string;
  selected: Set<string>;
  mine: Set<string>;
  colour: string;
  onToggle?: (label: string) => void;
};

type View = { k: number; x: number; y: number };

const STAGE_SPACE = 2.6; // room above the seats for the "stage this way" bar
const MAX_ZOOM = 6;
const COARSE = typeof matchMedia !== 'undefined' && matchMedia('(pointer: coarse)').matches;

export function SeatMapView({ section, geometry: g, states, selected, mine, colour, onToggle }: Props) {
  const shellRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [view, setView] = useState<View>({ k: 1, x: 0, y: 0 });
  const [hover, setHover] = useState<{ seat: SeatPos; left: number; top: number } | null>(null);
  const [focusIndex, setFocusIndex] = useState(0);
  const [hintKey, setHintKey] = useState(0);
  const viewRef = useRef(view);
  viewRef.current = view;
  const toggleRef = useRef(onToggle);
  toggleRef.current = onToggle;

  const W = g.width;
  const H = g.height + STAGE_SPACE;
  const strong = shade(colour, 0.42);
  const border = shade(colour, 0.22);

  const clamp = (v: View): View => {
    const k = Math.min(MAX_ZOOM, Math.max(1, v.k));
    return {
      k,
      x: Math.min(0, Math.max(W - W * k, v.x)),
      y: Math.min(0, Math.max(H - H * k, v.y)),
    };
  };

  const zoomAt = (px: number, py: number, factor: number) =>
    setView((v) => {
      const k = Math.min(MAX_ZOOM, Math.max(1, v.k * factor));
      return clamp({ k, x: px - (px - v.x) * (k / v.k), y: py - (py - v.y) * (k / v.k) });
    });

  const toSvg = (clientX: number, clientY: number) => {
    const svg = svgRef.current!;
    const m = svg.getScreenCTM();
    if (!m) return { x: W / 2, y: H / 2 };
    const p = new DOMPoint(clientX, clientY).matrixTransform(m.inverse());
    return { x: p.x, y: p.y };
  };

  // Reset zoom when switching sections.
  useEffect(() => setView({ k: 1, x: 0, y: 0 }), [section.code]);

  // Ctrl/Cmd + wheel zooms (a plain wheel scrolls the page, and re-shows the hint).
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const onWheel = (e: WheelEvent) => {
      if (!(e.ctrlKey || e.metaKey)) {
        setHintKey((n) => n + 1);
        return;
      }
      e.preventDefault();
      const p = toSvg(e.clientX, e.clientY);
      zoomAt(p.x, p.y, Math.exp(-e.deltaY * 0.0025));
    };
    svg.addEventListener('wheel', onWheel, { passive: false });
    return () => svg.removeEventListener('wheel', onWheel);
  }, [W, H]);

  // Drag to pan (when zoomed), pinch to zoom, tap/click to pick a seat.
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const gesture = useRef({ moved: 0, downSeat: -1, pinch: 0 });

  const onPointerDown = (e: PointerEvent) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 1) {
      const idx = (e.target as Element).getAttribute?.('data-index');
      gesture.current = { moved: 0, downSeat: idx == null ? -1 : Number(idx), pinch: 0 };
    }
  };

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      const map = pointers.current;
      const prev = map.get(e.pointerId);
      if (!prev) return;
      const svg = svgRef.current;
      if (!svg) return;
      const scale = W / svg.getBoundingClientRect().width; // client px -> svg units
      if (map.size === 1) {
        const dx = e.clientX - prev.x;
        const dy = e.clientY - prev.y;
        gesture.current.moved += Math.abs(dx) + Math.abs(dy);
        if (viewRef.current.k > 1) setView((v) => clamp({ ...v, x: v.x + dx * scale, y: v.y + dy * scale }));
      } else if (map.size === 2) {
        const [a, b] = [...map.values()];
        const before = Math.hypot(a.x - b.x, a.y - b.y);
        map.set(e.pointerId, { x: e.clientX, y: e.clientY });
        const [c, d] = [...map.values()];
        const after = Math.hypot(c.x - d.x, c.y - d.y);
        gesture.current.moved += 20;
        if (before > 0) {
          const mid = toSvg((c.x + d.x) / 2, (c.y + d.y) / 2);
          zoomAt(mid.x, mid.y, after / before);
        }
        return;
      }
      map.set(e.pointerId, { x: e.clientX, y: e.clientY });
    };
    const onUp = (e: PointerEvent) => {
      const map = pointers.current;
      if (!map.has(e.pointerId)) return;
      map.delete(e.pointerId);
      if (map.size === 0 && gesture.current.moved < 8 && gesture.current.downSeat >= 0) {
        const seat = g.seats[gesture.current.downSeat];
        if (seat) {
          setFocusIndex(seat.index);
          toggleRef.current?.(seat.label);
        }
      }
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [g, W, H]);

  // Hover card (mouse only): row / seat / class / price, like a real box office.
  const onHover = (e: PointerEvent) => {
    if (e.pointerType !== 'mouse' || pointers.current.size) return;
    const target = e.target as Element;
    const idx = target.getAttribute?.('data-index');
    if (idx == null) {
      if (hover) setHover(null);
      return;
    }
    const seat = g.seats[Number(idx)];
    if (hover?.seat.index === seat.index) return;
    const box = target.getBoundingClientRect();
    const shell = shellRef.current!.getBoundingClientRect();
    setHover({ seat, left: box.left + box.width / 2 - shell.left, top: box.top - shell.top });
  };

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key.startsWith('Arrow')) {
      e.preventDefault();
      const next = neighbour(g, focusIndex, e.key);
      setFocusIndex(next);
      svgRef.current?.querySelector<SVGElement>(`[data-index="${next}"]`)?.focus();
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      const seat = g.seats[focusIndex];
      if (seat) onToggle?.(seat.label);
    } else if (e.key === '+' || e.key === '=') {
      zoomAt(W / 2, H / 2, 1.4);
    } else if (e.key === '-') {
      zoomAt(W / 2, H / 2, 1 / 1.4);
    }
  };

  const seatState = (s: SeatPos) => {
    if (mine.has(s.label)) return 'mine';
    if (selected.has(s.label)) return 'selected';
    const c = states?.[s.index] ?? 'a';
    return c === 'c' ? 'sold' : c === 'h' ? 'held' : 'available';
  };

  const ticks = useMemo(
    () => g.seats.filter((s) => selected.has(s.label) || mine.has(s.label)),
    [g, selected, mine],
  );

  return (
    <div ref={shellRef} class={`seatmap-viewport${view.k > 1 ? ' zoomed' : ''}`}>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${W} ${H}`}
        role="group"
        aria-label={`${section.name} seats. Arrow keys move between seats, Enter selects.`}
        onPointerDown={onPointerDown}
        onPointerMove={onHover}
        onPointerLeave={() => setHover(null)}
        onKeyDown={onKeyDown}
      >
        <g transform={`translate(${view.x} ${view.y}) scale(${view.k})`}>
          <rect x={W * 0.2} y={0.5} width={W * 0.6} height={0.95} rx={0.45} fill="#e4e3ea" />
          <text x={W / 2} y={0.99} class="stage-label" style={{ fontSize: '0.62px' }}>
            STAGE THIS WAY
          </text>
          <g transform={`translate(0 ${STAGE_SPACE})`}>
            {g.rows.map((r) => (
              <g key={r.row}>
                <text x={r.left[0]} y={r.left[1]} class="row-letter">
                  {r.row}
                </text>
                <text x={r.right[0]} y={r.right[1]} class="row-letter">
                  {r.row}
                </text>
              </g>
            ))}
            {g.seats.map((s) => {
              const state = seatState(s);
              const small = state === 'sold' || state === 'held';
              const r = small ? 0.17 : 0.4;
              const fill =
                state === 'available' ? colour
                : state === 'selected' ? strong
                : state === 'mine' ? 'var(--mine)'
                : state === 'held' ? 'var(--held)'
                : 'var(--sold)';
              const { row, seat } = rowAndSeat(s.label);
              return (
                <circle
                  key={s.label}
                  data-index={s.index}
                  class={`seat ${state}`}
                  cx={s.x}
                  cy={s.y}
                  r={r}
                  style={{ r: `${r}px` }}
                  fill={fill}
                  stroke={state === 'available' ? border : 'none'}
                  stroke-width={0.05}
                  role="button"
                  tabindex={s.index === focusIndex ? 0 : -1}
                  aria-pressed={state === 'selected'}
                  aria-label={`${row ? `Row ${row} seat ${seat}` : `Seat ${seat}`}, ${section.name}, ${rupees(section.price_paise)}, ${
                    state === 'available' ? 'available' : state === 'selected' ? 'selected' : state === 'mine' ? 'yours' : state === 'held' ? 'on hold' : 'sold'
                  }`}
                />
              );
            })}
            {ticks.map((s) => (
              <path
                key={`t-${s.label}`}
                d={`M${s.x - 0.17} ${s.y + 0.01} l0.12 0.13 l0.23 -0.27`}
                fill="none"
                stroke="#fff"
                stroke-width={0.08}
                stroke-linecap="round"
                stroke-linejoin="round"
                pointer-events="none"
              />
            ))}
          </g>
        </g>
      </svg>

      <div class="zoom-controls">
        <button aria-label="Zoom in" onClick={() => zoomAt(W / 2, H / 2, 1.5)}>+</button>
        <button aria-label="Zoom out" onClick={() => zoomAt(W / 2, H / 2, 1 / 1.5)}>−</button>
      </div>
      <div class="zoom-hint" key={hintKey} aria-hidden="true">
        {COARSE ? 'Pinch to zoom' : 'Use Ctrl + scroll to zoom in'}
      </div>

      {hover && (
        <div class="seat-card" style={{ left: `${hover.left}px`, top: `${hover.top}px` }} role="tooltip">
          <div class="seat-card-top">
            <div>
              <small>ROW</small>
              <strong>{rowAndSeat(hover.seat.label).row || '–'}</strong>
            </div>
            <div>
              <small>SEAT</small>
              <strong>{rowAndSeat(hover.seat.label).seat}</strong>
            </div>
          </div>
          <div class="seat-card-bottom" style={{ background: strong }}>
            <span>{section.name}</span>
            <span>{rupees(section.price_paise)}</span>
          </div>
        </div>
      )}
    </div>
  );
}
