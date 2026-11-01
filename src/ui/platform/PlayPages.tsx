import { motion } from 'framer-motion';
import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { RoomPreview } from '../../../shared/protocol';
import { net, type RoomResult } from '../../game/net';
import { fmt, get, type MatchMode } from '../../lib/api';
import { useAuth } from '../../store/auth';
import { useGame } from '../../store/game';
import { Button, Modal, Segmented } from '../common';
import { IconBolt, IconBot, IconCrown, IconGamepad, IconKey, IconPlay, IconPlus, IconTrophy, IconUsers, IconWallet } from '../icons';
import { Card, CoinIcon, Confirm, ErrorNote, PageTitle, Spinner } from './kit';

/** Mode cards, Play Now, Quick Match, Create Room, Join Room and the shared paid-entry flow. */

const MODE_STYLE: Record<string, { cls: string; icon: ReactNode }> = {
  quick: { cls: 'mode-quick', icon: <IconBolt size={22} /> },
  classic: { cls: 'mode-classic', icon: <IconGamepad size={22} /> },
  pro: { cls: 'mode-pro', icon: <IconTrophy size={22} /> },
  master: { cls: 'mode-master', icon: <IconCrown size={22} /> },
  friendly: { cls: 'mode-friendly', icon: <IconUsers size={22} /> },
};

export function useModes(includePrivate = false): MatchMode[] {
  const modes = useAuth((s) => s.config?.modes ?? []);
  return modes.filter((m) => m.enabled && (includePrivate || !m.privateOnly));
}

type EntryRequest =
  | { kind: 'quick'; mode: MatchMode }
  | { kind: 'create'; mode: MatchMode; maxPlayers: number; teams: boolean }
  | { kind: 'join'; preview: RoomPreview };

