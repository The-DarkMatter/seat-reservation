import { useEffect, useState } from 'preact/hooks';
import { serverNow, type Reservation, type Section } from '../lib/api';
import { mmss, plural, rupees, seatShort } from '../lib/format';
import { Sheet } from './Overlays';
import { StandingIcon } from './Icons';

/** "All or nothing" vs "book what's left": what to do if some seats go while you decide. */
export function PartialToggle({ partial, onChange }: { partial: boolean; onChange: (v: boolean) => void }) {
  return (
    <div class="partial-toggle" role="group" aria-label="If some seats are taken before you book">
      <button aria-pressed={!partial} onClick={() => onChange(false)} title="Book every seat or none of them">
        All or nothing
      </button>
      <button aria-pressed={partial} onClick={() => onChange(true)} title="Book whichever of these seats are still free">
        Book what's left
      </button>
    </div>
  );
}

function seatSummary(labels: string[], sectionOf: (label: string) => Section | undefined) {
  const groups = new Map<string, string[]>();
  for (const label of labels) {
    const name = sectionOf(label)?.name ?? 'Seats';
    groups.set(name, [...(groups.get(name) ?? []), seatShort(label)]);
  }
  return [...groups].map(([name, seats]) => `${name} (${seats.join(', ')})`).join(' · ');
}

/** m:ss left on a hold, ticking on server time; calls onExpired once when it runs out. */
export function useCountdown(expiresAt: string | undefined, onExpired?: () => void) {
  const [now, setNow] = useState(serverNow());
  useEffect(() => {
    const t = setInterval(() => setNow(serverNow()), 500);
    return () => clearInterval(t);
  }, []);
  const left = expiresAt ? Date.parse(expiresAt) - now : 0;
  useEffect(() => {
    if (expiresAt && left <= 0) onExpired?.();
  }, [left <= 0]);
  return left;
}

type HoldBarProps = {
  hold: Reservation;
  others: number;
  sectionOf: (label: string) => Section | undefined;
  onContinue: () => void;
  onRelease: () => void;
  onExpired: () => void;
};

/**
 * Back on the seat map while seats are on hold for you: they stay selected and
 * this bar offers to pick up the payment where you left it, with the hold's
 * real countdown.
 */
export function HoldBar({ hold, others, sectionOf, onContinue, onRelease, onExpired }: HoldBarProps) {
  const left = useCountdown(hold.expires_at, onExpired);
  return (
    <div class="bottom-bar open hold-bar">
      <div class="container">
        <div class="bottom-bar-seats">
          <small>
            <span class={`pill ${left < 60_000 ? 'pill-bad' : 'pill-warn'}`} role="timer">
              On hold for you · {mmss(left)}
            </span>{' '}
            <button class="back-link" style={{ padding: 0, fontSize: '13px' }} onClick={onRelease}>
              release
            </button>
            {others > 0 && <span class="muted"> · +{plural(others, 'other hold')} in My bookings</span>}
          </small>
          <div title={seatSummary(hold.seats, sectionOf)}>{seatSummary(hold.seats, sectionOf)}</div>
        </div>
        <div class="bottom-bar-total">
          <strong>{rupees(hold.amount_paise)}</strong>
          <small>{plural(hold.seats.length, 'ticket')}</small>
        </div>
        <button class="btn btn-display proceed" onClick={onContinue}>
          CONTINUE TO PAYMENT
        </button>
      </div>
    </div>
  );
}

type BarProps = {
  selection: string[];
  sectionOf: (label: string) => Section | undefined;
  partial: boolean;
  onPartial: (v: boolean) => void;
  busy: boolean;
  onProceed: () => void;
  onClear: () => void;
};

/** Slides up once something is selected: which seats, how much, and the big PROCEED. */
export function BottomBar({ selection, sectionOf, partial, onPartial, busy, onProceed, onClear }: BarProps) {
  const open = selection.length > 0;
  const total = selection.reduce((sum, label) => sum + (sectionOf(label)?.price_paise ?? 0), 0);
  const summary = seatSummary(selection, sectionOf);
  return (
    <div class={`bottom-bar${open ? ' open' : ''}`} aria-hidden={!open}>
      <div class="container">
        <div class="bottom-bar-seats">
          <small>
            {plural(selection.length, 'seat')} selected ·{' '}
            <button class="back-link" style={{ padding: 0, fontSize: '13px' }} onClick={onClear} tabIndex={open ? 0 : -1}>
              clear
            </button>
          </small>
          <div title={summary}>{summary}</div>
          {selection.length > 1 && <PartialToggle partial={partial} onChange={onPartial} />}
        </div>
        <div class="bottom-bar-total">
          <strong>{rupees(total)}</strong>
          <small>{plural(selection.length, 'ticket')}</small>
        </div>
        <button class="btn btn-display proceed" disabled={!open || busy} onClick={onProceed} tabIndex={open ? 0 : -1}>
          {busy ? 'HOLDING…' : 'PROCEED'}
        </button>
      </div>
    </div>
  );
}

type SheetProps = {
  section: Section;
  available: number;
  maxPerUser: number;
  busy: boolean;
  onBook: (quantity: number, partial: boolean) => void;
  onClose: () => void;
};

/** Standing sections are first come, first served: pick how many, not which. */
export function StandingSheet({ section, available, maxPerUser, busy, onBook, onClose }: SheetProps) {
  const max = Math.max(1, Math.min(maxPerUser, 10, available));
  const [qty, setQty] = useState(Math.min(2, max));
  const [partial, setPartial] = useState(false);
  return (
    <Sheet onClose={onClose} label={`${section.name}: choose how many`}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', alignItems: 'start' }}>
        <div>
          <span class="pill">
            <StandingIcon /> Standing · first come, first served
          </span>
          <h2 style={{ fontSize: '24px', fontWeight: 800, margin: '10px 0 2px' }}>{section.name}</h2>
          <p class="muted" style={{ margin: 0 }}>
            {rupees(section.price_paise)} each · {available} places left
          </p>
        </div>
        <button class="icon-btn" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '22px 0' }}>
        <div class="stepper">
          <button aria-label="One fewer" onClick={() => setQty(Math.max(1, qty - 1))} disabled={qty <= 1}>
            −
          </button>
          <output aria-live="polite">{qty}</output>
          <button aria-label="One more" onClick={() => setQty(Math.min(max, qty + 1))} disabled={qty >= max}>
            +
          </button>
        </div>
        <div class="bottom-bar-total">
          <strong>{rupees(section.price_paise * qty)}</strong>
          <small>{plural(qty, 'ticket')}</small>
        </div>
      </div>
      {qty > 1 && (
        <div style={{ marginBottom: '16px' }}>
          <PartialToggle partial={partial} onChange={setPartial} />
        </div>
      )}
      <button class="btn btn-display btn-block" disabled={busy || available === 0} onClick={() => onBook(qty, partial)}>
        {busy ? 'HOLDING…' : `BOOK ${qty} ${qty === 1 ? 'PLACE' : 'PLACES'}`}
      </button>
    </Sheet>
  );
}
