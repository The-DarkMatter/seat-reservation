const rupeeFormat = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });
const dateFormat = new Intl.DateTimeFormat('en-IN', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  timeZone: 'Asia/Kolkata',
});
const timeFormat = new Intl.DateTimeFormat('en-IN', {
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
  timeZone: 'Asia/Kolkata',
});

/** 680000 paise -> "₹6,800"; 149950 -> "₹1,499.5" (money is integer paise end to end). */
export function rupees(paise: number): string {
  return `₹${rupeeFormat.format(paise / 100)}`;
}

/** "Sun, 1 Nov · 7:30 pm" in India time. */
export function eventDate(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return `${dateFormat.format(d)} · ${timeFormat.format(d)}`;
}

/** "GOLD-X18" -> "X18": the section is shown separately. */
export function seatShort(label: string): string {
  const dash = label.indexOf('-');
  return dash > 0 ? label.slice(dash + 1) : label;
}

/** Row and seat number out of a generated label ("GOLD-X18" -> X, 18). */
export function rowAndSeat(label: string): { row: string; seat: string } {
  const m = /^[A-Z0-9]+-([A-Z]{1,3})(\d+)$/.exec(label);
  return m ? { row: m[1], seat: m[2] } : { row: '', seat: seatShort(label) };
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

export function mmss(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
