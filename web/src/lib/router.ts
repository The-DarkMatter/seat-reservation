// A minimal history router: one signal holding the current path.

import { signal } from '@preact/signals';

export const currentPath = signal(location.pathname);

export function navigate(to: string, replace = false) {
  if (replace) history.replaceState(null, '', to);
  else history.pushState(null, '', to);
  currentPath.value = location.pathname;
  window.scrollTo({ top: 0 });
}

window.addEventListener('popstate', () => {
  currentPath.value = location.pathname;
});

/** Matches "/events/:id" style patterns; returns the params or null. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const p = pattern.split('/').filter(Boolean);
  const s = path.split('/').filter(Boolean);
  if (p.length !== s.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < p.length; i++) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = decodeURIComponent(s[i]);
    else if (p[i] !== s[i]) return null;
  }
  return params;
}

/** Use on <a> elements: keeps normal links (new tab, copy link) but routes plain clicks in-app. */
export function linkTo(href: string) {
  return {
    href,
    onClick: (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      e.preventDefault();
      navigate(href);
    },
  };
}
