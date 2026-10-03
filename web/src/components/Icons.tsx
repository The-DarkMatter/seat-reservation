type IconProps = { size?: number; class?: string };

/** The Kursi chair: the brand mark. */
export function ChairIcon({ size = 28, class: cls }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" class={cls} aria-hidden="true">
      <rect x="8" y="3" width="16" height="13" rx="3" fill="currentColor" />
      <rect x="5" y="16.5" width="22" height="4.5" rx="2" fill="currentColor" />
      <rect x="7" y="21" width="3" height="8" rx="1.5" fill="currentColor" />
      <rect x="22" y="21" width="3" height="8" rx="1.5" fill="currentColor" />
      <rect x="11.5" y="6.5" width="9" height="2.2" rx="1.1" fill="#fff" opacity="0.55" />
    </svg>
  );
}

export function BackIcon({ size = 22 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  );
}

export function PinIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 22s7-6.2 7-12a7 7 0 10-14 0c0 5.8 7 12 7 12z" fill="none" stroke="currentColor" stroke-width="2" />
      <circle cx="12" cy="10" r="2.6" fill="currentColor" />
    </svg>
  );
}

export function FilterIcon({ size = 18 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M4 7h16M7 12h10M10 17h4" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
    </svg>
  );
}

export function SeatedIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M7 3v10h9l2 8M7 13l-2 8M9 17h7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  );
}

export function StandingIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="4.5" r="2.3" fill="currentColor" />
      <path d="M12 8v7m0 0l-3 7m3-7l3 7M8 11h8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    </svg>
  );
}
