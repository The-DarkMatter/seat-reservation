import type { Poster as PosterSpec } from '../lib/api';

const FALLBACK: PosterSpec = { from: '#1d1636', to: '#ff7a1a', glyph: 'chair' };

/**
 * Event poster art, drawn rather than photographed: a gradient, a big glyph for
 * the kind of event, and the title in display type. No image assets to license.
 */
export function Poster({ poster, title, category }: { poster?: PosterSpec; title: string; category?: string }) {
  const p = poster ?? FALLBACK;
  return (
    <div class="poster" style={{ background: `linear-gradient(150deg, ${p.from} 0%, ${p.from} 35%, ${p.to} 140%)` }}>
      <svg class="poster-glyph" viewBox="0 0 200 200" aria-hidden="true">
        <Glyph name={p.glyph} accent={p.to} />
      </svg>
      {category && <span class="poster-category">{category}</span>}
      <div class="poster-title display">{title}</div>
    </div>
  );
}

function Glyph({ name, accent }: { name: string; accent: string }) {
  switch (name) {
    case 'raag':
      return (
        <g fill="none" stroke={accent} stroke-linecap="round">
          {[18, 34, 50, 66, 82].map((r, i) => (
            <circle cx="150" cy="58" r={r} stroke-width={6 - i} opacity={0.95 - i * 0.16} />
          ))}
          <path d="M20 150 q20 -40 40 0 t40 0 t40 0" stroke-width="5" opacity="0.7" />
          <path d="M20 170 q20 -30 40 0 t40 0 t40 0" stroke-width="3" opacity="0.45" />
        </g>
      );
    case 'satire':
      return (
        <g fill={accent}>
          <rect x="112" y="18" width="62" height="54" rx="10" opacity="0.95" />
          <rect x="100" y="74" width="86" height="16" rx="7" />
          <rect x="106" y="90" width="10" height="44" rx="5" />
          <rect x="170" y="90" width="10" height="44" rx="5" />
          <circle cx="62" cy="58" r="8" opacity="0.5" />
          <circle cx="40" cy="84" r="6" opacity="0.35" />
          <circle cx="78" cy="96" r="5" opacity="0.3" />
        </g>
      );
    case 'chai':
      return (
        <g fill="none" stroke={accent} stroke-width="6" stroke-linecap="round">
          <path d="M112 74 h60 l-8 56 a14 14 0 0 1 -14 12 h-16 a14 14 0 0 1 -14 -12 z" fill={accent} opacity="0.95" stroke="none" />
          <path d="M170 86 h8 a12 12 0 0 1 0 24 h-10" />
          <path d="M128 60 q-8 -12 0 -24 t0 -24" opacity="0.6" />
          <path d="M148 60 q-8 -12 0 -24 t0 -24" opacity="0.4" />
        </g>
      );
    default:
      return (
        <g fill={accent}>
          <rect x="110" y="24" width="64" height="58" rx="10" />
          <rect x="98" y="84" width="88" height="16" rx="7" />
          <rect x="104" y="100" width="10" height="44" rx="5" />
          <rect x="170" y="100" width="10" height="44" rx="5" />
        </g>
      );
  }
}
