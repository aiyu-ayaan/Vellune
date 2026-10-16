import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Same-origin API in dev so the HttpOnly session cookie and WebSocket just work.
    proxy: {
      '/api': { target: process.env.VITE_API_PROXY ?? 'http://127.0.0.1:8080', ws: true },
    },
  },
});
