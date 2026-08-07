import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backendPort = process.env.SERVER_PORT || '8080';
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET || `http://127.0.0.1:${backendPort}`;
const proxy = {
  '/api': apiProxyTarget,
  '/fastship': apiProxyTarget,
  '/quickexpress': apiProxyTarget,
  '/reliablecourier': apiProxyTarget,
};

export default defineConfig({
  root: 'client',
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy,
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy,
  },
  build: {
    outDir: '../dist',
    emptyOutDir: true,
  },
});
