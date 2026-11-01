/** Server configuration from environment variables (see .env.example). */

export interface ServerConfig {
  port: number;
  /** Public URL players open; used in emails and invitation links. */
  appBaseUrl: string;
  /** Browser origins allowed to call the API / open sockets. */
  allowedOrigins: string[];
  databaseUrl?: string;
  /** PGlite directory when DATABASE_URL is not set ('memory' = in-memory). */
  pgliteDir: string;
  secureCookies: boolean;
  requireEmailVerification: boolean;
  /** Emails that become administrators when they register (or on next login). */
  adminEmails: string[];
  mail: { mode: 'console' | 'resend' | 'memory'; from: string; resendApiKey?: string };
  staticDir?: string | null;
  /** Per-IP limits on account endpoints. */
  authLimits: { registrationsPer10Min: number; loginsPerMin: number };
}

function list(v: string | undefined): string[] {
  return (v ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const port = Number(env.PORT ?? 3001);
  const appBaseUrl = (env.APP_BASE_URL ?? 'http://localhost:5173').replace(/\/+$/, '');
  const production = env.NODE_ENV === 'production';
  return {
    port,
    appBaseUrl,
    allowedOrigins: [...new Set([appBaseUrl, `http://localhost:${port}`, ...list(env.CORS_ORIGIN)])],
    databaseUrl: env.DATABASE_URL || undefined,
    pgliteDir: env.PGLITE_DIR ?? './data/pglite',
    secureCookies: env.COOKIE_SECURE ? env.COOKIE_SECURE === 'true' : production,
    requireEmailVerification: env.REQUIRE_EMAIL_VERIFICATION === 'true',
    adminEmails: list(env.ADMIN_EMAILS).map((e) => e.toLowerCase()),
    mail: {
      mode: env.RESEND_API_KEY ? 'resend' : 'console',
      from: env.MAIL_FROM ?? 'Ludo 3D <no-reply@localhost>',
      resendApiKey: env.RESEND_API_KEY,
    },
    staticDir: env.STATIC_DIR === 'none' ? null : undefined,
    authLimits: { registrationsPer10Min: Number(env.REGISTER_LIMIT ?? 5), loginsPerMin: Number(env.LOGIN_LIMIT ?? 10) },
  };
}
