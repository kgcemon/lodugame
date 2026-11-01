import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const SERVER_PORT = Number(process.env.PORT ?? 3001);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/socket.io': { target: `http://localhost:${SERVER_PORT}`, ws: true },
      '/api': { target: `http://localhost:${SERVER_PORT}` },
    },
  },
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 1800,
    rollupOptions: {
      output: {
        // 3D libraries load with the game; everything else (React, router, state) is the app vendor chunk.
        manualChunks(id) {
          // Vite's preload helper is shared with drei's lazy imports; keep it out of the 3D chunk.
          if (id.includes('vite/preload-helper') || id.includes('commonjsHelpers')) return 'vendor';
          if (!id.includes('node_modules')) return undefined;
          if (/node_modules[\/]three[\/]/.test(id)) return 'three';
          if (/@react-three|three-stdlib|three-mesh-bvh|troika|camera-controls|maath|meshline|stats-gl|@monogrid|detect-gpu|hls\.js|suspend-react|its-fine|tunnel-rat/.test(id)) return 'r3f';
          return 'vendor';
        },
      },
    },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    testTimeout: 20000,
  },
});
