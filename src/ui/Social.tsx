import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { ChatMessage } from '../../shared/protocol';
import { CHAT_EMOJI, CHAT_MAX_CHARS, charCount, EMOTE_CATEGORIES, EMOTES, INSTANT_REACTIONS, QUICK_MESSAGES, type EmoteId } from '../../shared/social';
import { sfx } from '../audio/audio';
import { sendEmote } from '../game/emotes';
import { net } from '../game/net';
import { useGame } from '../store/game';
import { PLAYER_HEX } from '../three/layout';
import { Avatar, IconButton } from './common';
import { IconChat, IconClose, IconEyeOff, IconSend, IconSmile, IconUserMute } from './icons';

/** Emote picker, instant reactions and match chat for the in-game HUD. */

const reduceMotion = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

function useEmoteCooldown(): { blocked: boolean; ms: number } {
  const until = useGame((s) => s.emoteBlockedUntil);
  const ms = Math.max(0, until - Date.now());
  return { blocked: ms > 0, ms };
}

const popIn = reduceMotion
  ? { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } }
  : {
      initial: { opacity: 0, y: -12, scale: 0.96 },
      animate: { opacity: 1, y: 0, scale: 1 },
      exit: { opacity: 0, y: -8, scale: 0.97 },
      transition: { type: 'spring' as const, stiffness: 360, damping: 28 },
    };

// ---------------------------------------------------------------- buttons in the top bar

export function SocialButtons() {
  const set = useGame((s) => s.set);
  const emoteOpen = useGame((s) => s.emoteOpen);
  const chatOpen = useGame((s) => s.chatOpen);
  const unread = useGame((s) => s.unread);
  const online = useGame((s) => s.matchKind === 'online');
  return (
    <>
      <IconButton
        label={emoteOpen ? 'Close emotes' : 'Open emotes'}
        className={emoteOpen ? 'is-active' : ''}
        onClick={() => set({ emoteOpen: !emoteOpen, chatOpen: false })}
      >
        <IconSmile />
      </IconButton>
      {online && (
        <IconButton
          label={unread ? `Open chat (${unread} unread)` : chatOpen ? 'Close chat' : 'Open chat'}
          className={`chat-btn ${chatOpen ? 'is-active' : ''}`}
          onClick={() => set({ chatOpen: !chatOpen, emoteOpen: false, unread: 0 })}
        >
          <IconChat />
          <AnimatePresence>
            {unread > 0 && !chatOpen && (
              <motion.span key={unread} className="badge" initial={{ scale: 0.4 }} animate={{ scale: 1 }} exit={{ scale: 0 }}>
                {unread > 9 ? '9+' : unread}
              </motion.span>
            )}
          </AnimatePresence>
        </IconButton>
      )}
    </>
  );
}

// ---------------------------------------------------------------- emote picker

function gridNav(e: KeyboardEvent<HTMLDivElement>, cols: number) {
  const items = Array.from(e.currentTarget.querySelectorAll<HTMLButtonElement>('button'));
  const i = items.indexOf(document.activeElement as HTMLButtonElement);
  if (i < 0) return;
  const d = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: cols, ArrowUp: -cols }[e.key];
  if (!d) return;
  e.preventDefault();
  items[Math.min(items.length - 1, Math.max(0, i + d))]?.focus();
}

export function EmotePanel() {
  const open = useGame((s) => s.emoteOpen);
  const set = useGame((s) => s.set);
  const [cat, setCat] = useState(0);
  const { blocked } = useEmoteCooldown();
  const grid = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) requestAnimationFrame(() => grid.current?.querySelector<HTMLButtonElement>('button')?.focus());
  }, [open, cat]);

  const pick = (id: EmoteId) => {
    if (blocked) return;
    void sendEmote(id);
    set({ emoteOpen: false });
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.div className="social-pop emote-pop glass" role="dialog" aria-label="Emotes" {...popIn}>
          <div className="emote-tabs" role="tablist">
            {EMOTE_CATEGORIES.map((c, i) => (
              <button
                key={c.id}
                role="tab"
                aria-selected={i === cat}
                aria-label={c.label}
                title={c.label}
                className={i === cat ? 'active' : ''}
                onClick={() => setCat(i)}
              >
                <span className="emoji">{c.icon}</span>
              </button>
            ))}
            <button className="emote-close" aria-label="Close emotes" onClick={() => set({ emoteOpen: false })}>
              <IconClose size={16} />
            </button>
          </div>
          <div className="emote-cat">{EMOTE_CATEGORIES[cat].label}</div>
          <div className="emote-grid" ref={grid} onKeyDown={(e) => gridNav(e, 4)} role="tabpanel">
            {EMOTE_CATEGORIES[cat].emotes.map((id) => (
              <motion.button
                key={`${cat}-${id}`}
                className="emote-cell"
                aria-label={`Send ${EMOTES[id].label}`}
                title={EMOTES[id].label}
                disabled={blocked}
                whileHover={reduceMotion ? undefined : { scale: 1.18, y: -2 }}
                whileTap={reduceMotion ? undefined : { scale: 0.9 }}
                onClick={() => pick(id)}
              >
                <span className="emoji">{EMOTES[id].emoji}</span>
              </motion.button>
            ))}
          </div>
          {blocked && <div className="cooldown-note">Cooling down…</div>}
        </motion.div>
      )}
    </AnimatePresence>
  );
}

