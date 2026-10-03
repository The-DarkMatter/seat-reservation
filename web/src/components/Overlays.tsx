import type { ComponentChildren } from 'preact';
import { useEffect } from 'preact/hooks';
import { toasts } from '../lib/toast';

/** Centered dialog. Escape and a click on the backdrop close it. */
export function Modal({ onClose, children, label }: { onClose: () => void; children: ComponentChildren; label: string }) {
  useEscape(onClose);
  return (
    <div class="overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div class="modal" role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </div>
    </div>
  );
}

/** Bottom sheet (standing-section quantity picker). */
export function Sheet({ onClose, children, label }: { onClose: () => void; children: ComponentChildren; label: string }) {
  useEscape(onClose);
  return (
    <div class="overlay sheet-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div class="sheet" role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </div>
    </div>
  );
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
}

export function Toasts() {
  return (
    <div class="toasts" role="status" aria-live="polite">
      {toasts.value.map((t) => (
        <div key={t.id} class={`toast ${t.tone}`}>
          {t.text}
        </div>
      ))}
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div class="loading" aria-busy="true">
      <div class="spinner" aria-label={label} />
    </div>
  );
}
