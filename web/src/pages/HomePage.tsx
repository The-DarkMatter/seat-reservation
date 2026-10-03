import { useEffect, useState } from 'preact/hooks';
import { PinIcon } from '../components/Icons';
import { Loading } from '../components/Overlays';
import { Poster } from '../components/Poster';
import { api, type ShowSummary } from '../lib/api';
import { eventDate, rupees } from '../lib/format';
import { linkTo, navigate } from '../lib/router';

const FAIR = [
  {
    icon: '1',
    title: 'Exactly one winner per seat',
    text: 'Every claim is a single conditional UPDATE in MySQL. Five hundred people can click A12 at the same instant; one gets it, the rest get a clean "taken".',
  },
  {
    icon: '↻',
    title: 'Retries never double-book',
    text: 'Each booking carries an idempotency key. A dropped connection or a double-tap replays the first answer instead of booking again.',
  },
  {
    icon: '⏱',
    title: 'Holds that let go',
    text: "Seats are held while you pay and released when time's up, judged by the database clock, so an expired hold can't be paid for.",
  },
];

export function HomePage() {
  const [shows, setShows] = useState<ShowSummary[] | null>(null);
  const [active, setActive] = useState(0);

  useEffect(() => {
    api.listShows().then(setShows).catch(() => setShows([]));
  }, []);

  useEffect(() => {
    if (!shows?.length) return;
    const t = setInterval(() => setActive((a) => (a + 1) % shows.length), 6000);
    return () => clearInterval(t);
  }, [shows?.length, active]);

  if (!shows) return <Loading label="Loading events" />;
  const hero = shows[active];

  return (
    <>
      {hero && (
        <section class="hero" aria-roledescription="carousel" aria-label="Featured events">
          <div
            class="hero-backdrop"
            style={{ background: `radial-gradient(60% 80% at 20% 30%, ${hero.layout?.poster?.to ?? '#ff7a1a'}, transparent), radial-gradient(60% 80% at 80% 60%, ${hero.layout?.poster?.from ?? '#1d1636'}, transparent)` }}
          />
          <div class="container">
            <div class="hero-inner">
              <a class="hero-poster" {...linkTo(`/events/${hero.id}`)} aria-label={hero.name}>
                <Poster poster={hero.layout?.poster} title={hero.name} category={hero.layout?.category} />
              </a>
              <div class="hero-meta">
                <span class="hero-date">{eventDate(hero.starts_at)}</span>
                <h1 class="hero-title">{hero.name}</h1>
                <span class="hero-venue">{hero.venue}</span>
                <span class="hero-price">{rupees(hero.price_paise)} onwards</span>
                <button class="btn" onClick={() => navigate(`/events/${hero.id}`)}>
                  Book tickets
                </button>
              </div>
            </div>
            <div class="hero-dots" role="tablist">
              {shows.map((s, i) => (
                <button key={s.id} role="tab" aria-current={i === active} aria-label={s.name} onClick={() => setActive(i)} />
              ))}
            </div>
          </div>
        </section>
      )}

      <div class="container">
        <section class="section-block">
          <h2 class="section-title">On sale now</h2>
          {shows.length === 0 ? (
            <div class="empty">No events right now. Try the Rush lab.</div>
          ) : (
            <div class="event-grid">
              {shows.map((s) => {
                const sold = s.counts.held + s.counts.confirmed;
                return (
                  <a key={s.id} class="event-card" {...linkTo(`/events/${s.id}`)}>
                    <div class="poster-frame">
                      <Poster poster={s.layout?.poster} title={s.name} category={s.layout?.category} />
                    </div>
                    <span class="event-card-venue">
                      <PinIcon /> {s.venue}
                    </span>
                    <span class="event-card-name">{s.name}</span>
                    <span class="muted" style={{ fontSize: '14px' }}>
                      {eventDate(s.starts_at)} · from {rupees(s.price_paise)}
                    </span>
                    <div class="availability" aria-label={`${s.counts.available} of ${s.total_seats} left`}>
                      <span style={{ width: `${Math.max(2, (100 * sold) / s.total_seats)}%` }} />
                    </div>
                    <span class="muted" style={{ fontSize: '12px', marginTop: '-4px' }}>
                      {s.counts.available.toLocaleString('en-IN')} of {s.total_seats.toLocaleString('en-IN')} left
                    </span>
                  </a>
                );
              })}
            </div>
          )}
        </section>

        <section class="section-block">
          <h2 class="section-title">How Kursi keeps it fair</h2>
          <div class="fair-grid">
            {FAIR.map((f) => (
              <div key={f.title} class="fair-card">
                <span class="fair-icon">{f.icon}</span>
                <h3>{f.title}</h3>
                <p>{f.text}</p>
              </div>
            ))}
          </div>
        </section>

        <section class="section-block">
          <div class="rush-cta">
            <div>
              <h2>Start your own rush</h2>
              <p>
                Spin up a private copy of a venue and send up to 1,500 bots at it. Watch the seat map fill in real
                time and see the totals add up at every moment.
              </p>
            </div>
            <a class="btn btn-brand btn-display" {...linkTo('/lab')}>
              OPEN THE LAB
            </a>
          </div>
        </section>
      </div>
    </>
  );
}
