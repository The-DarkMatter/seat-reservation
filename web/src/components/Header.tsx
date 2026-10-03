import { useState } from 'preact/hooks';
import { NAME_PATTERN, randomName, rename, userName } from '../lib/identity';
import { currentPath, linkTo } from '../lib/router';
import { ChairIcon } from './Icons';

const NAV = [
  { href: '/', label: 'Events' },
  { href: '/lab', label: 'Rush lab' },
  { href: '/me', label: 'My bookings' },
];

export function avatarColour(name: string): string {
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 360;
  return `hsl(${h} 62% 46%)`;
}

export function Wordmark() {
  return (
    <a class="wordmark" {...linkTo('/')} aria-label="Kursi home">
      <ChairIcon size={30} class="wordmark-mark" />
      <span>
        <span class="wordmark-text">KURSI</span>
        <span class="wordmark-sub">EK SEAT · EK MAALIK</span>
      </span>
    </a>
  );
}

function isCurrent(href: string): boolean {
  const path = currentPath.value;
  return href === '/' ? path === '/' || path.startsWith('/events') : path.startsWith(href);
}

export function Header() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <header class="site-header">
        <div class="container">
          <Wordmark />
          <nav class="nav" aria-label="Main">
            {NAV.map((n) => (
              <a key={n.href} {...linkTo(n.href)} aria-current={isCurrent(n.href) ? 'page' : undefined}>
                {n.label}
              </a>
            ))}
          </nav>
          <button class="who" onClick={() => setOpen(!open)} aria-expanded={open} aria-label="Your guest identity">
            <span class="who-avatar" style={{ background: avatarColour(userName.value) }}>
              {userName.value.charAt(0).toUpperCase()}
            </span>
            <span class="who-name">{userName.value}</span>
          </button>
        </div>
        {open && <WhoMenu onClose={() => setOpen(false)} />}
      </header>
      <nav class="mobile-nav" aria-label="Main">
        {NAV.map((n) => (
          <a key={n.href} {...linkTo(n.href)} aria-current={isCurrent(n.href) ? 'page' : undefined}>
            {n.label}
          </a>
        ))}
      </nav>
    </>
  );
}

function WhoMenu({ onClose }: { onClose: () => void }) {
  const [draft, setDraft] = useState(userName.value);
  const valid = NAME_PATTERN.test(draft);
  return (
    <div class="who-menu" role="dialog" aria-label="Guest identity">
      <strong>You're booking as a guest</strong>
      <p class="muted" style={{ margin: '4px 0 10px', fontSize: '14px' }}>
        Every browser tab is a different buyer. Open a second window and race yourself for the same seat.
      </p>
      <label class="field" style={{ marginBottom: 0 }}>
        Guest name
        <input
          value={draft}
          maxLength={64}
          onInput={(e) => setDraft((e.target as HTMLInputElement).value.replace(/\s+/g, '-'))}
          aria-invalid={!valid}
        />
      </label>
      <div style={{ display: 'flex', gap: '8px' }}>
        <button
          class="btn btn-sm"
          disabled={!valid}
          onClick={() => {
            rename(draft);
            onClose();
          }}
        >
          Save
        </button>
        <button class="btn btn-sm btn-ghost" onClick={() => setDraft(randomName())}>
          Shuffle
        </button>
        <button
          class="btn btn-sm btn-ghost"
          onClick={() => window.open(`${location.pathname}?as=new`, '_blank', 'noopener')}
        >
          New window
        </button>
      </div>
    </div>
  );
}
