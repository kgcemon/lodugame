import { useEffect, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { net } from '../../game/net';
import { fmt, get, post } from '../../lib/api';
import { useAuth } from '../../store/auth';
import { useGame } from '../../store/game';
import { PLAYER_HEX } from '../../three/layout';
import { Avatar, Button } from '../common';
import { IconBot, IconCheck, IconClose, IconCopy, IconCrown, IconLink, IconLogout, IconPlay, IconShare, IconTelegram, IconWhatsApp } from '../icons';
import { Card, CoinIcon, PageTitle } from './kit';

/** Waiting room for an online match: seats, ready/start, and every way to invite friends. */

export function inviteLink(code: string): string {
  const base = useAuth.getState().config?.appBaseUrl || location.origin;
  return `${base}/join?code=${encodeURIComponent(code)}`;
}

export function inviteText(code: string): string {
  return `Join my 3D Ludo match!\nRoom Code: ${code}\nOpen the game, enter the room code, and play with me.\n${inviteLink(code)}`;
}

async function copy(text: string, what: string): Promise<void> {
  const toast = useGame.getState().toast;
  try {
    await navigator.clipboard.writeText(text);
    toast(`${what} copied`);
  } catch {
    // Fallback for browsers without the async clipboard API.
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    toast(ok ? `${what} copied` : text);
  }
}

export function InvitePanel({ code }: { code: string }) {
  const link = inviteLink(code);
  const text = inviteText(code);
  const canShare = typeof navigator.share === 'function';
  return (
    <div className="invite">
      <div className="room-code">
        <span className="label">Room code</span>
        <strong>{code}</strong>
      </div>
      {/* Native share sheet where supported (most phones); otherwise copy the full invitation. */}
      <Button
        variant="primary"
        size="lg"
        icon={<IconShare />}
        className="invite-big"
        onClick={() => (canShare ? navigator.share({ title: 'Ludo 3D', text, url: link }).catch(() => {}) : copy(text, 'Invitation'))}
      >
        Invite Friends
      </Button>
      <div className="invite-grid">
        <Button icon={<IconCopy size={18} />} onClick={() => copy(code, 'Room code')}>
          Copy code
        </Button>
        <Button icon={<IconLink size={18} />} onClick={() => copy(link, 'Invite link')}>
          Copy link
        </Button>
        <a className="btn btn-glass btn-md share-wa" href={`https://wa.me/?text=${encodeURIComponent(text)}`} target="_blank" rel="noopener noreferrer">
          <IconWhatsApp size={18} />
          <span>WhatsApp</span>
        </a>
        <a
          className="btn btn-glass btn-md share-tg"
          href={`https://t.me/share/url?url=${encodeURIComponent(link)}&text=${encodeURIComponent(`Join my 3D Ludo match! Room Code: ${code}`)}`}
          target="_blank"
          rel="noopener noreferrer"
        >
          <IconTelegram size={18} />
          <span>Telegram</span>
        </a>
      </div>
    </div>
  );
}

interface FriendRow {
  username: string;
  publicId: string;
  avatarHue: number;
  online: boolean;
}

function InviteFriends() {
  const version = useAuth((s) => s.friendsVersion);
  const [friends, setFriends] = useState<FriendRow[]>([]);
  const [sent, setSent] = useState<string[]>([]);
  useEffect(() => {
    get<{ friends: FriendRow[] }>('/friends').then((r) => setFriends(r.friends), () => {});
  }, [version]);
  if (!friends.length) return <p className="muted">Add friends to invite them here with one tap.</p>;
  return (
    <ul className="friend-mini">
      {friends.map((f) => (
        <li key={f.publicId}>
          <Avatar name={f.username} hue={f.avatarHue} size={30} />
          <span>{f.username}</span>
          <i className={`dot ${f.online ? 'on' : ''}`} aria-label={f.online ? 'online' : 'offline'} />
          <Button
            size="sm"
            disabled={sent.includes(f.publicId)}
            onClick={async () => {
              try {
                await post('/friends/invite', { publicId: f.publicId });
                setSent([...sent, f.publicId]);
                useGame.getState().toast(`Invite sent to ${f.username}`);
              } catch (e) {
                useGame.getState().toast((e as Error).message);
              }
            }}
          >
            {sent.includes(f.publicId) ? 'Invited' : 'Invite'}
          </Button>
        </li>
      ))}
    </ul>
  );
}

export function RoomPage() {
  const room = useGame((s) => s.room);
  const nav = useNavigate();
  if (!room) return <Navigate to="/play" replace />;
  const me = room.seats[room.you];
  const isHost = !!me?.isHost;
  const everyoneReady = room.seats.every((s) => s.ready);
  const leave = async () => {
    await net.leave();
    nav('/play');
  };

  return (
    <div className="page room-page">
      <PageTitle
        title={room.isPublic ? `${room.mode.name} — finding players` : `${room.mode.name} room`}
        subtitle={
          <span className="coins">
            Entry fee <CoinIcon size={16} /> {room.entryFee ? fmt(room.entryFee) : 'free'}
            {room.entryFee > 0 && <> · winner receives {fmt(Math.round(room.entryFee * room.payoutMultiplier))}</>}
          </span>
        }
        action={
          <Button variant="ghost" icon={<IconLogout size={18} />} onClick={leave}>
            Leave{room.entryFee ? ' & refund' : ''}
          </Button>
        }
      />
      <div className="room-grid">
        <Card title={`Players ${room.seats.length}/${room.maxPlayers}${room.teams ? ' · 2 vs 2' : ''}`}>
          {room.teams && <p className="hint">Team A (seats 1 + 3) plays Team B (seats 2 + 4). Partners share a private team chat in the match.</p>}
          <ul className="seat-list">
            {Array.from({ length: room.maxPlayers }, (_, i) => {
              const s = room.seats[i];
              if (!s) {
                return (
                  <li className="seat-row empty" key={`empty-${i}`}>
                    <span className="seat-dot" />
                    <span className="muted">{room.isPublic ? 'Searching for a player…' : 'Waiting for a player…'}</span>
                    {isHost && !room.isPublic && room.entryFee === 0 && (
                      <Button size="sm" icon={<IconBot size={16} />} onClick={() => net.addBot()}>
                        Add bot
                      </Button>
                    )}
                  </li>
                );
              }
              return (
                <li className="seat-row" key={s.id} style={{ ['--seat' as string]: PLAYER_HEX[s.color] }}>
                  <Avatar name={s.name} hue={s.kind === 'ai' ? undefined : s.avatarHue} color={s.kind === 'ai' ? s.color : undefined} size={34} />
                  <span className="seat-name">
                    {s.name}
                    {i === room.you && <em> (you)</em>}
                  </span>
                  {s.team !== null && (
                    <span className={`tag team-tag team-${s.team}`}>
                      Team {s.team === 0 ? 'A' : 'B'}
                      {i !== room.you && s.team === room.seats[room.you]?.team ? ' · partner' : ''}
                    </span>
                  )}
                  {s.isHost && (
                    <span className="tag tag-gold" title="Host">
                      <IconCrown size={14} />
                    </span>
                  )}
                  {s.kind === 'ai' && <span className="tag">BOT</span>}
                  {!s.connected && <span className="tag tag-warn">{s.ready ? 'offline' : 'joining'}</span>}
                  <span className={`ready ${s.ready ? 'yes' : ''}`}>{s.ready ? <IconCheck size={16} /> : 'not ready'}</span>
                  {isHost && i !== room.you && !room.isPublic && (
                    <button className="mini-x" aria-label={`Remove ${s.name}`} onClick={() => net.kick(i)}>
                      <IconClose size={14} />
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
          {room.isPublic ? (
            <p className="hint">The match starts automatically when the room is full (or after a short wait with at least 2 players).</p>
          ) : isHost ? (
            <>
              <Button variant="primary" size="lg" icon={<IconPlay />} disabled={room.seats.length < (room.teams ? 4 : 2) || !everyoneReady} onClick={() => net.start()}>
                Start Match
              </Button>
              <p className="hint">
                {room.seats.length < (room.teams ? 4 : 2)
                  ? room.teams ? '2 vs 2 needs 4 players — invite friends.' : 'Invite a friend to begin.'
                  : everyoneReady ? 'Everyone is ready!' : 'Waiting for players to get ready…'}
              </p>
            </>
          ) : (
            <Button variant={me?.ready ? 'glass' : 'primary'} size="lg" icon={<IconCheck />} onClick={() => net.ready(!me?.ready)}>
              {me?.ready ? 'Not ready' : "I'm ready"}
            </Button>
          )}
        </Card>
        {!room.isPublic && (
          <Card title="Invite friends">
            <InvitePanel code={room.code} />
            <h3 className="sub-title">Your friends</h3>
            <InviteFriends />
          </Card>
        )}
      </div>
    </div>
  );
}
