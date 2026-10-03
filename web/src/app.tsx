import { useEffect } from 'preact/hooks';
import { Header, Wordmark } from './components/Header';
import { Toasts } from './components/Overlays';
import { CheckoutPage } from './pages/CheckoutPage';
import { EventPage } from './pages/EventPage';
import { HomePage } from './pages/HomePage';
import { DASHBOARD_URL, LabPage } from './pages/LabPage';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { TicketPage } from './pages/TicketPage';
import { currentPath, linkTo, match } from './lib/router';

function route(path: string) {
  let p: Record<string, string> | null;
  if (path === '/') return { page: <HomePage />, title: 'Kursi · Har kursi ka ek hi maalik', footer: true };
  if ((p = match('/events/:id', path))) return { page: <EventPage id={p.id} />, title: 'Pick your seats · Kursi', footer: false };
  if ((p = match('/checkout/:id', path))) return { page: <CheckoutPage id={p.id} />, title: 'Checkout · Kursi', footer: false };
  if ((p = match('/tickets/:id', path))) return { page: <TicketPage id={p.id} />, title: 'Your ticket · Kursi', footer: true };
  if (path === '/me') return { page: <MyBookingsPage />, title: 'My bookings · Kursi', footer: true };
  if (path === '/lab') return { page: <LabPage />, title: 'Rush lab · Kursi', footer: true };
  return {
    page: (
      <div class="container">
        <div class="empty" style={{ marginTop: '40px' }}>
          <p>Nothing here. Every seat has one owner, but this page has none.</p>
          <a class="btn" {...linkTo('/')}>Back to events</a>
        </div>
      </div>
    ),
    title: 'Not found · Kursi',
    footer: true,
  };
}

export function App() {
  const { page, title, footer } = route(currentPath.value);
  useEffect(() => {
    document.title = title;
  }, [title]);
  useEffect(() => {
    document.body.classList.add('has-mobile-nav');
  }, []);

  return (
    <>
      <Header />
      <main>{page}</main>
      {footer && <Footer />}
      <Toasts />
    </>
  );
}

function Footer() {
  return (
    <footer class="site-footer">
      <div class="container">
        <Wordmark />
        <span>Har kursi ka ek hi maalik. A demo by <a href="https://amogh.cloud">Amogh Upadhyay</a>; no real tickets or money.</span>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: '18px', flexWrap: 'wrap' }}>
          <a href="https://github.com/The-DarkMatter/seat-reservation" target="_blank" rel="noopener">Source</a>
          <a href={DASHBOARD_URL} target="_blank" rel="noopener">Live metrics</a>
          <a href="https://github.com/The-DarkMatter/seat-reservation/blob/main/WRITEUP.md" target="_blank" rel="noopener">How it works</a>
        </span>
      </div>
    </footer>
  );
}
