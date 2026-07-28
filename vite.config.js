import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  root: 'client',
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/fastship': 'http://127.0.0.1:8080',
      '/quickexpress': 'http://127.0.0.1:8080',
      '/reliablecourier': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: '../public',
    emptyOutDir: true,
  },
});
