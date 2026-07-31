import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  root: 'client',
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080',
      '/fastship': process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080',
      '/quickexpress': process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080',
      '/reliablecourier': process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: '../public',
    emptyOutDir: true,
  },
});
