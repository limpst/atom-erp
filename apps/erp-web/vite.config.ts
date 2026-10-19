/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// 로컬 전용: dev 서버는 127.0.0.1 에만 바인딩, /api 는 백엔드(기본 127.0.0.1:8081)로 프록시.
const backend = process.env.ATOM_ERP_BACKEND ?? 'http://127.0.0.1:8081';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy: { '/api': { target: backend } } },
  build: { sourcemap: true, chunkSizeWarningLimit: 2000 },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
