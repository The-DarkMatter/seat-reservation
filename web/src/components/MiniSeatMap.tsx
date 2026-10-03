import type { Section } from '../lib/api';
import { shade, type SectionGeometry } from '../lib/layout';

/** A read-only seat map for watching a rush: just the dots, no interaction. */
export function MiniSeatMap({ section, geometry: g, states, colour }: {
  section: Section;
  geometry: SectionGeometry;
  states?: string;
  colour: string;
}) {
  return (
    <figure class="mini-map">
      <figcaption>
        <strong>{section.name}</strong>
      </figcaption>
      <svg viewBox={`0 0 ${g.width} ${g.height}`} aria-label={`${section.name} live seat states`}>
        {g.seats.map((s) => {
          const c = states?.[s.index] ?? 'a';
          const fill = c === 'c' ? shade(colour, 0.55) : c === 'h' ? 'var(--held)' : colour;
          return <circle key={s.label} class="seat" cx={s.x} cy={s.y} r={0.4} style={{ r: c === 'a' ? '0.4px' : '0.43px' }} fill={fill} opacity={c === 'a' ? 0.55 : 1} />;
        })}
      </svg>
    </figure>
  );
}
