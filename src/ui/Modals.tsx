import { AnimatePresence } from 'framer-motion';
import { useGame } from '../store/game';
import { useSettings, type CameraPreset, type Quality } from '../store/settings';
import { Modal, Segmented, Slider, Toggle } from './common';

function Rules() {
  return (
    <div className="prose">
      <h3>Goal</h3>
      <p>Be the first to bring all four of your tokens around the board and into the centre home.</p>
      <h3>Turns</h3>
      <ul>
        <li>Roll the die, then move one of your tokens forward by that many squares.</li>
        <li>A token leaves the yard only on a <b>6</b> and enters on your coloured start square.</li>
        <li>Rolling a 6 gives another roll. Three 6s in a row forfeit the turn.</li>
        <li>If no token can move, the turn passes automatically.</li>
      </ul>
      <h3>Captures &amp; safety</h3>
      <ul>
        <li>Landing on an opponent sends their token back to its yard and earns an extra roll.</li>
        <li>Start squares and gold star squares are safe — no captures there.</li>
        <li>Several tokens may share a square.</li>
      </ul>
      <h3>Going home</h3>
      <ul>
        <li>After almost a full lap a token turns into its coloured home column (the arrow marks the turn).</li>
        <li>You need the exact number to reach the centre. Getting a token home earns an extra roll.</li>
      </ul>
      <p className="muted">Variants (entering on 1, safe squares, bonus rolls, playing for every place) can be changed when setting up a local match.</p>
      <h3>Controls</h3>
      <ul>
        <li>Tap the die or the ROLL button (Space / Enter) to roll.</li>
        <li>Tap a glowing token (keys 1–4) to move it.</li>
        <li>Drag to tilt the camera a little, pinch or scroll to zoom.</li>
      </ul>
    </div>
  );
}

function SettingsPanel() {
  const s = useSettings();
  return (
    <div className="stack">
      <Toggle label="Sound effects" checked={s.sound} onChange={(v) => s.set({ sound: v })} />
      <Slider label="Effects volume" value={s.soundVolume} onChange={(v) => s.set({ soundVolume: v })} />
      <Toggle label="Background music" checked={s.music} onChange={(v) => s.set({ music: v })} />
      <Slider label="Music volume" value={s.musicVolume} onChange={(v) => s.set({ musicVolume: v })} />
      <div className="field">
        <span className="label">Graphics quality</span>
        <Segmented<Quality>
          value={s.quality}
          options={[
            { value: 'auto', label: 'Auto' },
            { value: 'low', label: 'Low' },
            { value: 'medium', label: 'Medium' },
            { value: 'high', label: 'High' },
          ]}
          onChange={(v) => s.set({ quality: v })}
        />
      </div>
      <div className="field">
        <span className="label">Camera</span>
        <Segmented<CameraPreset>
          value={s.camera}
          options={[
            { value: 'tabletop', label: 'Tabletop' },
            { value: 'close', label: 'Close' },
            { value: 'top', label: 'Top-down' },
          ]}
          onChange={(v) => s.set({ camera: v })}
        />
      </div>
      <Toggle label="Auto-move when only one move is possible" checked={s.autoMove} onChange={(v) => s.set({ autoMove: v })} />
      <Toggle label="Pass & play: turn camera to the active player" checked={s.followTurns} onChange={(v) => s.set({ followTurns: v })} />
    </div>
  );
}

export function Modals() {
  const modal = useGame((s) => s.modal);
  const set = useGame((s) => s.set);
  const close = () => set({ modal: null });
  return (
    <AnimatePresence>
      {modal === 'rules' && (
        <Modal key="rules" title="How to play" onClose={close} wide>
          <Rules />
        </Modal>
      )}
      {modal === 'settings' && (
        <Modal key="settings" title="Settings" onClose={close}>
          <SettingsPanel />
        </Modal>
      )}
    </AnimatePresence>
  );
}
