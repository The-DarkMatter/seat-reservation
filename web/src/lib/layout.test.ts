import { describe, expect, it } from 'vitest';
import { AISLE, PAD_X, PAD_Y, ROW_GAP, neighbour, sectionGeometry, shade } from './layout';
import { mmss, rowAndSeat, rupees, seatShort } from './format';

describe('sectionGeometry', () => {
  it('keeps the API seat order, so states[i] is seat i', () => {
    const g = sectionGeometry('GOLD', [
      { row: 'X', seats: 3 },
      { row: 'Y', seats: 2 },
    ]);
    expect(g.seats.map((s) => s.label)).toEqual(['GOLD-X1', 'GOLD-X2', 'GOLD-X3', 'GOLD-Y1', 'GOLD-Y2']);
    expect(g.seats.map((s) => s.index)).toEqual([0, 1, 2, 3, 4]);
  });

  it('opens a gap after each aisle seat', () => {
    const g = sectionGeometry('A', [{ row: 'A', seats: 4, aisles_after: [2] }]);
    const xs = g.seats.map((s) => s.x - PAD_X);
    [0, 1, 2 + AISLE, 3 + AISLE].forEach((x, i) => expect(xs[i]).toBeCloseTo(x));
  });

  it('centres shorter rows under longer ones', () => {
    const g = sectionGeometry('A', [
      { row: 'A', seats: 6 },
      { row: 'B', seats: 4 },
    ]);
    const rowB = g.seats.filter((s) => s.row === 'B');
    expect(rowB[0].x - PAD_X).toBe(1);
    expect(rowB[0].y - PAD_Y).toBeCloseTo(ROW_GAP);
  });

  it('bends row ends toward the stage and stays inside the box', () => {
    const g = sectionGeometry('A', [{ row: 'A', seats: 11 }, { row: 'B', seats: 11 }], 0.2);
    const rowA = g.seats.filter((s) => s.row === 'A');
    expect(rowA[0].y).toBeLessThan(rowA[5].y);
    expect(Math.min(...g.seats.map((s) => s.y))).toBeCloseTo(PAD_Y);
    expect(Math.max(...g.seats.map((s) => s.y))).toBeLessThanOrEqual(g.height);
  });

  it('lays a flat show out as a grid with no row letters', () => {
    const labels = Array.from({ length: 50 }, (_, i) => `S${i + 1}`);
    const g = sectionGeometry('GENERAL', undefined, 0, labels);
    expect(g.seats).toHaveLength(50);
    expect(g.rows).toHaveLength(0);
    expect(new Set(g.seats.map((s) => `${s.x},${s.y}`)).size).toBe(50);
  });

  it('moves between seats with the arrow keys', () => {
    const g = sectionGeometry('A', [
      { row: 'A', seats: 3 },
      { row: 'B', seats: 3 },
    ]);
    expect(neighbour(g, 0, 'ArrowRight')).toBe(1);
    expect(neighbour(g, 2, 'ArrowRight')).toBe(2); // end of the row
    expect(neighbour(g, 1, 'ArrowDown')).toBe(4);
    expect(neighbour(g, 4, 'ArrowUp')).toBe(1);
    expect(neighbour(g, 0, 'ArrowUp')).toBe(0);
  });
});

describe('format', () => {
  it('formats paise as rupees with Indian grouping', () => {
    expect(rupees(680000)).toBe('₹6,800');
    expect(rupees(1299900)).toBe('₹12,999');
    expect(rupees(149950)).toBe('₹1,499.5');
    expect(rupees(1000000000)).toBe('₹1,00,00,000');
  });

  it('reads row and seat out of generated labels', () => {
    expect(rowAndSeat('GOLD-X18')).toEqual({ row: 'X', seat: '18' });
    expect(rowAndSeat('A12')).toEqual({ row: '', seat: 'A12' });
    expect(seatShort('DIAMOND-AA7')).toBe('AA7');
  });

  it('counts down in m:ss', () => {
    expect(mmss(299_400)).toBe('5:00');
    expect(mmss(61_000)).toBe('1:01');
    expect(mmss(-5)).toBe('0:00');
  });

  it('darkens colours for borders', () => {
    expect(shade('#ffffff', 0.5)).toBe('#808080');
    expect(shade('not-a-colour', 0.5)).toBe('not-a-colour');
  });
});
