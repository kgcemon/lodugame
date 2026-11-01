import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useState } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { FINISH } from '../../shared/board';
import type { GameState } from '../../shared/types';
import { canRoll, leaveMatch, requestRoll, restartLocal, setPaused } from '../game/controller';
import { useReactions } from '../game/emotes';
import { net } from '../game/net';
import { isLocalTurn, useGame } from '../store/game';
import { useSettings, type CameraPreset } from '../store/settings';
import { PLAYER_HEX, screenCorner } from '../three/layout';
import { Avatar, Button, IconButton } from './common';
import { ChatPanel, EmotePanel, ReactionBar, SocialButtons } from './Social';
import { IconBook, IconBot, IconCamera, IconCrown, IconDice, IconLogout, IconMusic, IconMute, IconPause, IconPlay, IconRefresh, IconSettings, IconSound } from './icons';

const CAMERAS: CameraPreset[] = ['tabletop', 'close', 'top'];

function useNow(active: boolean) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(id);
  }, [active]);
  return now;
}

function PlayerPanels({ view }: { view: GameState }) {
  const viewColor = useGame((s) => s.viewColor);
  const localColors = useGame((s) => s.localColors);
  const matchKind = useGame((s) => s.matchKind);
  const room = useGame((s) => s.room);
  const deadline = useGame((s) => s.deadline);
  const busy = useGame((s) => s.busy);
  const now = useNow(matchKind === 'online' && !!deadline);
  const reactions = useReactions((s) => s.list);

  return (
    <>
      {view.players.map((p, i) => {
        const active = i === view.current && view.phase !== 'over';
        const home = p.tokens.filter((t) => t === FINISH).length;
        const seat = room?.seats.find((s) => s.color === p.color);
        const offline = matchKind === 'online' && seat && !seat.connected;
        const remaining = active && deadline && !busy ? Math.max(0, (deadline - now) / 1000) : null;
        const corner = screenCorner(p.color, viewColor);
        const reaction = [...reactions].reverse().find((r) => r.color === p.color);
        return (
          <motion.div
            key={p.color}
            className={`player-panel glass corner-${corner} ${active ? 'active' : ''}`}
            style={{ ['--pc' as string]: PLAYER_HEX[p.color] }}
            layout
            animate={{ scale: active ? 1.04 : 1, opacity: p.rank && view.phase !== 'over' ? 0.7 : 1 }}
          >
            <div className="pp-avatar">
              <Avatar name={p.name} color={p.color} size={40} />
              <AnimatePresence>
                {reaction && (
                  <motion.span
                    key={reaction.key}
                    className="pp-emote emoji"
                    aria-label={`${p.name} reacted`}
                    initial={{ scale: 0, rotate: -30 }}
                    animate={{ scale: 1, rotate: 0 }}
                    exit={{ scale: 0, opacity: 0 }}
                    transition={{ type: 'spring', stiffness: 400, damping: 15 }}
                  >
                    {reaction.emoji}
                  </motion.span>
                )}
              </AnimatePresence>
              {remaining !== null && (
                <svg className="timer-ring" viewBox="0 0 44 44">
                  <circle cx="22" cy="22" r="20" pathLength={100} strokeDasharray={`${Math.min(100, (remaining / 25) * 100)} 100`} />
                </svg>
              )}
            </div>
            <div className="pp-info">
              <div className="pp-name">
                {p.name}
                {localColors.includes(p.color) && matchKind !== 'local' && <em> you</em>}
              </div>
              <div className="pp-meta">
                {p.rank ? (
                  <span className="tag tag-gold">
                    <IconCrown size={12} /> #{p.rank}
                  </span>
                ) : (
                  <span className="pips" aria-label={`${home} of 4 tokens home`}>
                    {[0, 1, 2, 3].map((k) => (
                      <i key={k} className={k < home ? 'on' : ''} />
                    ))}
                  </span>
                )}
                {p.kind === 'ai' && <IconBot size={14} />}
                {offline && <span className="tag tag-warn">offline</span>}
                {remaining !== null && remaining < 10 && <span className="tag tag-warn">{Math.ceil(remaining)}s</span>}
              </div>
            </div>
          </motion.div>
        );
      })}
    </>
  );
}

const localColorsPresent = (c: string[]) => c.length > 0;

