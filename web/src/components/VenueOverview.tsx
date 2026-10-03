import type { Counts, Layout, Section } from '../lib/api';
import { rupees } from '../lib/format';
import { centroid, pointsAttr, sectionColour, shade } from '../lib/layout';

type Props = {
  layout: Layout;
  sections: Section[];
  live: Record<string, Counts>;
  priceFilter: number | null;
  onPick: (section: Section) => void;
};

/** The whole venue at a glance: one shape per section, coloured by class, live availability. */
export function VenueOverview({ layout, sections, live, priceFilter, onPick }: Props) {
  const canvas = layout.canvas ?? { w: 1000, h: 800 };
  const stage = layout.stage;
  return (
    <div class="overview">
      <svg viewBox={`0 0 ${canvas.w} ${canvas.h}`} role="group" aria-label="Venue sections">
        {stage && (
          <g>
            <polygon points={pointsAttr(stage.points)} fill="#e4e3ea" stroke="#cfcdd7" stroke-width="2" />
            <text
              x={(Math.min(...stage.points.map((p) => p[0])) + Math.max(...stage.points.map((p) => p[0]))) / 2}
              y={Math.min(...stage.points.map((p) => p[1])) +
                Math.min(35, (Math.max(...stage.points.map((p) => p[1])) - Math.min(...stage.points.map((p) => p[1]))) / 2)}
              class="stage-label"
              style={{ fontSize: '24px' }}
            >
              {stage.label ?? 'STAGE'}
            </text>
          </g>
        )}
        {sections.map((s, i) => {
          const pts = s.display?.points;
          if (!pts) return null;
          const counts = live[s.code] ?? s.counts;
          const left = counts.available;
          const soldOut = left === 0;
          const dimmed = priceFilter != null && s.price_paise !== priceFilter;
          const colour = sectionColour(s.display, i);
          const [cx, cy] = s.display?.label_at ?? centroid(pts); // label_at for shapes whose middle isn't inside them
          const fill = soldOut ? '#ecebf0' : colour;
          const ink = soldOut ? '#9b99a6' : shade(colour, 0.62);
          const label = `${s.name}, ${rupees(s.price_paise)}, ${soldOut ? 'sold out' : `${left} left`}${s.standing ? ', standing' : ''}`;
          return (
            <g
              key={s.code}
              class={`section-shape${dimmed ? ' dimmed' : ''}${soldOut ? ' sold-out' : ''}`}
              role="button"
              tabindex={soldOut ? -1 : 0}
              aria-label={label}
              aria-disabled={soldOut}
              onClick={() => !soldOut && onPick(s)}
              onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && !soldOut && (e.preventDefault(), onPick(s))}
            >
              <title>{label}</title>
              <polygon
                points={pointsAttr(pts)}
                fill={fill}
                fill-opacity={0.9}
                stroke={soldOut ? '#d6d5dc' : shade(colour, 0.18)}
                stroke-width="3"
                stroke-linejoin="round"
              />
              <text x={cx} y={cy - 12} text-anchor="middle" fill={ink} style={{ fontSize: '17px', fontWeight: 800, letterSpacing: '0.04em' }}>
                {s.name.replace(/\s*\(standing\)/i, '').toUpperCase()}
              </text>
              <text x={cx} y={cy + 12} text-anchor="middle" fill={ink} style={{ fontSize: '14px', fontWeight: 650 }}>
                {soldOut ? 'SOLD OUT' : `${s.standing ? 'Standing · ' : ''}${rupees(s.price_paise)}`}
              </text>
              {!soldOut && (
                <text x={cx} y={cy + 32} text-anchor="middle" fill={ink} opacity={0.75} style={{ fontSize: '12px', fontWeight: 650 }}>
                  {left} left
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