// ---------------------------------------------------------------- instant reactions

export function ReactionBar() {
  const { blocked, ms } = useEmoteCooldown();
  return (
    <div className={`reaction-bar glass ${blocked ? 'cooling' : ''}`} role="toolbar" aria-label="Quick reactions">
      {INSTANT_REACTIONS.map((id) => (
        <button
          key={id}
          className="reaction-btn"
          aria-label={`React with ${EMOTES[id].label}`}
          title={EMOTES[id].label}
          disabled={blocked}
          onClick={() => void sendEmote(id)}
        >
          <span className="emoji">{EMOTES[id].emoji}</span>
        </button>
      ))}
      {blocked && <i className="cooldown-bar" key={Math.round(ms / 100)} style={{ animationDuration: `${ms}ms` }} />}
    </div>
  );
}

// ---------------------------------------------------------------- chat

const time = (at: number) => new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

function Message({ m, mine, selected, onSelect }: { m: ChatMessage; mine: boolean; selected: boolean; onSelect: () => void }) {
  const hidden = useGame((s) => s.hidden.includes(m.id));
  const set = useGame((s) => s.set);
  const toggleHide = () => {
    const h = useGame.getState().hidden;
    set({ hidden: hidden ? h.filter((x) => x !== m.id) : [...h, m.id] });
  };
  const mute = () => set({ muted: [...useGame.getState().muted, m.seatId] });
  return (
    <motion.li
      className={`msg ${mine ? 'mine' : ''} ${m.quick ? 'quick' : ''}`}
      style={{ ['--pc' as string]: PLAYER_HEX[m.color] }}
      initial={reduceMotion ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2 }}
    >
      <Avatar name={m.name} color={m.color} size={28} />
      <div className="msg-body">
        <div className="msg-meta">
          <b>{mine ? 'You' : m.name}</b>
          <time dateTime={new Date(m.at).toISOString()}>{time(m.at)}</time>
          {!mine && (
            <button className="msg-more" aria-label={`Message options for ${m.name}`} aria-expanded={selected} onClick={onSelect}>
              ⋯
            </button>
          )}
        </div>
        {/* Rendered as a React text node: user text can never become HTML. */}
        <p className={`msg-text ${hidden ? 'is-hidden' : ''}`} dir="auto">
          {hidden ? 'Message hidden' : m.text}
        </p>
        {selected && !mine && (
          <div className="msg-actions">
            <button onClick={toggleHide}>
              <IconEyeOff size={14} /> {hidden ? 'Show' : 'Hide'}
            </button>
            <button onClick={mute}>
              <IconUserMute size={14} /> Mute {m.name}
            </button>
          </div>
        )}
      </div>
    </motion.li>
  );
}