function statusText(s: Pick<ReturnType<typeof useGame.getState>, 'view' | 'busy' | 'rollPending' | 'matchKind' | 'localColors'>): string {
  const v = s.view!;
  if (v.phase === 'over') return 'Match over';
  const p = v.players[v.current];
  const mine = isLocalTurn(s);
  const who = s.matchKind === 'local' || !mine ? `${p.name}` : 'Your';
  if (s.busy) return s.rollPending || v.phase === 'roll' ? `${who === 'Your' ? 'Your' : `${who}'s`} turn` : 'Moving…';
  if (!mine) {
    if (p.kind === 'ai') return `${p.name} is thinking…`;
    return `Waiting for ${p.name}…`;
  }
  if (v.phase === 'roll') return s.matchKind === 'local' ? `${p.name}, roll the dice` : 'Your turn — roll the dice';
  return 'Tap a glowing token to move';
}

function PauseMenu() {
  const matchKind = useGame((s) => s.matchKind);
  const set = useGame((s) => s.set);
  const [confirm, setConfirm] = useState(false);
  return (
    <motion.div className="modal-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
      <motion.div className="glass modal pause" initial={{ scale: 0.95, y: 20 }} animate={{ scale: 1, y: 0 }}>
        <h2>{matchKind === 'online' ? 'Menu' : 'Paused'}</h2>
        <p className="hint">{matchKind === 'online' ? 'The online match keeps running while this menu is open.' : 'Bots wait while the game is paused.'}</p>
        <Button variant="primary" size="lg" icon={<IconPlay />} onClick={() => setPaused(false)}>
          Resume
        </Button>
        {matchKind !== 'online' && (
          <Button icon={<IconRefresh />} onClick={restartLocal}>
            Restart match
          </Button>
        )}
        <div className="row gap-s">
          <Button icon={<IconBook />} onClick={() => set({ modal: 'rules' })}>
            Rules
          </Button>
          <Button icon={<IconSettings />} onClick={() => set({ modal: 'settings' })}>
            Settings
          </Button>
        </div>
        {confirm ? (
          <div className="row gap-s">
            <Button variant="danger" onClick={leaveMatch}>
              {matchKind === 'online' ? 'Leave — a bot plays on, entry fee is not refunded' : 'Quit to menu'}
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(false)}>
              Cancel
            </Button>
          </div>
        ) : (
          <Button variant="ghost" icon={<IconLogout size={18} />} onClick={() => setConfirm(true)}>
            Leave match
          </Button>
        )}
      </motion.div>
    </motion.div>
  );
}

function Victory({ view }: { view: GameState }) {
  const matchKind = useGame((s) => s.matchKind);
  const localColors = useGame((s) => s.localColors);
  const room = useGame((s) => s.room);
  const ranked = [...view.players].sort((a, b) => (a.rank ?? 9) - (b.rank ?? 9));
  const winner = ranked[0];
  const youWon = matchKind !== 'local' && localColors.includes(winner.color);
  const youLost = matchKind !== 'local' && localColors.length > 0 && !youWon;
  const isHost = room ? !!room.seats[room.you]?.isHost : false;
  const settlement = useGame((s) => s.settlement);
  const fee = room?.entryFee ?? 0;
  return (
    <motion.div className="victory" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 1.1 }}>
      <motion.div
        className="glass victory-card"
        style={{ ['--pc' as string]: PLAYER_HEX[winner.color] }}
        initial={{ scale: 0.8, y: 40 }}
        animate={{ scale: 1, y: 0 }}
        transition={{ delay: 1.1, type: 'spring', stiffness: 200, damping: 18 }}
      >
        <IconCrown size={44} className="victory-crown" />
        <h2>{youWon ? 'Victory!' : youLost ? 'Defeat' : `${winner.name} wins!`}</h2>
        <p className="hint">{youWon ? 'All four tokens made it home first.' : `${winner.name} brought every token home first.`}</p>
        {matchKind === 'online' && fee > 0 && (
          <div className={`payout ${youWon ? 'won' : ''}`}>
            {settlement ? (
              youWon ? (
                <>
                  <b>+{settlement.payout.toLocaleString()} coins</b>
                  <span>
                    Entry {fee.toLocaleString()} · net +{(settlement.payout - fee).toLocaleString()}
                  </span>
                </>
              ) : (
                <>
                  <b>−{fee.toLocaleString()} coins</b>
                  <span>
                    {settlement.payout > 0 ? `${settlement.winnerName} received ${settlement.payout.toLocaleString()}` : 'No payout (a bot won)'}
                  </span>
                </>
              )
            ) : (
              <span>Settling coins…</span>
            )}
          </div>
        )}
        <ol className="ranking">
          {ranked.map((p) => (
            <li key={p.color} style={{ ['--pc' as string]: PLAYER_HEX[p.color] }}>
              <span className="rank-no">{p.rank ?? '-'}</span>
              <Avatar name={p.name} color={p.color} size={30} />
              <span>{p.name}</span>
              <span className="muted">{p.tokens.filter((t) => t === FINISH).length}/4 home</span>
            </li>
          ))}
        </ol>
        <div className="row gap-s wrap">
          {matchKind === 'online' ? (
            isHost ? (
              <Button variant="primary" icon={<IconRefresh />} onClick={() => net.rematch()}>
                {fee > 0 ? `Rematch (${fee.toLocaleString()} coins each)` : 'Rematch'}
              </Button>
            ) : (
              <span className="hint">Waiting for the host to start a rematch…</span>
            )
          ) : (
            <Button variant="primary" icon={<IconRefresh />} onClick={restartLocal}>
              Play again
            </Button>
          )}
          <Button icon={<IconLogout size={18} />} onClick={leaveMatch}>
            {matchKind === 'online' ? 'Dashboard' : 'Back'}
          </Button>
        </div>
      </motion.div>
    </motion.div>
  );
}

