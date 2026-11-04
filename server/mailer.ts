import type { ServerConfig } from './config';

/**
 * Transactional email. With RESEND_API_KEY set, mail is sent through Resend's
 * HTTPS API (no extra dependency). Without it, the link is printed to the
 * server console — fine for local development, never for production.
 */

export interface Mail {
  to: string;
  subject: string;
  text: string;
}

export interface Mailer {
  send(mail: Mail): Promise<void>;
  /** Mails captured in 'memory' mode (tests). */
  outbox: Mail[];
}

export function createMailer(cfg: ServerConfig['mail']): Mailer {
  const outbox: Mail[] = [];
  return {
    outbox,
    async send(mail) {
      if (cfg.mode === 'memory') {
        outbox.push(mail);
        return;
      }
      if (cfg.mode === 'disabled') {
        // Never the body: it holds password-reset and verification links.
        console.warn(`[mail] not sent, RESEND_API_KEY is not set: ${mail.subject}`);
        return;
      }
      if (cfg.mode === 'console') {
        console.log(`\n[mail] (console mode — set RESEND_API_KEY to send real email)\n  to: ${mail.to}\n  subject: ${mail.subject}\n  ${mail.text.replace(/\n/g, '\n  ')}\n`);
        return;
      }
      const res = await fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${cfg.resendApiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ from: cfg.from, to: [mail.to], subject: mail.subject, text: mail.text }),
      });
      if (!res.ok) throw new Error(`Email provider responded ${res.status}`);
    },
  };
}