export function ChatPanel() {
  const open = useGame((s) => s.chatOpen);
  const chat = useGame((s) => s.chat);
  const muted = useGame((s) => s.muted);
  const room = useGame((s) => s.room);
  const set = useGame((s) => s.set);
  const me = room?.seats[room.you]?.id;
  const [text, setText] = useState('');
  const [showQuick, setShowQuick] = useState(false);
  const [showEmoji, setShowEmoji] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const [sending, setSending] = useState(false);
  const list = useRef<HTMLUListElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const stick = useRef(true);
  const visible = chat.filter((m) => !muted.includes(m.seatId));

  useEffect(() => {
    if (open) {
      set({ unread: 0 });
      stick.current = true;
      requestAnimationFrame(() => input.current?.focus({ preventScroll: true }));
    }
  }, [open, set]);

  // Follow new messages only when the reader is already at the bottom (or wrote it).
  useLayoutEffect(() => {
    const el = list.current;
    if (!el) return;
    const last = visible.at(-1);
    if (stick.current || last?.seatId === me) el.scrollTop = el.scrollHeight;
  }, [visible.length, open, me]);

  const send = async (raw: string, quick = false) => {
    const msg = raw.trim();
    if (!msg || sending) return;
    if (charCount(msg) > CHAT_MAX_CHARS) return;
    setSending(true);
    const ok = await net.chat(msg, quick);
    setSending(false);
    if (ok) {
      if (!quick) setText('');
      stick.current = true;
      sfx('click');
    }
  };

  const insertEmoji = (e: string) => {
    const el = input.current;
    const start = el?.selectionStart ?? text.length;
    const end = el?.selectionEnd ?? text.length;
    const next = text.slice(0, start) + e + text.slice(end);
    if (charCount(next) > CHAT_MAX_CHARS) return;
    setText(next);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + e.length, start + e.length);
    });
  };

  const count = charCount(text);
  return (
    <AnimatePresence>
      {open && (
        <motion.section className="social-pop chat-pop glass" role="dialog" aria-label="Match chat" {...popIn}>
          <header className="chat-head">
            <IconChat size={18} />
            <h3>Match chat</h3>
            {muted.length > 0 && (
              <button className="chip" onClick={() => set({ muted: [] })}>
                Unmute all ({muted.length})
              </button>
            )}
            <IconButton label="Close chat" onClick={() => set({ chatOpen: false })}>
              <IconClose size={18} />
            </IconButton>
          </header>

          <ul
            className="chat-list"
            ref={list}
            aria-live="polite"
            onScroll={(e) => {
              const el = e.currentTarget;
              stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
            }}
          >
            {visible.length === 0 && <li className="chat-empty">No messages yet — say hi! 👋</li>}
            {visible.map((m) => (
              <Message key={m.id} m={m} mine={m.seatId === me} selected={selected === m.id} onSelect={() => setSelected(selected === m.id ? null : m.id)} />
            ))}
          </ul>

          <AnimatePresence initial={false}>
            {showQuick && (
              <motion.div className="quick-row" initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}>
                {QUICK_MESSAGES.map((q) => (
                  <motion.button key={q} className="chip" whileTap={reduceMotion ? undefined : { scale: 0.92 }} disabled={sending} onClick={() => void send(q, true)}>
                    {q}
                  </motion.button>
                ))}
              </motion.div>
            )}
            {showEmoji && (
              <motion.div
                className="emoji-pick"
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                onKeyDown={(e) => gridNav(e, 8)}
              >
                {CHAT_EMOJI.map((e) => (
                  <motion.button key={e} aria-label={`Insert ${e}`} whileTap={reduceMotion ? undefined : { scale: 1.35 }} onClick={() => insertEmoji(e)}>
                    <span className="emoji">{e}</span>
                  </motion.button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>

          <form
            className="chat-input"
            onSubmit={(e) => {
              e.preventDefault();
              void send(text);
            }}
          >
            <button type="button" className={`chip ${showQuick ? 'on' : ''}`} aria-expanded={showQuick} onClick={() => (setShowQuick(!showQuick), setShowEmoji(false))}>
              Quick
            </button>
            <button
              type="button"
              className={`icon-btn small ${showEmoji ? 'is-active' : ''}`}
              aria-label="Emoji picker"
              aria-expanded={showEmoji}
              onClick={() => (setShowEmoji(!showEmoji), setShowQuick(false))}
            >
              <IconSmile size={18} />
            </button>
            <input
              ref={input}
              className="input"
              value={text}
              placeholder="Type a message…"
              aria-label="Chat message"
              maxLength={CHAT_MAX_CHARS * 4}
              enterKeyHint="send"
              autoComplete="off"
              dir="auto"
              onChange={(e) => {
                const v = e.target.value;
                if (charCount(v) <= CHAT_MAX_CHARS) setText(v);
              }}
            />
            <span className={`count ${count > CHAT_MAX_CHARS - 20 ? 'warn' : ''}`}>{CHAT_MAX_CHARS - count}</span>
            <button type="submit" className="icon-btn small send" aria-label="Send message" disabled={!text.trim() || sending}>
              <IconSend size={18} />
            </button>
          </form>
        </motion.section>
      )}
    </AnimatePresence>
  );
}
