import { useState } from 'react';
import { colorsForCount, DEFAULT_RULES } from '../../shared/engine';
import type { AiLevel, PlayerKind, RuleConfig, SeatSetup } from '../../shared/types';
import { startLocalMatch } from '../game/controller';
import { useAuth } from '../store/auth';
import { PLAYER_HEX } from '../three/layout';
import { Button, Panel, Segmented, Toggle } from './common';
import { IconBack, IconBot, IconPlay, IconUser } from './icons';

const AI_NAMES = ['Nova', 'Atlas', 'Vega', 'Orion'];

interface SeatDraft {
  name: string;
  kind: PlayerKind;
  aiLevel: AiLevel;
}

export function Setup({ preset, onBack }: { preset: 'ai' | 'local'; onBack: () => void }) {
  const myName = useAuth((s) => s.user?.username ?? 'Player');
  const [count, setCount] = useState(preset === 'ai' ? 2 : 4);
  const [seats, setSeats] = useState<SeatDraft[]>(() =>
    [0, 1, 2, 3].map((i) =>
      preset === 'ai'
        ? { name: i === 0 ? myName : AI_NAMES[i - 1], kind: i === 0 ? 'human' : 'ai', aiLevel: 'medium' }
        : { name: i === 0 ? myName : `Player ${i + 1}`, kind: 'human', aiLevel: 'medium' },
    ),
  );
  const [rules, setRules] = useState<RuleConfig>(DEFAULT_RULES);
  const colors = colorsForCount(count);

  const patch = (i: number, p: Partial<SeatDraft>) => setSeats((s) => s.map((d, j) => (j === i ? { ...d, ...p } : d)));
  const humans = seats.slice(0, count).filter((s) => s.kind === 'human').length;

  const start = () => {
    const list: SeatSetup[] = colors.map((color, i) => ({
      color,
      name: seats[i].name.trim().slice(0, 16) || (seats[i].kind === 'ai' ? AI_NAMES[i % 4] : `Player ${i + 1}`),
      kind: seats[i].kind,
      aiLevel: seats[i].aiLevel,
    }));
    startLocalMatch(humans <= 1 ? 'ai' : 'local', list, rules);
  };

  return (
    <div className="screen center-screen">
      <Panel className="setup">
        <header className="panel-head">
          <Button variant="ghost" size="sm" icon={<IconBack />} onClick={onBack} aria-label="Back" />
          <h2>{preset === 'ai' ? 'Play vs AI' : 'Local Multiplayer'}</h2>
        </header>

        <div className="field">
          <span className="label">Players</span>
          <Segmented
            value={count}
            options={[
              { value: 2, label: '2 Players' },
              { value: 3, label: '3 Players' },
              { value: 4, label: '4 Players' },
            ]}
            onChange={setCount}
          />
        </div>

        <div className="seat-list">
          {colors.map((color, i) => (
            <div className="seat-row" key={color} style={{ ['--seat' as string]: PLAYER_HEX[color] }}>
              <span className="seat-dot" />
              <input
                className="input"
                value={seats[i].name}
                maxLength={16}
                aria-label={`${color} player name`}
                onChange={(e) => patch(i, { name: e.target.value })}
              />
              <Segmented
                value={seats[i].kind}
                options={[
                  { value: 'human', label: 'Human' },
                  { value: 'ai', label: 'AI' },
                ]}
                onChange={(kind) => patch(i, { kind, name: kind === 'ai' && seats[i].kind !== 'ai' ? AI_NAMES[i % 4] : seats[i].name })}
              />
              {seats[i].kind === 'ai' ? (
                <select className="input select" value={seats[i].aiLevel} onChange={(e) => patch(i, { aiLevel: e.target.value as AiLevel })} aria-label="AI difficulty">
                  <option value="easy">Easy</option>
                  <option value="medium">Medium</option>
                  <option value="hard">Hard</option>
                </select>
              ) : (
                <span className="seat-kind">
                  <IconUser size={16} />
                </span>
              )}
              {seats[i].kind === 'ai' && (
                <span className="seat-kind">
                  <IconBot size={16} />
                </span>
              )}
            </div>
          ))}
        </div>

        <details className="rules-variants">
          <summary>Rule variants</summary>
          <Toggle label="Leave yard on 1 or 6 (instead of only 6)" checked={rules.enterValues.includes(1)} onChange={(v) => setRules({ ...rules, enterValues: v ? [1, 6] : [6] })} />
          <Toggle label="Extra roll on a six" checked={rules.extraTurnOnSix} onChange={(v) => setRules({ ...rules, extraTurnOnSix: v })} />
          <Toggle label="Three sixes forfeit the turn" checked={rules.maxConsecutiveSixes === 3} onChange={(v) => setRules({ ...rules, maxConsecutiveSixes: v ? 3 : 0 })} />
          <Toggle label="Extra roll after a capture" checked={rules.extraTurnOnCapture} onChange={(v) => setRules({ ...rules, extraTurnOnCapture: v })} />
          <Toggle label="Extra roll when a token gets home" checked={rules.extraTurnOnHome} onChange={(v) => setRules({ ...rules, extraTurnOnHome: v })} />
          <Toggle label="Safe squares (stars and starts)" checked={rules.safeCells} onChange={(v) => setRules({ ...rules, safeCells: v })} />
          <Toggle label="Play on for every place" checked={rules.playUntil === 'all'} onChange={(v) => setRules({ ...rules, playUntil: v ? 'all' : 'first' })} />
        </details>

        <Button variant="primary" size="lg" icon={<IconPlay />} onClick={start}>
          Start Match
        </Button>
        {humans === 0 && <p className="hint">All seats are AI — sit back and watch the bots play.</p>}
      </Panel>
    </div>
  );
}
