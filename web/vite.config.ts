import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// `npm run dev` serves the UI on :5173 and proxies API calls to the Spring app on :8080.
const api = 'http://localhost:8080';

export default defineConfig({
  plugins: [preact()],
  server: {
    proxy: {
      '/auth': api,
      '/shows': api,
      '/reservations': api,
      '/me/reservations': api,
      '/demo': api,
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
  },
});
