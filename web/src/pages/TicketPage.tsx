import { useEffect, useState } from 'preact/hooks';
import { CodeArt } from '../components/CodeArt';
import { Loading, Modal } from '../components/Overlays';
import { api, ApiError, type Reservation, type Show } from '../lib/api';
import { eventDate, plural, rupees, seatShort } from '../lib/format';
import { linkTo, navigate } from '../lib/router';
import { toast } from '../lib/toast';

export function TicketPage({ id }: { id: string }) {
  const [res, setRes] = useState<Reservation | null>(null);
  const [show, setShow] = useState<Show | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);

  useEffect(() => {
    api
      .reservation(id)
      .then((r) => {
        if (r.status === 'held') return navigate(`/checkout/${id}`, true);
        setRes(r);
        return api.show(r.show_id).then(setShow);
      })
      .catch((e) => setError(e instanceof ApiError && e.status === 403 ? 'This ticket belongs to a different buyer (another tab).' : 'Ticket not found.'));
  }, [id]);

  if (error)
    return (
      <div class="container">
        <div class="empty" style={{ marginTop: '40px' }}>
          <p>{error}</p>
          <a class="btn" {...linkTo('/me')}>My bookings</a>
        </div>
      </div>
    );
  if (!res || !show) return <Loading label="Loading ticket" />;

  const sectionNames = [...new Set(res.seats.map((l) => (show.sections.length === 1 ? show.sections[0] : show.sections.find((s) => l.startsWith(`${s.code}-`)))?.name ?? ''))];
  const stamp = res.status === 'confirmed' ? { text: 'CONFIRMED', colour: 'var(--good)' } : res.status === 'cancelled' ? { text: 'CANCELLED', colour: 'var(--bad)' } : { text: 'EXPIRED', colour: 'var(--muted)' };

  const cancel = async () => {
    try {
      const r = await api.cancel(id);
      setRes(r);
      toast('Booking cancelled. The seats are back on sale.', 'info');
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Could not cancel', 'warn');
    } finally {
      setConfirmCancel(false);
    }
  };

  return (
    <div class="container" style={{ paddingBottom: '60px' }}>
      <div class="ticket">
        <div class="ticket-top">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start', gap: '12px' }}>
            <span class="pill">{show.layout?.category ?? 'Event'}</span>
            <span class="status-stamp" style={{ color: stamp.colour }}>
              {stamp.text}
            </span>
          </div>
          <h1 class="display" style={{ fontSize: '40px', margin: '14px 0 4px' }}>
            {show.name}
          </h1>
          <div class="muted">{[eventDate(show.starts_at), show.venue].filter(Boolean).join(' · ')}</div>
          <dl>
            <div>
              <dt>SECTION</dt>
              <dd>{sectionNames.join(', ')}</dd>
            </div>
            <div>
              <dt>SEATS</dt>
              <dd>{res.seats.map(seatShort).join(', ')}</dd>
            </div>
            <div>
              <dt>TICKETS</dt>
              <dd>{plural(res.seats.length, 'ticket')}</dd>
            </div>
            <div>
              <dt>{res.status === 'confirmed' ? 'PAID' : 'AMOUNT'}</dt>
              <dd>{rupees(res.amount_paise)}</dd>
            </div>
          </dl>
        </div>
        <div class="ticket-tear" />
        <div class="ticket-bottom">
          <div>
            <div class="muted" style={{ fontSize: '11px', fontWeight: 800, letterSpacing: '.12em' }}>
              BOOKING ID
            </div>
            <div style={{ margin: '2px 0 8px', fontWeight: 800, fontFamily: 'monospace' }}>{res.reservation_id.slice(0, 8).toUpperCase()}</div>
            <span class="muted" style={{ fontSize: '13px' }}>
              Buyer: {res.user_id}
            </span>
          </div>
          <div style={{ opacity: res.status === 'confirmed' ? 1 : 0.25 }}>
            <CodeArt value={res.reservation_id} size={104} />
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: '10px', justifyContent: 'center', marginTop: '24px', flexWrap: 'wrap' }}>
        <a class="btn" {...linkTo(`/events/${show.id}`)}>
          Back to the event
        </a>
        {res.status === 'confirmed' && (
          <button class="btn btn-ghost" onClick={() => setConfirmCancel(true)}>
            Cancel booking
          </button>
        )}
      </div>
      {confirmCancel && (
        <Modal label="Cancel booking" onClose={() => setConfirmCancel(false)}>
          <div class="modal-emoji">🎟️</div>
          <h2>Cancel these tickets?</h2>
          <p>The seats go straight back on sale. Cancelling twice is harmless: the second time does nothing.</p>
          <div style={{ display: 'flex', gap: '10px' }}>
            <button class="btn" onClick={cancel}>
              Yes, cancel
            </button>
            <button class="btn btn-ghost" onClick={() => setConfirmCancel(false)} autoFocus>
              Keep them
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
