import { useState } from 'preact/hooks';
import type { Section } from '../lib/api';
import { plural, rupees, seatShort } from '../lib/format';
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
  const groups = new Map<string, string[]>();
  let total = 0;
  for (const label of selection) {
    const s = sectionOf(label);
    if (!s) continue;
    total += s.price_paise;
    groups.set(s.name, [...(groups.get(s.name) ?? []), seatShort(label)]);
  }
  const summary = [...groups].map(([name, seats]) => `${name} (${seats.join(', ')})`).join(' · ');
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
