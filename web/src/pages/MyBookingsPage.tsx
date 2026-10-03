import { useEffect, useState } from 'preact/hooks';
import { Loading } from '../components/Overlays';
import { api, type Reservation } from '../lib/api';
import { plural, rupees, seatShort } from '../lib/format';
import { userName } from '../lib/identity';
import { linkTo } from '../lib/router';

const PILL: Record<string, string> = { confirmed: 'pill-good', held: 'pill-warn', cancelled: 'pill-bad', expired: '' };

export function MyBookingsPage() {
  const [list, setList] = useState<Reservation[] | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});

  useEffect(() => {
    api.mine().then(async (rs) => {
      setList(rs);
      const ids = [...new Set(rs.map((r) => r.show_id))];
      const pairs = await Promise.all(ids.map((id) => api.show(id).then((s) => [id, s.name] as const).catch(() => [id, 'A past demo show'] as const)));
      setNames(Object.fromEntries(pairs));
    }).catch(() => setList([]));
  }, [userName.value]);

  return (
    <div class="container" style={{ paddingBottom: '40px' }}>
      <h1 class="page-title">My bookings</h1>
      <p class="muted" style={{ marginTop: 0 }}>
        As <strong>{userName.value}</strong>. Each tab is its own buyer, so bookings made in another window show up there.
      </p>
      {!list ? (
        <Loading />
      ) : list.length === 0 ? (
        <div class="empty">
          <p>No bookings yet in this tab.</p>
          <a class="btn" {...linkTo('/')}>Find an event</a>
        </div>
      ) : (
        <div class="booking-list">
          {list.map((r) => (
            <a key={r.reservation_id} class="booking-row" {...linkTo(r.status === 'held' ? `/checkout/${r.reservation_id}` : `/tickets/${r.reservation_id}`)}>
              <div style={{ minWidth: 0 }}>
                <strong>{names[r.show_id] ?? '…'}</strong>
                <div class="muted" style={{ fontSize: '14px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {plural(r.seats.length, 'seat')}: {r.seats.map(seatShort).join(', ')} · {rupees(r.amount_paise)}
                </div>
              </div>
              <span class={`pill ${PILL[r.status] ?? ''}`}>{r.status === 'held' ? 'awaiting payment' : r.status}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