/** Confirm the fee, call the server, handle "insufficient coins", go to the room. */
export function useEntryFlow() {
  const nav = useNavigate();
  const balance = useAuth((s) => s.wallet?.balance ?? 0);
  const multiplier = useAuth((s) => s.config?.payoutMultiplier ?? 2);
  const [req, setReq] = useState<EntryRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [insufficient, setInsufficient] = useState(false);

  const fee = !req ? 0 : req.kind === 'join' ? req.preview.entryFee : req.mode.entryFee;
  const name = !req ? '' : req.kind === 'join' ? req.preview.mode.name : req.mode.name;

  const run = async () => {
    if (!req) return;
    setBusy(true);
    setError(null);
    let res: RoomResult;
    if (req.kind === 'quick') res = await net.quick(req.mode.id);
    else if (req.kind === 'create') res = await net.create(req.mode.id, req.maxPlayers, req.teams);
    else res = await net.join(req.preview.code);
    setBusy(false);
    if (res.ok) {
      setReq(null);
      nav('/room');
    } else if (res.code === 'INSUFFICIENT_COINS') {
      setReq(null);
      setInsufficient(true);
    } else setError(res.error);
  };

  const start = (r: EntryRequest) => {
    const f = r.kind === 'join' ? r.preview.entryFee : r.mode.entryFee;
    setError(null);
    if (f > balance) setInsufficient(true);
    else setReq(r);
  };

  const ui = (
    <>
      {req && (
        <Confirm
          title={req.kind === 'create' ? `Create ${name} room` : `Join ${name}`}
          confirmLabel={fee > 0 ? `Pay ${fmt(fee)} coins & ${req.kind === 'create' ? 'create' : 'join'}` : req.kind === 'create' ? 'Create room' : 'Join room'}
          busy={busy}
          onConfirm={run}
          onClose={() => !busy && setReq(null)}
        >
          {fee > 0 ? (
            <div className="fee-box">
              <div>
                <span className="muted">Entry fee</span>
                <b className="coins">
                  <CoinIcon /> {fmt(fee)}
                </b>
              </div>
              <div>
                <span className="muted">Winner receives</span>
                <b className="coins">
                  <CoinIcon /> {fmt(Math.round(fee * multiplier))}
                </b>
              </div>
              <div>
                <span className="muted">Your balance after paying</span>
                <b>{fmt(balance - fee)}</b>
              </div>
            </div>
          ) : (
            <p className="muted">This room is free — no coins are at stake.</p>
          )}
          {fee > 0 && <p className="hint">The fee is refunded if you leave before the match starts. It is not refunded after the start.</p>}
          {req.kind === 'quick' && <p className="hint">You'll be matched with other players choosing {name}. The match starts when the room fills.</p>}
          <ErrorNote error={error} />
        </Confirm>
      )}
      {insufficient && <InsufficientCoins onClose={() => setInsufficient(false)} />}
    </>
  );
  return { start, ui };
}

export function InsufficientCoins({ onClose }: { onClose: () => void }) {
  const nav = useNavigate();
  return (
    <Modal title="Not enough coins" onClose={onClose}>
      <div className="stack center-text">
        <CoinIcon size={56} />
        <p className="insufficient">Insufficient Coins! Play more matches or collect your free bonus.</p>
        <Button
          variant="primary"
          icon={<IconWallet />}
          onClick={() => {
            onClose();
            nav('/wallet');
          }}
        >
          Open wallet & free bonus
        </Button>
        <Button
          variant="ghost"
          onClick={() => {
            onClose();
            nav('/create?mode=friendly');
          }}
        >
          Play a free Friendly room instead
        </Button>
      </div>
    </Modal>
  );
}

export function ModeCard({ mode, onJoin, compact }: { mode: MatchMode; onJoin: () => void; compact?: boolean }) {
  const balance = useAuth((s) => s.wallet?.balance ?? 0);
  const style = MODE_STYLE[mode.id] ?? MODE_STYLE.classic;
  const short = mode.entryFee > balance;
  return (
    <motion.article className={`mode-card glass ${style.cls} ${compact ? 'compact' : ''}`} whileHover={{ y: -4 }} transition={{ type: 'spring', stiffness: 300, damping: 20 }}>
      <div className="mode-head">
        <span className="mode-icon">{style.icon}</span>
        <div>
          <h3>{mode.name}</h3>
          <span className="mode-type">{mode.matchType}</span>
        </div>
      </div>
      {!compact && <p className="mode-desc">{mode.description}</p>}
      <dl className="mode-facts">
        <div>
          <dt>Entry fee</dt>
          <dd className="coins">
            <CoinIcon size={16} /> {mode.entryFee ? fmt(mode.entryFee) : 'Free'}
          </dd>
        </div>
        <div>
          <dt>Players</dt>
          <dd>Up to {mode.maxPlayers}</dd>
        </div>
      </dl>
      <Button variant={short ? 'glass' : 'primary'} onClick={onJoin} title={short ? 'Not enough coins for this mode' : undefined}>
        {short ? `Need ${fmt(mode.entryFee - balance)} more coins` : 'Join'}
      </Button>
    </motion.article>
  );
}

export function PlayNowButton({ onPlay }: { onPlay: () => void }) {
  const modes = useModes();
  const quick = modes.find((m) => m.id === 'quick') ?? modes[0];
  return (
    <motion.button className="play-now" onClick={onPlay} whileHover={{ scale: 1.02 }} whileTap={{ scale: 0.98 }} aria-label="Play now">
      <IconPlay size={30} />
      <span>
        <b>PLAY NOW</b>
        <small>{quick ? `${quick.name} · ${quick.entryFee ? `${quick.entryFee} coins` : 'free'}` : 'Find a match'}</small>
      </span>
    </motion.button>
  );
}

/** "Play Game": every way to play, each with a one-line explanation. */
export function PlayHub() {
  const nav = useNavigate();
  const modes = useModes();
  const flow = useEntryFlow();
  const quick = modes.find((m) => m.id === 'quick') ?? modes[0];
  return (
    <div className="page">
      <PageTitle title="Play Game" subtitle="Choose how you want to play. Paid modes use coins; practice and Friendly rooms are free." />
      {quick && <PlayNowButton onPlay={() => flow.start({ kind: 'quick', mode: quick })} />}
      <h2 className="section-title">Online matches (coins)</h2>
      <div className="mode-grid">
        {modes.map((m) => (
          <ModeCard key={m.id} mode={m} onJoin={() => flow.start({ kind: 'quick', mode: m })} />
        ))}
      </div>
      <h2 className="section-title">Play with friends</h2>
      <div className="option-grid">
        <OptionCard icon={<IconPlus />} title="Create Room" text="Make a private room, pick the mode and share the code." onClick={() => nav('/create')} />
        <OptionCard icon={<IconKey />} title="Join Room" text="Have a room code from a friend? Enter it here." onClick={() => nav('/join')} />
      </div>
      <h2 className="section-title">Offline practice (free)</h2>
      <div className="option-grid">
        <OptionCard icon={<IconBot />} title="Practice vs AI" text="Play against Easy, Medium or Hard bots. No coins." onClick={() => nav('/practice?preset=ai')} />
        <OptionCard icon={<IconUsers />} title="Pass & Play" text="Several people taking turns on this device." onClick={() => nav('/practice?preset=local')} />
      </div>
      {flow.ui}
    </div>
  );
}

function OptionCard({ icon, title, text, onClick }: { icon: ReactNode; title: string; text: string; onClick: () => void }) {
  return (
    <button className="option-card glass" onClick={onClick}>
      <span className="option-icon">{icon}</span>
      <span>
        <b>{title}</b>
        <small>{text}</small>
      </span>
    </button>
  );
}

export function QuickMatchPage() {
  const modes = useModes();
  const flow = useEntryFlow();
  const policy = useAuth((s) => s.config?.payoutPolicy);
  return (
    <div className="page">
      <PageTitle title="Quick Match" subtitle="Pick a mode and we'll match you with other players." />
      <div className="mode-grid">
        {modes.map((m) => (
          <ModeCard key={m.id} mode={m} onJoin={() => flow.start({ kind: 'quick', mode: m })} />
        ))}
      </div>
      {policy && <p className="hint policy">{policy}</p>}
      {flow.ui}
    </div>
  );
}

export function CreateRoomPage() {
  const [params] = useSearchParams();
  const modes = useModes(true);
  const [modeId, setModeId] = useState(params.get('mode') ?? 'friendly');
  const [players, setPlayers] = useState(4);
  const [teams, setTeams] = useState(false);
  const flow = useEntryFlow();
  const mode = modes.find((m) => m.id === modeId) ?? modes[0];
  if (!mode) return <Spinner />;
  return (
    <div className="page narrow">
      <PageTitle title="Create Room" subtitle="Private room with a code you can share. Everyone in the room pays the same entry fee." />
      <Card>
        <div className="stack">
          <span className="label">Game mode & entry fee</span>
          <div className="mode-pick" role="radiogroup">
            {modes.map((m) => (
              <button key={m.id} role="radio" aria-checked={m.id === mode.id} className={`mode-pill ${m.id === mode.id ? 'active' : ''}`} onClick={() => setModeId(m.id)}>
                <b>{m.name}</b>
                <span className="coins">
                  <CoinIcon size={14} /> {m.entryFee ? fmt(m.entryFee) : 'Free'}
                </span>
              </button>
            ))}
          </div>
          <p className="muted">{mode.description}</p>
          <div className="field">
            <span className="label">Players</span>
            <Segmented value={players} options={[2, 3, 4].map((n) => ({ value: n, label: `${n} players` }))} onChange={setPlayers} />
          </div>
          {players === 4 && (
            <div className="field">
              <span className="label">Play as</span>
              <Segmented
                value={teams ? 'teams' : 'solo'}
                options={[
                  { value: 'solo', label: 'Everyone for themselves' },
                  { value: 'teams', label: '🤝 2 vs 2 teams' },
                ]}
                onChange={(v) => setTeams(v === 'teams')}
              />
              {teams && <p className="hint">Seats 1 + 3 play against 2 + 4. Partners can't capture each other and get a private team chat.</p>}
            </div>
          )}
          <Button variant="primary" size="lg" icon={<IconPlus />} onClick={() => flow.start({ kind: 'create', mode, maxPlayers: players, teams: players === 4 && teams })}>
            Create room
          </Button>
        </div>
      </Card>
      {flow.ui}
    </div>
  );
}

export function JoinRoomPage() {
  const [params] = useSearchParams();
  const [code, setCode] = useState((params.get('code') ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 5));
  const [preview, setPreview] = useState<RoomPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const flow = useEntryFlow();

  const lookup = async (c: string) => {
    if (c.length !== 5) return;
    setLoading(true);
    setError(null);
    try {
      const r = await get<{ room: RoomPreview }>(`/rooms/${encodeURIComponent(c)}`);
      setPreview(r.room);
    } catch (e) {
      setPreview(null);
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  // Opening an invitation link only shows the room; joining (and paying) needs an explicit click.
  useEffect(() => {
    if (code.length === 5) void lookup(code);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void lookup(code);
  };

  return (
    <div className="page narrow">
      <PageTitle title="Join Room" subtitle="Enter the 5-character code your friend shared." />
      <Card>
        <form className="join-row" onSubmit={submit}>
          <input
            className="input code-input"
            placeholder="CODE"
            value={code}
            maxLength={5}
            autoCapitalize="characters"
            autoComplete="off"
            aria-label="Room code"
            onChange={(e) => {
              setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''));
              setPreview(null);
            }}
          />
          <Button variant="primary" type="submit" disabled={code.length !== 5 || loading}>
            {loading ? 'Checking…' : 'Find room'}
          </Button>
        </form>
        <ErrorNote error={error} />
        {preview && (
          <div className="room-preview">
            <h3>{preview.mode.name}</h3>
            <p className="muted">{preview.mode.description}</p>
            <dl className="mode-facts">
              <div>
                <dt>Entry fee</dt>
                <dd className="coins">
                  <CoinIcon size={16} /> {preview.entryFee ? fmt(preview.entryFee) : 'Free'}
                </dd>
              </div>
              <div>
                <dt>Players</dt>
                <dd>
                  {preview.players} / {preview.maxPlayers}
                </dd>
              </div>
              <div>
                <dt>Host</dt>
                <dd>{preview.hostName ?? '—'}</dd>
              </div>
            </dl>
            {preview.joinable ? (
              <Button variant="primary" size="lg" onClick={() => flow.start({ kind: 'join', preview })}>
                {preview.entryFee ? `Join for ${fmt(preview.entryFee)} coins` : 'Join room'}
              </Button>
            ) : (
              <p className="form-error">{preview.status === 'lobby' ? 'This room is full.' : 'This match has already started.'}</p>
            )}
          </div>
        )}
      </Card>
      {flow.ui}
    </div>
  );
}

/** Redirect to the current room when the socket reports one (used after reconnecting). */
export function useCurrentRoomCode(): string | null {
  return useGame((s) => s.room?.code ?? null);
}
