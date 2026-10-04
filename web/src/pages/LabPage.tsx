import { useEffect, useMemo, useState } from 'preact/hooks';
import { MiniSeatMap } from '../components/MiniSeatMap';
import { Poster } from '../components/Poster';
import { api, ApiError, type RushStatus, type Show, type Template } from '../lib/api';
import { sectionColour, sectionGeometry } from '../lib/layout';
import { useSeatMap } from '../lib/live';
import { linkTo } from '../lib/router';
import { toast } from '../lib/toast';

export const DASHBOARD_URL = 'https://violetmonorail1413.grafana.net/public-dashboards/4a39fb254b98447aa608638245763b54';
const LAB_KEY = 'kursi.lab.show';

/**
 * The engineering showcase: spin up your own copy of a venue, send a crowd of
 * bots at it, and watch every seat get exactly one owner while the totals
 * always add up.
 */
export function LabPage() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [template, setTemplate] = useState('arena');
  const [ttl, setTtl] = useState(120);
  const [showId, setShowId] = useState<string | null>(() => sessionStorage.getItem(LAB_KEY));
  const [show, setShow] = useState<Show | null>(null);
  const [bots, setBots] = useState(800);
  const [rush, setRush] = useState<RushStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [seatMap] = useSeatMap(showId ?? undefined, 700);

  useEffect(() => {
    api.templates().then(setTemplates).catch(() => {});
  }, []);

  useEffect(() => {
    if (!showId) return setShow(null);
    api.show(showId).then((s) => {
      setShow(s);
      // Keep the picker in step with the show you actually have.
      if (s.layout?.template) setTemplate(s.layout.template);
      if (s.hold_ttl_seconds) setTtl(s.hold_ttl_seconds);
    }).catch(() => {
      sessionStorage.removeItem(LAB_KEY);
      setShowId(null);
    });
    api.rushStatus(showId).then(setRush).catch(() => setRush(null));
  }, [showId]);

  // Poll the rush while it runs.
  useEffect(() => {
    if (!showId || !rush?.running) return;
    const t = setInterval(() => api.rushStatus(showId).then(setRush).catch(() => {}), 500);
    return () => clearInterval(t);
  }, [showId, rush?.running]);

  const geometry = useMemo(() => {
    if (!show) return [];
    return show.sections
      .map((s, i) => ({ s, i }))
      .filter(({ s }) => !s.standing)
      .map(({ s, i }) => ({ s, colour: sectionColour(s.display, i), g: sectionGeometry(s.code, s.rows, s.display?.curve ?? 0, s.rows ? [] : show.seats.map((x) => x.seat)) }));
  }, [show]);

  const create = async () => {
    setBusy(true);
    try {
      const created = await api.createDemoShow(template, ttl);
      sessionStorage.setItem(LAB_KEY, created.id);
      setRush(null);
      setShowId(created.id);
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Could not create the show', 'warn');
    } finally {
      setBusy(false);
    }
  };

  const start = async () => {
    if (!showId) return;
    setBusy(true);
    try {
      setRush(await api.startRush(showId, bots));
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Could not start the rush', 'warn');
    } finally {
      setBusy(false);
    }
  };

  const currentTemplate = show?.layout?.template;
  const switching = !!show && template !== currentTemplate;
  const picked = templates.find((t) => t.id === template);
  const counts = seatMap?.counts;
  const total = show?.total_seats ?? 0;
  const sum = counts ? counts.available + counts.held + counts.confirmed : 0;
  const declined = rush ? Object.entries(rush.declined).sort((a, b) => b[1] - a[1]) : [];

  return (
    <div class="container" style={{ paddingBottom: '60px' }}>
      <h1 class="page-title">Rush lab</h1>
      <p class="muted" style={{ marginTop: 0, maxWidth: '720px' }}>
        Make your own copy of a venue, then let a crowd of bots loose on it. They go through the same reserve path
        as you do: same transaction, same locks, same idempotency keys. Watch the seats fill and check that no seat
        ever gets two owners.
      </p>

      <div class="lab-grid">
        <div class="panel">
          <h2>1 · Build a venue</h2>
          <div class="template-pick">
            {templates.map((t) => (
              <button key={t.id} class="template-option" aria-pressed={template === t.id} onClick={() => setTemplate(t.id)}>
                <div class="poster-frame">
                  <Poster poster={t.poster} title="" />
                </div>
                <span>
                  <strong>{t.venue.split(',')[0]}</strong>
                  {t.id === currentTemplate && <span class="pill pill-good" style={{ marginLeft: '8px', padding: '1px 8px', fontSize: '11px' }}>Your show</span>}
                  <br />
                  <span class="muted" style={{ fontSize: '13px' }}>
                    {t.category} · {t.name}
                  </span>
                </span>
              </button>
            ))}
          </div>
          <label class="field" style={{ marginTop: '14px' }}>
            Hold time (unpaid seats go back on sale after)
            <select value={ttl} onChange={(e) => setTtl(Number((e.target as HTMLSelectElement).value))}>
              <option value={60}>1 minute</option>
              <option value={120}>2 minutes</option>
              <option value={300}>5 minutes</option>
            </select>
          </label>
          <button class={`btn btn-block${show && !switching ? ' btn-ghost' : ''}`} onClick={create} disabled={busy}>
            {!show ? 'Create my show' : switching ? `Switch to ${picked?.venue.split(',')[0] ?? 'this venue'}` : 'Make a fresh copy (empty seats)'}
          </button>

          {switching && (
            <div class="banner warn" style={{ marginTop: '14px', marginBottom: 0 }}>
              Your live show is still <strong>{show?.venue?.split(',')[0]}</strong>. Switch to rush {picked?.venue.split(',')[0]} instead.
            </div>
          )}

          {show && !switching && (
            <>
              <h2 style={{ marginTop: '26px' }}>2 · Unleash the crowd</h2>
              <label class="field">
                Bots: {bots}
                <input type="range" min={50} max={1500} step={50} value={bots} onInput={(e) => setBots(Number((e.target as HTMLInputElement).value))} />
              </label>
              <p class="muted" style={{ fontSize: '13px', marginTop: 0 }}>
                Most bots fight over the front rows, some pick anywhere, some want standing places. One in ten retries
                with the same idempotency key, one in five accepts a partial booking, and one in six walks away
                without paying, so you'll see its hold expire.
              </p>
              <button class="btn btn-brand btn-block btn-display" onClick={start} disabled={busy || rush?.running}>
                {rush?.running ? 'RUSH IN PROGRESS…' : 'START THE RUSH'}
              </button>
              <div style={{ display: 'grid', gap: '8px', marginTop: '12px' }}>
                <a class="btn btn-ghost btn-block" href={`/events/${show.id}?as=new`} target="_blank" rel="noopener">
                  Join as a buyer (new window)
                </a>
                <a class="btn btn-ghost btn-block" href={DASHBOARD_URL} target="_blank" rel="noopener">
                  Watch it on Grafana
                </a>
              </div>
            </>
          )}
        </div>

        <div class="panel">
          {!show ? (
            <div class="empty">Create a show to see it here, live.</div>
          ) : (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap', alignItems: 'center' }}>
                <div>
                  <h2 style={{ margin: 0 }}>{show.name}</h2>
                  <a class="muted" style={{ fontSize: '14px' }} {...linkTo(`/events/${show.id}`)}>
                    Open the booking page
                  </a>
                </div>
                {rush && (
                  <span class={`pill ${rush.running ? 'pill-live pill-warn' : 'pill-good'}`}>
                    {rush.running ? `Rushing · ${(rush.elapsed_ms / 1000).toFixed(1)}s` : `Done in ${(rush.elapsed_ms / 1000).toFixed(1)}s`}
                  </span>
                )}
              </div>

              <div class={`invariant ${sum === total ? 'ok' : 'broken'}`} style={{ marginTop: '14px' }} aria-live="polite">
                {sum === total ? '✓' : '✗'} available {counts?.available ?? '…'} + held {counts?.held ?? '…'} + sold {counts?.confirmed ?? '…'} = {sum} of {total}
              </div>

              {rush && (
                <div class="stat-grid">
                  <Stat label="Requests" value={rush.attempts} />
                  <Stat label="Bookings (201)" value={rush.reservations} />
                  <Stat label="Seats won" value={rush.seats} />
                  <Stat label="Paid" value={rush.paid} />
                  <Stat label="Walked away" value={rush.abandoned} />
                  <Stat label="Retries replayed" value={rush.replays} />
                  {declined.map(([reason, n]) => (
                    <Stat key={reason} label={`409 ${reason}`} value={n} />
                  ))}
                  <Stat label="Errors (5xx)" value={rush.errors} />
                </div>
              )}

              <div class="mini-maps">
                {geometry.map(({ s, g, colour }) => (
                  <MiniSeatMap key={s.code} section={s} geometry={g} colour={colour} states={seatMap?.sections.find((x) => x.code === s.code)?.states} />
                ))}
                {show.sections
                  .filter((s) => s.standing)
                  .map((s) => {
                    const c = seatMap?.sections.find((x) => x.code === s.code)?.counts ?? s.counts;
                    const taken = c.held + c.confirmed;
                    return (
                      <figure class="mini-map" key={s.code}>
                        <figcaption>
                          <strong>{s.name}</strong> <span class="muted">· {taken} of {s.capacity} taken</span>
                        </figcaption>
                        <div class="availability" style={{ height: '12px' }}>
                          <span style={{ width: `${(100 * taken) / s.capacity}%` }} />
                        </div>
                      </figure>
                    );
                  })}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div class="stat">
      <small>{label}</small>
      <strong>{value.toLocaleString('en-IN')}</strong>
    </div>
  );
}
