import { existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { loadConfig } from '../config';
import { connectDb, migrate } from './index';

/** `npm run db:migrate` — apply pending migrations and exit (the server also does this on start). */
try {
  if (existsSync('.env')) process.loadEnvFile('.env');
} catch {
  /* optional */
}
const cfg = loadConfig();
if (!cfg.databaseUrl) mkdirSync(dirname(resolve(cfg.pgliteDir)), { recursive: true });
const db = await connectDb(cfg.databaseUrl ? { url: cfg.databaseUrl } : { dataDir: resolve(cfg.pgliteDir) });
const again = await migrate(db);
const applied = (await db.query<{ name: string }>('select name from schema_migrations order by name')).rows.map((r) => r.name);
const label = { postgres: 'PostgreSQL', mysql: 'MySQL', pglite: `PGlite at ${cfg.pgliteDir}` }[db.kind];
console.log(`[db] ${label} — migrations applied: ${applied.join(', ')}${again.length ? '' : ' (nothing pending)'}`);
await db.close();