export function Hud() {
  // Subscribe only to what the HUD shows, so chat traffic doesn't re-render it.
  const store = useGame(
    useShallow((s) => ({
      view: s.view,
      paused: s.paused,
      matchKind: s.matchKind,
      connection: s.connection,
      lastRoll: s.lastRoll,
      busy: s.busy,
      rollPending: s.rollPending,
      localColors: s.localColors,
    })),
  );
  const { view, paused, matchKind, connection, lastRoll } = store;
  const settings = useSettings();
  const showRoll = useGame((s) => !!s.view && s.view.phase === 'roll' && isLocalTurn(s));
  const rollEnabled = useGame(() => canRoll());

  if (!view) return null;
  const cur = view.players[view.current];
  const status = statusText(store);
  const nextCam = () => settings.set({ camera: CAMERAS[(CAMERAS.indexOf(settings.camera) + 1) % CAMERAS.length] });

  return (
    <div className="hud">
      <div className="hud-top">
        <IconButton label="Pause menu" onClick={() => setPaused(true)}>
          <IconPause />
        </IconButton>
        <div className="row gap-s">
          <SocialButtons />
          <IconButton label={settings.sound ? 'Mute sound' : 'Enable sound'} active={settings.sound} onClick={() => settings.set({ sound: !settings.sound })}>
            {settings.sound ? <IconSound /> : <IconMute />}
          </IconButton>
          <IconButton label={settings.music ? 'Stop music' : 'Play music'} active={settings.music} onClick={() => settings.set({ music: !settings.music })}>
            <IconMusic />
          </IconButton>
          <IconButton label={`Camera: ${settings.camera}`} onClick={nextCam}>
            <IconCamera />
          </IconButton>
        </div>
      </div>

      {matchKind === 'online' && connection !== 'online' && <div className="banner">Connection lost — reconnecting…</div>}

      <PlayerPanels view={view} />
      <EmotePanel />
      <ChatPanel />

      <div className="hud-bottom">
        {view.phase !== 'over' && localColorsPresent(store.localColors) && <ReactionBar />}
        <div className="hud-actions">
        <AnimatePresence mode="wait">
          <motion.div
            key={status}
            className="status glass"
            style={{ ['--pc' as string]: PLAYER_HEX[cur.color] }}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.18 }}
          >
            <i className="status-dot" />
            {status}
            {lastRoll && view.phase === 'move' && <b className="status-roll">{lastRoll.value}</b>}
          </motion.div>
        </AnimatePresence>
        <AnimatePresence>
          {showRoll && (
            <motion.div initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.6, opacity: 0 }}>
              <button
                className="roll-btn"
                style={{ ['--pc' as string]: PLAYER_HEX[cur.color] }}
                disabled={!rollEnabled}
                onClick={requestRoll}
                aria-label="Roll dice"
              >
                <IconDice size={26} />
                <span>ROLL</span>
              </button>
            </motion.div>
          )}
        </AnimatePresence>
        </div>
      </div>

      <AnimatePresence>{paused && <PauseMenu />}</AnimatePresence>
      {view.phase === 'over' && <Victory view={view} />}
    </div>
  );
}
