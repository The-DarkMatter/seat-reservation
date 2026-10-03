import { signal } from '@preact/signals';

export type Toast = { id: number; text: string; tone: 'info' | 'warn' | 'good' };

export const toasts = signal<Toast[]>([]);
let next = 1;

export function toast(text: string, tone: Toast['tone'] = 'info', ms = 4200) {
  const id = next++;
  toasts.value = [...toasts.value, { id, text, tone }].slice(-4);
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }, ms);
}
