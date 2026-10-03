// Seat geometry: turns a section's rows (from the API) into positions to draw.
// Units are "seat pitches": one seat is 1 wide, rows are ROW_GAP apart. Seat
// order matches the API's seat order (rows in order, seats 1..n), which is also
// the order of characters in the seat map's `states` string.

import type { Point, RowSpec } from './api';

export const ROW_GAP = 1.3;
export const AISLE = 1.1;
export const PAD_X = 2.2; // room for row letters on both sides
export const PAD_Y = 0.9;

export type SeatPos = { label: string; row: string; n: number; x: number; y: number; index: number; rowIndex: number };
export type RowLabel = { row: string; left: Point; right: Point };
export type SectionGeometry = { seats: SeatPos[]; rows: RowLabel[]; width: number; height: number };

/**
 * @param rows   the section's rows; absent for a flat show (labels come from {@code flatLabels})
 * @param curve  0 = straight rows; 0.1-0.2 bends the ends of each row toward the stage
 */
export function sectionGeometry(code: string, rows: RowSpec[] | undefined, curve = 0,
                                flatLabels: string[] = []): SectionGeometry {
  const raw: Omit<SeatPos, 'index'>[] = [];
  const rowSpans: { row: string; first: number; last: number }[] = [];

  if (rows && rows.length) {
    const widths = rows.map((r) => seatX(r.seats, r.aisles_after ?? []));
    const maxWidth = Math.max(...widths);
    rows.forEach((r, rowIndex) => {
      const offset = (maxWidth - widths[rowIndex]) / 2;
      const first = raw.length;
      const aisles = r.aisles_after ?? [];
      for (let n = 1; n <= r.seats; n++) {
        raw.push({ label: `${code}-${r.row}${n}`, row: r.row, n, rowIndex, x: offset + seatX(n, aisles), y: rowIndex * ROW_GAP });
      }
      rowSpans.push({ row: r.row, first, last: raw.length - 1 });
    });
    if (curve) {
      const cx = maxWidth / 2;
      const half = Math.max(cx, 1);
      for (const s of raw) s.y -= (curve * (s.x - cx) ** 2) / half;
    }
  } else {
    // A flat show (created from a plain seat list): lay the seats out as a grid.
    const perRow = Math.min(40, Math.max(8, Math.ceil(Math.sqrt(flatLabels.length * 2.2))));
    const rowCount = Math.ceil(flatLabels.length / perRow);
    for (let r = 0; r < rowCount; r++) {
      const chunk = flatLabels.slice(r * perRow, (r + 1) * perRow);
      const offset = (perRow - chunk.length) / 2;
      const first = raw.length;
      chunk.forEach((label, i) => raw.push({ label, row: '', n: i + 1, rowIndex: r, x: offset + i, y: r * ROW_GAP }));
      rowSpans.push({ row: '', first, last: raw.length - 1 });
    }
  }

  if (!raw.length) return { seats: [], rows: [], width: 2 * PAD_X, height: 2 * PAD_Y };
  const minX = Math.min(...raw.map((s) => s.x));
  const minY = Math.min(...raw.map((s) => s.y));
  const maxX = Math.max(...raw.map((s) => s.x));
  const maxY = Math.max(...raw.map((s) => s.y));
  const seats = raw.map((s, index) => ({ ...s, index, x: s.x - minX + PAD_X, y: s.y - minY + PAD_Y }));
  const labels = rowSpans
    .filter((r) => r.row)
    .map((r) => {
      const a = seats[r.first];
      const b = seats[r.last];
      return { row: r.row, left: [a.x - 1.4, a.y] as Point, right: [b.x + 1.4, b.y] as Point };
    });
  return { seats, rows: labels, width: maxX - minX + 2 * PAD_X, height: maxY - minY + 2 * PAD_Y };
}

/** x of seat n in a row (0-based pitch), counting the aisle gaps before it. */
function seatX(n: number, aislesAfter: number[]): number {
  return n - 1 + AISLE * aislesAfter.filter((a) => a < n).length;
}

/** Keyboard navigation: the seat to move to from {@code index} with an arrow key. */
export function neighbour(g: SectionGeometry, index: number, key: string): number {
  const cur = g.seats[index];
  if (!cur) return index;
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    const next = g.seats[index + (key === 'ArrowLeft' ? -1 : 1)];
    return next && next.rowIndex === cur.rowIndex ? next.index : index;
  }
  if (key === 'ArrowUp' || key === 'ArrowDown') {
    const targetRow = cur.rowIndex + (key === 'ArrowUp' ? -1 : 1);
    let best = index;
    let bestDx = Infinity;
    for (const s of g.seats) {
      if (s.rowIndex !== targetRow) continue;
      const dx = Math.abs(s.x - cur.x);
      if (dx < bestDx) {
        bestDx = dx;
        best = s.index;
      }
    }
    return best;
  }
  return index;
}

export function centroid(points: Point[]): Point {
  const n = points.length || 1;
  return [points.reduce((a, p) => a + p[0], 0) / n, points.reduce((a, p) => a + p[1], 0) / n];
}

export function pointsAttr(points: Point[]): string {
  return points.map((p) => `${p[0]},${p[1]}`).join(' ');
}

/** Darkens a #rrggbb colour by mixing it with black (for borders and the selected state). */
export function shade(hex: string, amount: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  const f = (c: number) => Math.round(c * (1 - amount)).toString(16).padStart(2, '0');
  return `#${f((n >> 16) & 255)}${f((n >> 8) & 255)}${f(n & 255)}`;
}

export const FALLBACK_COLOURS = ['#A8C7F5', '#F3C978', '#C4B5F6', '#F5B5C4', '#9DE9EF', '#E7A6F0', '#F7A98B'];

export function sectionColour(display: { color?: string } | undefined, index: number): string {
  return display?.color ?? FALLBACK_COLOURS[index % FALLBACK_COLOURS.length];
}
