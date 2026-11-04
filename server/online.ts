import type { Db } from './db';
import type { Presence } from './presence';

/**
 * "Your friend is online": when a player comes online (after being away for a while),
 * each of their friends who is online gets a live alert, with how many matches they played
 * together and whether this is the friend they play with most (a special "best friend"
 * alert in the app).
 */

/** Matches together before a friend can be someone's best friend. */
const BEST_FRIEND_MIN_MATCHES = 3;

export async function announceOnline(db: Db, presence: Presence, userId: string): Promise<void> {
  const friends = (
    await db.query<{ other: string }>(
      'select case when user_a = $1 then user_b else user_a end as other from friendships where user_a = $1 or user_b = $1',
      [userId],
    )
  ).rows
    .map((r) => r.other)
    .filter((id) => presence.isOnline(id));
  if (!friends.length) return;

  const p = (
    await db.query<{ username: string; public_id: string; avatar_hue: number; avatar_url: string | null; frame: string | null; country: string | null }>(
      'select username, public_id, avatar_hue, avatar_url, frame, country from profiles where user_id = $1',
      [userId],
    )
  ).rows[0];
  if (!p) return;
  const player = { username: p.username, publicId: p.public_id, avatarHue: p.avatar_hue, avatarUrl: p.avatar_url, frame: p.frame, country: p.country };

  // For each online friend, whom they have played with among their friends, in one query.
  const marks = friends.map((_, i) => `$${i + 1}`).join(', ');
  const together = (
    await db.query<{ friend: string; other: string; n: number | string }>(
      `select a.user_id as friend, b.user_id as other, count(*) as n
       from match_players a
       join match_players b on b.match_id = a.match_id and b.user_id <> a.user_id
       join friendships fr on fr.user_a = least(a.user_id, b.user_id) and fr.user_b = greatest(a.user_id, b.user_id)
       where a.user_id in (${marks})
       group by a.user_id, b.user_id`,
      friends,
    )
  ).rows;
  for (const f of friends) {
    const mine = together.filter((t) => t.friend === f);
    const played = Number(mine.find((t) => t.other === userId)?.n ?? 0);
    const top = Math.max(0, ...mine.map((t) => Number(t.n)));
    const best = played >= BEST_FRIEND_MIN_MATCHES && played === top;
    presence.emit(f, 'friend:online', { player, played, best });
  }
}
