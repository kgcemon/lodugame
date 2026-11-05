import type { Express, Request, Response } from 'express';

/**
 * The public privacy policy and terms of service (linked from Google Sign-In's consent
 * screen and the Play Store listing). Plain server-rendered pages, so they load without
 * the game's JavaScript and are easy for Google's reviewers to read.
 */

const CONTACT = 'kgcimambd@gmail.com';
const UPDATED = '5 November 2026';

function page(title: string, body: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} – LudoX</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; background: #1b0b4a; color: #ece8ff; font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 32px 16px 64px; }
  h1 { color: #ffd34d; font-size: 30px; margin: 0 0 4px; }
  h2 { color: #ffd34d; font-size: 20px; margin: 28px 0 8px; }
  p, li { color: #d9d2ff; }
  a { color: #8fd3ff; }
  .muted { color: #a99fd6; font-size: 14px; }
  nav { margin-top: 36px; font-size: 14px; }
</style>
</head>
<body><main>
${body}
<nav><a href="/privacy">Privacy policy</a> · <a href="/terms">Terms of service</a> · <a href="/">LudoX</a></nav>
</main></body>
</html>`;
}

const PRIVACY = page(
  'Privacy policy',
  `<h1>Privacy policy</h1>
<p class="muted">LudoX · last updated ${UPDATED}</p>
<p>LudoX is an online Ludo game for Android and the web. This policy explains what we collect when you play, why, and what you can do about it.</p>

<h2>What we collect</h2>
<ul>
  <li><b>Your Google account basics</b> when you sign in with Google: your name, email address, profile picture and Google account id. Other players can see your name and picture. We do not get your Google password or access to your other Google data.</li>
  <li><b>Your game profile</b>: the player name and avatar colour you choose, your public player id, friends and friend requests.</li>
  <li><b>Game activity</b>: matches you play, results, coins, diamonds, dice skins and the history of these balances.</li>
  <li><b>Purchases</b>: when you buy diamonds through Google Play we receive the order id and purchase token to confirm the payment. Payment details (card numbers and the like) stay with Google; we never see them.</li>
  <li><b>Chat and emotes</b> you send in match rooms, so the other players can see them.</li>
  <li><b>Voice chat</b>: the Android app uses your microphone only after you give permission and turn your mic on in a match room. While it is on, your voice goes through our server to your friends in that room, live. We do not record or store it, and players who are not your friends never hear it.</li>
  <li><b>Reports and blocks</b>: players you block, reports you send, and reports about you. A report keeps the note its sender wrote and, if you shared a room, the messages you sent there, for an administrator to review.</li>
  <li><b>Technical data</b>: IP address, device or browser type, and sign-in sessions, used to keep your account secure and prevent abuse.</li>
  <li><b>Your country</b>: we work it out from your IP address on our own server, without sending the address to anyone, and show it as a flag beside your name to other players. It does not give your precise location.</li>
</ul>

<h2>How we use it</h2>
<ul>
  <li>To run your account and the game: matchmaking, turns, rewards, balances and purchases.</li>
  <li>To show other players your name, avatar, dice skin and messages in matches and friend lists.</li>
  <li>To keep the game fair and safe: detect cheating, abuse and fraud, and handle support requests.</li>
</ul>
<p>We do not sell your personal data and we do not show third-party advertising.</p>

<h2>Who we share it with</h2>
<ul>
  <li><b>Google</b>, for Google Sign-In and Google Play purchases.</li>
  <li>Our hosting provider, which stores the game's data on our behalf.</li>
  <li>Authorities, only when the law requires it.</li>
</ul>

<h2>How long we keep it</h2>
<p>We keep your account data while your account exists. Match and balance history is kept for fairness and accounting. When you ask us to delete your account, we delete or anonymise your personal data within 30 days, except records we must keep by law.</p>

<h2>Your choices</h2>
<ul>
  <li>Change your player name and avatar colour in the app's Profile.</li>
  <li>Turn your microphone off in a match at any time, mute a friend's voice, or remove the microphone permission in your phone's settings. The game works without it.</li>
  <li>Ask for a copy of your data, a correction, or deletion of your account by emailing <a href="mailto:${CONTACT}">${CONTACT}</a> from the email of your account.</li>
  <li>You can remove LudoX's access to your Google account at any time in your Google Account settings (Security → Third-party apps).</li>
</ul>

<h2>Deleting your account</h2>
<p>You can delete your account in the app (Profile, Security) or by following the steps on <a href="/delete-account">this page</a>.</p>

<h2>Children</h2>
<p>LudoX is not directed at children under 13, and we do not knowingly collect data from them. If you believe a child has created an account, contact us and we will delete it.</p>

<h2>Security</h2>
<p>Connections are encrypted (HTTPS), sign-in sessions are stored only as one-way hashes, and access to the game's data is limited to the people who run it.</p>

<h2>Changes</h2>
<p>We will update this page when the policy changes and change the date at the top.</p>

<h2>Contact</h2>
<p><a href="mailto:${CONTACT}">${CONTACT}</a></p>`,
);

const TERMS = page(
  'Terms of service',
  `<h1>Terms of service</h1>
<p class="muted">LudoX · last updated ${UPDATED}</p>
<p>By playing LudoX you agree to these terms.</p>

<h2>Your account</h2>
<p>You sign in with your Google account and are responsible for what happens in your LudoX account. One person, one account.</p>

<h2>Coins, diamonds and dice skins</h2>
<p>Coins and diamonds are in-game items. They have no cash value, cannot be withdrawn, sold or exchanged for real money, and can only be used inside LudoX. Diamonds bought through Google Play are covered by Google Play's refund policy. Dice skins are kept on your account for as long as it exists.</p>

<h2>Fair play</h2>
<p>Do not cheat, use bots or modified apps, exploit bugs, harass other players or post offensive messages. We may remove balances gained unfairly and suspend or close accounts that break these rules.</p>

<h2>The service</h2>
<p>We work to keep LudoX running, but it is provided as is and may change, be interrupted or end. If a match is interrupted by a server problem, its entry fees are refunded as described in the game.</p>

<h2>Privacy</h2>
<p>How we handle your data is described in our <a href="/privacy">privacy policy</a>.</p>

<h2>Contact</h2>
<p><a href="mailto:${CONTACT}">${CONTACT}</a></p>`,
);

const DELETE_ACCOUNT = page(
  'Delete your LudoX account',
  `<h1>Delete your LudoX account</h1>
<p class="muted">LudoX · last updated ${UPDATED}</p>
<h2>In the app</h2>
<ol>
  <li>Open LudoX and go to <b>Profile</b>.</li>
  <li>Scroll down to <b>Security</b> and tap <b>Delete my account</b>.</li>
  <li>Confirm. It takes effect at once and cannot be undone. If you are in a match, finish or leave it first.</li>
</ol>
<h2>Can't open the app?</h2>
<p>Email <a href="mailto:${CONTACT}">${CONTACT}</a> from the address on your account and ask for it to be deleted.</p>
<h2>What is deleted</h2>
<p>Your name, email address, Google link, profile picture and country, your friends and friend requests, your notifications and your sign-in sessions. Your coins, diamonds and items can no longer be used.</p>
<h2>What is kept</h2>
<p>An anonymous record with no name or contact details: your place in other players' match history, and the coin, diamond and purchase records needed for accounting, fraud prevention and legal obligations.</p>`,
);

export function legalPages(app: Express): void {
  const send = (html: string) => (_req: Request, res: Response) => res.type('html').send(html);
  app.get(['/privacy', '/privacy-policy'], send(PRIVACY));
  app.get(['/terms', '/terms-of-service'], send(TERMS));
  app.get(['/delete-account', '/account-deletion'], send(DELETE_ACCOUNT));
}
