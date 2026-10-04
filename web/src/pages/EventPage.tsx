import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { BottomBar, HoldBar, StandingSheet } from '../components/Booking';
import { BackIcon, FilterIcon } from '../components/Icons';
import { Loading, Modal } from '../components/Overlays';
import { SeatMapView } from '../components/SeatMapView';
import { VenueOverview } from '../components/VenueOverview';
import { api, ApiError, type Counts, type ReserveBody, type Section, type Show } from '../lib/api';
import { eventDate, plural, rupees, seatShort } from '../lib/format';
import { sectionColour, sectionGeometry, shade, type SectionGeometry } from '../lib/layout';
import { mySeats, useMine, useSeatMap } from '../lib/live';
import { linkTo, navigate } from '../lib/router';
import { toast } from '../lib/toast';

type Problem = { emoji: string; title: string; text: string };

export function EventPage({ id }: { id: string }) {
  const [show, setShow] = useState<Show | null>(null);
  const [missing, setMissing] = useState(false);
  const [seatMap, refreshMap] = useSeatMap(id);
  const [mineList, refreshMine] = useMine(id);
  const [view, setView] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [partial, setPartial] = useState(false);
  const [priceFilter, setPriceFilter] = useState<number | null>(null);
  const [standing, setStanding] = useState<Section | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);

  useEffect(() => {
    setShow(null);
    setSelected([]);
    api.show(id).then(setShow).catch((e) => (e instanceof ApiError && e.status === 404 ? setMissing(true) : toast(e.message, 'warn')));
  }, [id]);

  const sections = show?.sections ?? [];
  const hasOverview = !!show?.layout?.canvas && sections.length > 1 && sections.some((s) => s.display?.points);

  // Go straight to the seats when there's nothing to choose between.
  useEffect(() => {
    if (show && !hasOverview) setView(sections.find((s) => !s.standing)?.code ?? null);
  }, [show]);

  const geometry = useMemo(() => {
    const out = new Map<string, SectionGeometry>();
    if (!show) return out;
    for (const s of show.sections) {
      if (s.standing) continue;
      const flat = s.rows ? [] : show.seats.map((x) => x.seat);
      out.set(s.code, sectionGeometry(s.code, s.rows, s.display?.curve ?? 0, flat));
    }
    return out;
  }, [show]);

  // label -> where its live state lives
  const seatIndex = useMemo(() => {
    const out = new Map<string, { code: string; index: number }>();
    geometry.forEach((g, code) => g.seats.forEach((s) => out.set(s.label, { code, index: s.index })));
    return out;
  }, [geometry]);

  const states = useMemo(() => {
    const out: Record<string, string | undefined> = {};
    seatMap?.sections.forEach((s) => (out[s.code] = s.states));
    return out;
  }, [seatMap]);

  const liveCounts = useMemo(() => {
    const out: Record<string, Counts> = {};
    seatMap?.sections.forEach((s) => (out[s.code] = s.counts));
    return out;
  }, [seatMap]);

  const my = useMemo(() => mySeats(mineList), [mineList]);
  const mine = my.all; // paid or on hold: counts toward the per-user limit
  const sectionOf = useCallback(
    (label: string) => (sections.length === 1 ? sections[0] : sections.find((s) => label.startsWith(`${s.code}-`))),
    [sections],
  );
  const stateOf = (label: string) => {
    const at = seatIndex.get(label);
    return at ? (states[at.code]?.[at.index] ?? 'a') : 'a';
  };

  // Someone else took a seat we had selected: let go of it and say so.
  const lastWarned = useRef(new Set<string>());
  useEffect(() => {
    const lost = selected.filter((l) => stateOf(l) !== 'a' && !mine.has(l));
    if (!lost.length) return;
    setSelected((cur) => cur.filter((l) => !lost.includes(l)));
    const fresh = lost.filter((l) => !lastWarned.current.has(l));
    fresh.forEach((l) => lastWarned.current.add(l));
    if (fresh.length) toast(`${fresh.map(seatShort).join(', ')} just went to someone else`, 'warn');
  }, [seatMap]);

  if (missing) return <NotFoundShow />;
  if (!show) return <Loading label="Loading event" />;

  const limit = show.per_user_limit;
  const toggleSeat = (label: string) => {
    if (my.holding.has(label)) return toast('Already on hold for you: continue to payment to keep it', 'info', 3000);
    if (my.paid.has(label)) return toast('You already have a ticket for this seat', 'info', 2500);
    if (selected.includes(label)) return setSelected(selected.filter((l) => l !== label));
    if (stateOf(label) !== 'a') return toast(`${seatShort(label)} is ${stateOf(label) === 'h' ? 'on hold' : 'taken'}`, 'info', 2500);
    if (selected.length + mine.size >= limit) {
      return toast(`You can have up to ${limit} seats for this show`, 'warn');
    }
    setSelected([...selected, label]);
  };

  // While a hold is live, new seats join it (same reservation, same timer) instead of
  // starting a second hold with its own countdown.
  const activeHold = my.holds[0];
  const book = async (body: ReserveBody, wanted: string[]) => {
    setBusy(true);
    try {
      const r = activeHold ? await api.addToHold(activeHold.reservation_id, body) : await api.reserve(show.id, body);
      setSelected([]);
      setStanding(null);
      refreshMap();
      refreshMine();
      if (r.shortfall) {
        const missed = r.shortfall.unavailable?.map(seatShort).join(', ');
        toast(`Got ${r.seats.length} of ${r.shortfall.requested}${missed ? `: ${missed} went to someone faster` : ''}`, 'warn', 6000);
      }
      navigate(r.status === 'held' ? `/checkout/${r.reservation_id}` : `/tickets/${r.reservation_id}`);
    } catch (e) {
      refreshMap();
      if (!(e instanceof ApiError)) return toast('Network trouble. Your retry is safe: same idempotency key.', 'warn');
      if (e.code === 'hold_expired' || e.code === 'not_a_hold' || e.code === 'reservation_cancelled') {
        refreshMine();
        return toast('Your earlier hold has ended. Tap PROCEED again to hold these seats on a fresh timer.', 'warn', 6000);
      }
      const lostSeats = (e.body?.seats as string[] | undefined) ?? [];
      if (e.code === 'seat_taken') {
        setSelected((cur) => cur.filter((l) => !lostSeats.includes(l)));
        setProblem({
          emoji: '⚡',
          title: 'Someone beat you to it',
          text: `${lostSeats.map(seatShort).join(', ') || 'A seat'} was booked a moment before your request landed. Nothing was charged and ${
            wanted.length > 1 ? 'none of your other seats were held (all or nothing).' : 'you can pick another seat.'
          }`,
        });
      } else if (e.code === 'per_user_limit') {
        setProblem({ emoji: '✋', title: 'That is over the limit', text: `Each buyer can hold at most ${limit} seats for this show. You already have ${e.body?.seats_held ?? mine.size}.` });
      } else if (e.code === 'section_sold_out') {
        setProblem({ emoji: '🎟️', title: 'Not enough places left', text: e.message });
      } else {
        toast(e.message, 'warn');
      }
    } finally {
      setBusy(false);
    }
  };

  const current = view ? sections.find((s) => s.code === view) : undefined;
  const currentIndex = current ? sections.indexOf(current) : 0;
  const colour = current ? sectionColour(current.display, currentIndex) : '#ccc';
  const prices = [...new Set(sections.map((s) => s.price_paise))].sort((a, b) => a - b);
  const total = seatMap?.counts ?? show.counts;

  return (
    <>
      <div class="event-head">
        <div class="container">
          <button class="icon-btn" aria-label="Back to events" onClick={() => navigate('/')}>
            <BackIcon />
          </button>
          <div class="event-head-title">
            <h1>{show.name}</h1>
            <p>{[eventDate(show.starts_at), show.venue].filter(Boolean).join(' · ')}</p>
          </div>
          <span />
        </div>
      </div>

      <div class="container">
        <h2 class="step-title">{current ? 'SELECT SEATS' : 'SELECT A SECTION'}</h2>

        <div class="map-shell">
          {current && geometry.get(current.code) ? (
            <>
              <div class="map-toolbar">
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  {hasOverview && (
                    <button class="back-link" onClick={() => setView(null)}>
                      <BackIcon size={18} /> All sections
                    </button>
                  )}
                  <h2>
                    {current.name} <span class="muted" style={{ fontWeight: 650 }}>· {rupees(current.price_paise)}</span>
                  </h2>
                </div>
                <div class="legend" aria-label="Legend">
                  <span><i style={{ background: colour, border: `1px solid ${shade(colour, 0.22)}` }} />Available</span>
                  <span><i style={{ background: shade(colour, 0.42) }} />Selected</span>
                  <span><i style={{ background: 'var(--held)', transform: 'scale(.55)' }} />On hold</span>
                  <span><i style={{ background: 'var(--sold)', transform: 'scale(.55)' }} />Sold</span>
                  <span><i style={{ background: 'var(--mine)' }} />Yours</span>
                </div>
              </div>
              <SeatMapView
                section={current}
                geometry={geometry.get(current.code)!}
                states={states[current.code]}
                selected={new Set(selected)}
                mine={my.paid}
                holding={my.holding}
                colour={colour}
                onToggle={toggleSeat}
              />
            </>
          ) : (
            <>
              <div class="map-toolbar">
                <h2>Tap a section</h2>
                <span class="pill pill-live pill-good">
                  Live · {total.available} of {show.total_seats} left
                </span>
              </div>
              {show.layout && (
                <VenueOverview
                  layout={show.layout}
                  sections={sections}
                  live={liveCounts}
                  priceFilter={priceFilter}
                  onPick={(s) => (s.standing ? setStanding(s) : setView(s.code))}
                />
              )}
              <div class="price-filter">
                <span class="price-filter-label">
                  <FilterIcon /> Filter by price
                </span>
                {prices.map((p) => (
                  <button key={p} class="chip" aria-pressed={priceFilter === p} onClick={() => setPriceFilter(priceFilter === p ? null : p)}>
                    {rupees(p)}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>

        <RaceTip />

        <div class="section-block" style={{ paddingBottom: '120px' }}>
          <div class="fair-grid">
            <div class="fair-card">
              <h3 style={{ marginTop: 0 }}>About this event</h3>
              <p>{show.layout?.blurb ?? 'A Kursi demo show.'}</p>
            </div>
            <div class="fair-card">
              <h3 style={{ marginTop: 0 }}>House rules</h3>
              <p>
                Up to {plural(limit, 'seat')} per buyer.{' '}
                {show.hold_ttl_seconds
                  ? `Seats are held for ${Math.round(show.hold_ttl_seconds / 60) || 1} min while you pay; unpaid holds go back on sale.`
                  : 'Seats are confirmed the moment you book.'}
              </p>
            </div>
            {mineList.length > 0 && (
              <div class="fair-card">
                <h3 style={{ marginTop: 0 }}>Your bookings here</h3>
                <div class="booking-list" style={{ gap: '6px' }}>
                  {mineList.slice(0, 4).map((r) => (
                    <a key={r.reservation_id} {...linkTo(r.status === 'held' ? `/checkout/${r.reservation_id}` : `/tickets/${r.reservation_id}`)}>
                      {r.seats.map(seatShort).join(', ')} · {r.status}
                    </a>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {activeHold ? (
        <HoldBar
          hold={activeHold}
          others={my.holds.length - 1}
          extra={selected}
          partial={partial}
          onPartial={setPartial}
          busy={busy}
          sectionOf={sectionOf}
          onContinue={() =>
            selected.length
              ? book({ seats: selected, allow_partial: partial }, selected)
              : navigate(`/checkout/${activeHold.reservation_id}`)
          }
          onRelease={async () => {
            await api.cancel(activeHold.reservation_id).catch(() => {});
            toast('Seats released for someone else', 'info');
            refreshMine();
            refreshMap();
          }}
          onExpired={() => {
            toast('Your hold ran out and the seats went back on sale', 'warn');
            refreshMine();
            refreshMap();
          }}
        />
      ) : (
      <BottomBar
        selection={selected}
        sectionOf={sectionOf}
        partial={partial}
        onPartial={setPartial}
        busy={busy}
        onClear={() => setSelected([])}
        onProceed={() => book({ seats: selected, allow_partial: partial }, selected)}
      />
      )}

      {standing && (
        <StandingSheet
          section={standing}
          available={(liveCounts[standing.code] ?? standing.counts).available}
          maxPerUser={Math.max(0, limit - mine.size)}
          busy={busy}
          onClose={() => setStanding(null)}
          onBook={(quantity, allowPartial) => book({ section: standing.code, quantity, allow_partial: allowPartial }, [])}
        />
      )}

      {problem && (
        <Modal label={problem.title} onClose={() => setProblem(null)}>
          <div class="modal-emoji">{problem.emoji}</div>
          <h2>{problem.title}</h2>
          <p>{problem.text}</p>
          <button class="btn btn-block" onClick={() => setProblem(null)} autoFocus>
            Pick again
          </button>
        </Modal>
      )}
    </>
  );
}

function RaceTip() {
  const [hidden, setHidden] = useState(() => sessionStorage.getItem('kursi.tip') === '1');
  if (hidden) return null;
  return (
    <div class="race-tip">
      <span>
        Try to break it: open this event in a second window (a different buyer) and click the same seat in both.
        Exactly one of you gets it.
      </span>
      <button class="btn btn-sm" onClick={() => window.open(`${location.pathname}?as=new`, '_blank', 'noopener,width=1100,height=900')}>
        Open 2nd buyer
      </button>
      <button
        class="icon-btn"
        aria-label="Dismiss tip"
        onClick={() => {
          sessionStorage.setItem('kursi.tip', '1');
          setHidden(true);
        }}
      >
        ✕
      </button>
    </div>
  );
}

function NotFoundShow() {
  return (
    <div class="container">
      <div class="empty" style={{ marginTop: '40px' }}>
        <h2 style={{ color: 'var(--ink)' }}>This event isn't here any more</h2>
        <p>Demo shows are cleaned up after a day. The featured events are always on.</p>
        <a class="btn" {...linkTo('/')}>See events</a>
      </div>
    </div>
  );
}
