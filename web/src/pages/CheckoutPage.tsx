import { useEffect, useState } from 'preact/hooks';
import { CodeArt } from '../components/CodeArt';
import { Loading } from '../components/Overlays';
import { Poster } from '../components/Poster';
import { api, ApiError, serverNow, type Reservation, type Show } from '../lib/api';
import { eventDate, mmss, plural, rupees, seatShort } from '../lib/format';
import { userName } from '../lib/identity';
import { linkTo, navigate } from '../lib/router';
import { toast } from '../lib/toast';

type Method = 'upi' | 'card' | 'netbanking';
type Phase = 'idle' | 'processing' | 'failed' | 'expired';

/**
 * A mock payment page over the real hold -> confirm flow. Reserving put the
 * seats on hold; "paying" calls POST /reservations/{id}/confirm. Let the timer
 * run out and the seats go back on sale (the confirm then fails with
 * hold_expired, decided by the database clock).
 */
export function CheckoutPage({ id }: { id: string }) {
  const [res, setRes] = useState<Reservation | null>(null);
  const [show, setShow] = useState<Show | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(serverNow());
  const [method, setMethod] = useState<Method>('upi');
  const [failNext, setFailNext] = useState(false);
  const [phase, setPhase] = useState<Phase>('idle');

  useEffect(() => {
    api
      .reservation(id)
      .then((r) => {
        if (r.status === 'confirmed') return navigate(`/tickets/${id}`, true);
        setRes(r);
        if (r.status === 'expired') setPhase('expired');
        return api.show(r.show_id).then(setShow);
      })
      .catch((e) =>
        setError(e instanceof ApiError && e.status === 403 ? 'This hold belongs to a different buyer (another tab).' : 'We could not find this booking.'),
      );
    const t = setInterval(() => setNow(serverNow()), 250);
    return () => clearInterval(t);
  }, [id]);

  const remaining = res?.expires_at ? Date.parse(res.expires_at) - now : 0;
  useEffect(() => {
    if (res?.status === 'held' && remaining <= 0 && phase !== 'processing') setPhase('expired');
  }, [remaining <= 0]);

  if (error) return <Problem text={error} />;
  if (!res || !show) return <Loading label="Loading checkout" />;

  const sectionOf = (label: string) =>
    show.sections.length === 1 ? show.sections[0] : show.sections.find((s) => label.startsWith(`${s.code}-`));
  const groups = new Map<string, { price: number; seats: string[] }>();
  for (const l of res.seats) {
    const s = sectionOf(l);
    const key = s?.name ?? 'Seats';
    const g = groups.get(key) ?? { price: s?.price_paise ?? 0, seats: [] };
    g.seats.push(seatShort(l));
    groups.set(key, g);
  }

  const pay = async () => {
    setPhase('processing');
    await new Promise((r) => setTimeout(r, 1400));
    if (failNext) {
      setPhase('failed');
      return;
    }
    try {
      await api.confirm(id);
      toast('Payment received. Enjoy the show!', 'good');
      navigate(`/tickets/${id}`, true);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'hold_expired') setPhase('expired');
      else {
        setPhase('idle');
        toast(e instanceof Error ? e.message : 'Payment failed', 'warn');
      }
    }
  };

  const release = async () => {
    try {
      await api.cancel(id);
      toast('Seats released for someone else', 'info');
    } finally {
      navigate(`/events/${show.id}`);
    }
  };

  const urgent = remaining < 60_000;
  return (
    <div class="container">
      <h1 class="page-title">Checkout</h1>
      <p class="muted" style={{ margin: 0 }}>
        Demo payment: no money moves. Paying confirms your hold through the API.
      </p>
      <div class="checkout">
        <div class="panel">
          {phase === 'expired' ? (
            <div class="processing">
              <div class="modal-emoji">⌛</div>
              <h2>Your hold ran out</h2>
              <p class="muted" style={{ maxWidth: '420px' }}>
                The seats went back on sale for everyone else and you weren't charged. The expiry is decided by the
                database clock, so nobody can pay for a seat that's already been released.
              </p>
              <a class="btn" {...linkTo(`/events/${show.id}`)}>
                Pick seats again
              </a>
            </div>
          ) : phase === 'processing' ? (
            <div class="processing" aria-live="polite">
              <div class="spinner" />
              <strong>Processing payment…</strong>
              <span class="muted">Don't refresh. (You could; confirming is idempotent.)</span>
            </div>
          ) : (
            <>
              <div class={`hold-timer${urgent ? ' urgent' : ''}`} role="timer" aria-live="off">
                <span>Seats held for you</span>
                <strong>{mmss(remaining)}</strong>
              </div>
              <h2 style={{ marginTop: '22px' }}>Pay with</h2>
              {phase === 'failed' && (
                <div class="banner bad" role="alert">
                  Payment declined by the (pretend) bank. Your seats are still held: try again before the timer ends.
                </div>
              )}
              <div class="tabs" role="tablist">
                {(['upi', 'card', 'netbanking'] as Method[]).map((m) => (
                  <button key={m} role="tab" aria-selected={method === m} onClick={() => setMethod(m)}>
                    {m === 'upi' ? 'UPI' : m === 'card' ? 'Card' : 'Netbanking'}
                  </button>
                ))}
              </div>
              {method === 'upi' && (
                <>
                  <div class="upi-qr">
                    <CodeArt value={`upi:${id}`} size={120} />
                    <div>
                      <strong>Scan with any UPI app</strong>
                      <p class="muted" style={{ margin: '4px 0 0', fontSize: '14px' }}>
                        …or enter your UPI ID. (It's a demo, any value works.)
                      </p>
                    </div>
                  </div>
                  <label class="field">
                    UPI ID
                    <input value={`${userName.value.toLowerCase()}@kursi`} />
                  </label>
                </>
              )}
              {method === 'card' && (
                <>
                  <label class="field">
                    Card number
                    <input inputMode="numeric" value="4242 4242 4242 4242" />
                  </label>
                  <div class="field-row">
                    <label class="field">
                      Expiry
                      <input value="12 / 30" />
                    </label>
                    <label class="field">
                      CVV
                      <input inputMode="numeric" value="123" type="password" />
                    </label>
                  </div>
                </>
              )}
              {method === 'netbanking' && (
                <label class="field">
                  Bank
                  <select>
                    <option>Bank of Kursi</option>
                    <option>Chai Cooperative Bank</option>
                    <option>Jugaad National Bank</option>
                  </select>
                </label>
              )}
              <div class="toggle-row">
                <span>
                  <strong>Simulate a failed payment</strong>
                  <br />
                  <span class="muted">See what happens when the bank says no.</span>
                </span>
                <button class="switch" role="switch" aria-checked={failNext} aria-label="Simulate a failed payment" onClick={() => setFailNext(!failNext)} />
              </div>
              <button class="btn btn-display btn-block" onClick={pay}>
                PAY {rupees(res.amount_paise)}
              </button>
              <button class="btn btn-ghost btn-block" style={{ marginTop: '10px' }} onClick={release}>
                Release seats
              </button>
            </>
          )}
        </div>

        <aside class="panel summary">
          <div class="summary-poster">
            <Poster poster={show.layout?.poster} title={show.name} category={show.layout?.category} />
          </div>
          <strong style={{ fontSize: '18px' }}>{show.name}</strong>
          <div class="muted" style={{ fontSize: '14px' }}>
            {[eventDate(show.starts_at), show.venue].filter(Boolean).join(' · ')}
          </div>
          <div class="line-items">
            {[...groups].map(([name, g]) => (
              <div key={name}>
                <span>
                  {name} × {g.seats.length}
                  <br />
                  <span class="muted" style={{ fontSize: '13px' }}>
                    {g.seats.join(', ')}
                  </span>
                </span>
                <span>{rupees(g.price * g.seats.length)}</span>
              </div>
            ))}
            <div class="muted">
              <span>Convenience fee</span>
              <span>₹0 (it's a demo)</span>
            </div>
          </div>
          <div class="total-line">
            <span>Total · {plural(res.seats.length, 'ticket')}</span>
            <span>{rupees(res.amount_paise)}</span>
          </div>
        </aside>
      </div>
    </div>
  );
}

function Problem({ text }: { text: string }) {
  return (
    <div class="container">
      <div class="empty" style={{ marginTop: '40px' }}>
        <p>{text}</p>
        <a class="btn" {...linkTo('/')}>
          Back to events
        </a>
      </div>
    </div>
  );
}
