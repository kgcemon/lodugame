import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { brotliCompressSync, constants, gzipSync } from 'node:zlib';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Write .br and .gz copies of the built JS/CSS/SVG next to each file, compressed once at
 * maximum level, so the server can send them without compressing on every request.
 */
function precompress(): Plugin {
  return {
    name: 'precompress',
    apply: 'build',
    closeBundle() {
      const walk = (dir: string): void => {
        for (const name of readdirSync(dir)) {
          const file = join(dir, name);
          if (statSync(file).isDirectory()) walk(file);
          else if (/\.(js|css|svg)$/.test(name) && statSync(file).size >= 1024) {
            const data = readFileSync(file);
            writeFileSync(`${file}.br`, brotliCompressSync(data, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }));
            writeFileSync(`${file}.gz`, gzipSync(data, { level: 9 }));
          }
        }
      };
      walk('dist');
    },
  };
}

const SERVER_PORT = Number(process.env.PORT ?? 3001);

export default defineConfig({
  plugins: [react(), precompress()],
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
