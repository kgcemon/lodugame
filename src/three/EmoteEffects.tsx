import { Billboard } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef, type ReactNode } from 'react';
import * as THREE from 'three';
import { useReactions, type Reaction } from '../game/emotes';
import { PLAYER_HEX, yardCenter } from './layout';

/**
 * 3D emote reactions rendered inside the game world, above the sender's own
 * yard (their corner of the board), so they sit next to that player whatever
 * the camera angle or board orientation, and never over the track or the dice
 * path in the middle. Each reaction is a short-lived group: an emoji billboard
 * plus real geometry (hearts, flames, droplets, confetti, shockwaves) and
 * pooled instanced particles. Geometry is shared; per-reaction materials are
 * disposed automatically when the reaction unmounts.
 */

const REDUCED = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
const HEIGHT = 2.2;

// ---------------------------------------------------------------- shared resources

const textures = new Map<string, THREE.CanvasTexture>();
function emojiTexture(emoji: string): THREE.CanvasTexture {
  let t = textures.get(emoji);
  if (!t) {
    const c = document.createElement('canvas');
    c.width = c.height = 256;
    const g = c.getContext('2d')!;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.font = '200px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji","Twemoji Mozilla",sans-serif';
    g.shadowColor = 'rgba(0,0,0,0.35)';
    g.shadowBlur = 14;
    g.shadowOffsetY = 6;
    g.fillText(emoji, 128, 138);
    t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    textures.set(emoji, t);
  }
  return t;
}

function heartShape(s = 1): THREE.Shape {
  const h = new THREE.Shape();
  h.moveTo(0, -0.35 * s);
  h.bezierCurveTo(-0.05 * s, -0.25 * s, -0.5 * s, 0.0, -0.5 * s, 0.22 * s);
  h.bezierCurveTo(-0.5 * s, 0.48 * s, -0.12 * s, 0.55 * s, 0, 0.33 * s);
  h.bezierCurveTo(0.12 * s, 0.55 * s, 0.5 * s, 0.48 * s, 0.5 * s, 0.22 * s);
  h.bezierCurveTo(0.5 * s, 0.0, 0.05 * s, -0.25 * s, 0, -0.35 * s);
  return h;
}

const GEO = {
  heart: (() => {
    const g = new THREE.ExtrudeGeometry(heartShape(1), { depth: 0.22, bevelEnabled: true, bevelThickness: 0.08, bevelSize: 0.07, bevelSegments: 4, curveSegments: 18 });
    g.center();
    return g;
  })(),
  miniHeart: (() => {
    const g = new THREE.ExtrudeGeometry(heartShape(0.28), { depth: 0.06, bevelEnabled: true, bevelThickness: 0.02, bevelSize: 0.02, bevelSegments: 1, curveSegments: 8 });
    g.center();
    return g;
  })(),
  spark: new THREE.IcosahedronGeometry(0.065, 0),
  drop: (() => {
    const g = new THREE.SphereGeometry(0.07, 10, 8);
    g.scale(0.75, 1.35, 0.75);
    return g;
  })(),
  confetti: new THREE.BoxGeometry(0.15, 0.08, 0.012),
  flame: new THREE.ConeGeometry(0.42, 1.25, 24, 1, true),
  ring: new THREE.RingGeometry(0.55, 0.68, 48),
};

const clamp01 = (x: number) => Math.min(1, Math.max(0, x));
const outBack = (t: number) => {
  const c1 = 1.70158;
  return 1 + (c1 + 1) * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
};
/** Fade multiplier: in over 0.15 s, out over the last 0.45 s. */
const fade = (t: number, life: number) => clamp01(t / 0.15) * (1 - clamp01((t - (life - 0.45)) / 0.45));

// ---------------------------------------------------------------- building blocks

/** Elapsed seconds since mount, read inside useFrame without re-rendering. */
function useAge() {
  const start = useRef<number | null>(null);
  return (clock: THREE.Clock) => {
    if (start.current === null) start.current = clock.elapsedTime;
    return clock.elapsedTime - start.current;
  };
}

interface SpriteDriver {
  (sprite: THREE.Sprite, mat: THREE.SpriteMaterial, t: number, life: number): void;
}

function EmojiSprite({ emoji, size = 1.5, life, drive }: { emoji: string; size?: number; life: number; drive: SpriteDriver }) {
  const sprite = useRef<THREE.Sprite>(null);
  const mat = useRef<THREE.SpriteMaterial>(null);
  const age = useAge();
  const map = useMemo(() => emojiTexture(emoji), [emoji]);
  useFrame(({ clock }) => {
    if (!sprite.current || !mat.current) return;
    const t = age(clock);
    sprite.current.scale.setScalar(size);
    sprite.current.position.set(0, 0, 0);
    mat.current.rotation = 0;
    drive(sprite.current, mat.current, t, life);
    mat.current.opacity *= fade(t, life);
  });
  return (
    <sprite ref={sprite} renderOrder={10}>
      <spriteMaterial ref={mat} map={map} transparent depthWrite={false} toneMapped={false} />
    </sprite>
  );
}

interface ParticleSpec {
  p: [number, number, number];
  v: [number, number, number];
  delay?: number;
  size?: number;
}

interface ParticlesProps {
  count: number;
  geometry: THREE.BufferGeometry;
  colors: string[];
  spawn: (i: number) => ParticleSpec;
  gravity?: number;
  drag?: number;
  life: number;
  glow?: boolean;
  spin?: number;
}

/** A fixed burst of instanced particles: one draw call per burst. */
function Particles({ count, geometry, colors, spawn, gravity = -4, drag = 0.6, life, glow = false, spin = 0 }: ParticlesProps) {
  const n = REDUCED ? Math.ceil(count / 2) : count;
  const mesh = useRef<THREE.InstancedMesh>(null);
  const age = useAge();
  const sim = useMemo(() => {
    const specs = Array.from({ length: n }, (_, i) => spawn(i));
    return {
      specs,
      pos: specs.map((s) => new THREE.Vector3(...s.p)),
      vel: specs.map((s) => new THREE.Vector3(...s.v)),
      rot: specs.map(() => new THREE.Euler(Math.random() * 6, Math.random() * 6, Math.random() * 6)),
      last: 0,
    };
    // spawn is stable for the life of a burst
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [n]);
  const tmp = useMemo(() => new THREE.Object3D(), []);

  useLayoutEffect(() => {
    const m = mesh.current!;
    const c = new THREE.Color();
    for (let i = 0; i < n; i++) m.setColorAt(i, c.set(colors[i % colors.length]));
    if (m.instanceColor) m.instanceColor.needsUpdate = true;
  }, [n, colors]);

  useFrame(({ clock }) => {
    const m = mesh.current;
    if (!m) return;
    const t = age(clock);
    const dt = Math.min(0.05, Math.max(0, t - sim.last));
    sim.last = t;
    for (let i = 0; i < n; i++) {
      const s = sim.specs[i];
      const a = t - (s.delay ?? 0);
      if (a < 0 || a > life) {
        tmp.scale.setScalar(0);
      } else {
        const v = sim.vel[i];
        v.y += gravity * dt;
        v.multiplyScalar(1 - drag * dt);
        sim.pos[i].addScaledVector(v, dt);
        tmp.position.copy(sim.pos[i]);
        sim.rot[i].x += spin * dt;
        sim.rot[i].y += spin * 0.7 * dt;
        tmp.rotation.copy(sim.rot[i]);
        const k = clamp01(a / 0.12) * (1 - clamp01((a - life * 0.6) / (life * 0.4)));
        tmp.scale.setScalar((s.size ?? 1) * k);
      }
      tmp.updateMatrix();
      m.setMatrixAt(i, tmp.matrix);
    }
    m.instanceMatrix.needsUpdate = true;
  });

  return (
    <instancedMesh ref={mesh} args={[geometry, undefined, n]} frustumCulled={false}>
      {glow ? (
        <meshBasicMaterial toneMapped={false} transparent opacity={0.95} />
      ) : (
        <meshStandardMaterial roughness={0.35} metalness={0.15} />
      )}
    </instancedMesh>
  );
}

/** Expanding ring that always faces the camera. */
function Shockwave({ color, delay = 0, life = 0.7 }: { color: string; delay?: number; life?: number }) {
  const mesh = useRef<THREE.Mesh>(null);
  const mat = useRef<THREE.MeshBasicMaterial>(null);
  const age = useAge();
  useFrame(({ clock }) => {
    const a = age(clock) - delay;
    if (!mesh.current || !mat.current) return;
    mesh.current.visible = a >= 0 && a <= life;
    const k = clamp01(a / life);
    mesh.current.scale.setScalar(0.4 + k * 2.4);
    mat.current.opacity = (1 - k) * 0.85;
  });
  return (
    <Billboard>
      <mesh ref={mesh} geometry={GEO.ring} visible={false}>
        <meshBasicMaterial ref={mat} color={color} transparent depthWrite={false} toneMapped={false} side={THREE.DoubleSide} />
      </mesh>
    </Billboard>
  );
}

const rnd = (a: number, b: number) => a + Math.random() * (b - a);
const radial = (speed: number, up: number): ParticleSpec => {
  const a = Math.random() * Math.PI * 2;
  const s = speed * rnd(0.5, 1);
  return { p: [0, 0, 0], v: [Math.cos(a) * s, up * rnd(0.6, 1.2), Math.sin(a) * s] };
};

// ---------------------------------------------------------------- effects

function Laugh({ r }: { r: Reaction }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          // Big bouncy belly laugh: hops with squash, wobbles side to side.
          const decay = Math.max(0, 1 - t / r.life);
          const hop = REDUCED ? 0 : Math.abs(Math.sin(t * 9)) * 0.35 * decay;
          s.position.y = hop + t * 0.25;
          const squash = REDUCED ? 0 : Math.sin(t * 18) * 0.08 * decay;
          s.scale.set(1.7 * outBack(clamp01(t / 0.3)) * (1 + squash), 1.7 * outBack(clamp01(t / 0.3)) * (1 - squash), 1);
          m.rotation = REDUCED ? 0 : Math.sin(t * 7) * 0.18 * decay;
          m.opacity = 1;
        }}
      />
      <Particles count={14} geometry={GEO.spark} colors={['#ffd54a', '#ffb300', '#fff3b0']} spawn={() => radial(2.2, 2.6)} life={1.1} glow gravity={-5} />
      <Particles
        count={8}
        geometry={GEO.drop}
        colors={['#7cc8ff', '#4aa8ff']}
        spawn={(i) => ({ p: [i % 2 ? 0.45 : -0.45, 0.15, 0.1], v: [(i % 2 ? 1 : -1) * rnd(1, 2), rnd(1, 2.2), rnd(-0.3, 0.3)], delay: (i >> 1) * 0.3 })}
        life={0.9}
        gravity={-7}
      />
    </>
  );
}

function Love({ r, color }: { r: Reaction; color: string }) {
  const heart = useRef<THREE.Mesh>(null);
  const mat = useRef<THREE.MeshPhysicalMaterial>(null);
  const age = useAge();
  const pink = r.id === 'sparkle_heart';
  useFrame(({ clock }) => {
    const t = age(clock);
    if (!heart.current || !mat.current) return;
    const f = fade(t, r.life);
    const beat = 1 + (REDUCED ? 0 : Math.max(0, Math.sin(t * 9)) * 0.12);
    heart.current.scale.setScalar(1.15 * outBack(clamp01(t / 0.35)) * beat * (0.4 + 0.6 * f));
    heart.current.rotation.y = REDUCED ? 0 : Math.sin(t * 2.2) * 0.9;
    heart.current.position.y = t * 0.3;
    mat.current.opacity = f;
    mat.current.emissiveIntensity = 0.45 + Math.sin(t * 9) * 0.2;
  });
  return (
    <>
      <mesh ref={heart} geometry={GEO.heart} rotation={[0, 0, Math.PI]} castShadow>
        <meshPhysicalMaterial
          ref={mat}
          color={pink ? '#ff5fb2' : '#e8243c'}
          emissive={pink ? '#ff3d9a' : '#ff1f3d'}
          roughness={0.18}
          clearcoat={1}
          clearcoatRoughness={0.05}
          transparent
        />
      </mesh>
      <Particles
        count={12}
        geometry={GEO.miniHeart}
        colors={pink ? ['#ff7cc6', '#ffc0e3'] : ['#ff4d6d', '#ff8fa3', color]}
        spawn={(i) => ({ p: [rnd(-0.7, 0.7), rnd(-0.4, 0), rnd(-0.4, 0.4)], v: [rnd(-0.3, 0.3), rnd(0.9, 1.6), rnd(-0.2, 0.2)], delay: i * 0.12 })}
        life={1.3}
        gravity={0.2}
        drag={0.2}
        spin={1.5}
      />
    </>
  );
}

function Fire({ r }: { r: Reaction }) {
  const flames = useRef<(THREE.Mesh | null)[]>([]);
  const mats = useRef<(THREE.MeshBasicMaterial | null)[]>([]);
  const age = useAge();
  const layers = [
    { color: '#ff3d00', s: 1.0, o: 0.55 },
    { color: '#ff9100', s: 0.72, o: 0.7 },
    { color: '#ffe066', s: 0.42, o: 0.9 },
  ];
  useFrame(({ clock }) => {
    const t = age(clock);
    const f = fade(t, r.life);
    const grow = outBack(clamp01(t / 0.35));
    flames.current.forEach((m, i) => {
      if (!m) return;
      const flick = REDUCED ? 1 : 1 + Math.sin(t * (17 + i * 5)) * 0.07 + Math.sin(t * 31 + i) * 0.05;
      m.scale.set(layers[i].s * grow * (2 - flick), layers[i].s * grow * flick * 1.1, layers[i].s * grow * (2 - flick));
      m.position.y = -0.45 + (layers[i].s * 1.25 * grow * flick) / 2;
      m.rotation.y = t * (1.5 + i);
      const mat = mats.current[i];
      if (mat) mat.opacity = layers[i].o * f;
    });
  });
  return (
    <>
      <group position={[0, -0.2, 0]}>
        {layers.map((l, i) => (
          <mesh key={i} ref={(m) => (flames.current[i] = m)} geometry={GEO.flame}>
            <meshBasicMaterial
              ref={(m) => (mats.current[i] = m)}
              color={l.color}
              transparent
              blending={THREE.AdditiveBlending}
              depthWrite={false}
              toneMapped={false}
              side={THREE.DoubleSide}
            />
          </mesh>
        ))}
      </group>
      <Particles
        count={22}
        geometry={GEO.spark}
        colors={['#ffb300', '#ff6d00', '#ffe082']}
        spawn={(i) => ({ p: [rnd(-0.35, 0.35), -0.3, rnd(-0.35, 0.35)], v: [rnd(-0.4, 0.4), rnd(1.6, 3), rnd(-0.4, 0.4)], delay: i * 0.07, size: rnd(0.5, 1.1) })}
        life={0.9}
        gravity={0.6}
        glow
      />
      {r.emoji !== '🔥' && (
        <group position={[0, 1.15, 0]}>
          <EmojiSprite emoji={r.emoji} size={0.9} life={r.life} drive={(s, m, t) => ((s.position.y = Math.sin(t * 3) * 0.05), (m.opacity = 1))} />
        </group>
      )}
    </>
  );
}

function Clap({ r, color }: { r: Reaction; color: string }) {
  const claps = [0.25, 0.6, 0.95, 1.3];
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          // A quick squeeze on every clap.
          let k = 0;
          for (const c of claps) k = Math.max(k, Math.max(0, 1 - Math.abs(t - c) / 0.09));
          const sc = 1.5 * outBack(clamp01(t / 0.25)) * (1 - (REDUCED ? 0 : k * 0.18));
          s.scale.set(sc * (1 + k * 0.1), sc, 1);
          s.position.y = t * 0.2;
          m.opacity = 1;
        }}
      />
      {claps.map((c, ci) => (
        <Particles
          key={ci}
          count={7}
          geometry={GEO.spark}
          colors={['#ffd54a', '#ffffff', color]}
          spawn={() => ({ ...radial(2, 2), delay: c })}
          life={0.6}
          glow
          gravity={-3}
        />
      ))}
    </>
  );
}

function Wow({ r, color }: { r: Reaction; color: string }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          // Snap in big, settle, then a little tremble.
          const pop = t < 0.18 ? (t / 0.18) * 2.1 : 2.1 - (2.1 - 1.6) * clamp01((t - 0.18) / 0.25);
          s.scale.setScalar(pop);
          s.position.x = REDUCED ? 0 : Math.sin(t * 60) * 0.02 * Math.max(0, 1 - t);
          m.opacity = 1;
        }}
      />
      <Shockwave color={color} />
      <Shockwave color="#ffffff" delay={0.18} />
      <Particles count={10} geometry={GEO.spark} colors={['#ffffff', color]} spawn={() => radial(2.6, 1.2)} life={0.7} glow gravity={-2} />
    </>
  );
}

function Cry({ r }: { r: Reaction }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          // Droops a little and sobs (small shudders).
          s.scale.setScalar(1.6 * outBack(clamp01(t / 0.3)));
          s.position.y = -t * 0.12 + (REDUCED ? 0 : Math.abs(Math.sin(t * 14)) * 0.04);
          m.opacity = 1;
        }}
      />
      <Particles
        count={20}
        geometry={GEO.drop}
        colors={['#5ab8ff', '#9fd8ff', '#3a8fe0']}
        spawn={(i) => ({ p: [(i % 2 ? 0.32 : -0.32) + rnd(-0.05, 0.05), 0.05, 0.25], v: [(i % 2 ? 1 : -1) * rnd(0.2, 0.7), rnd(0.2, 0.8), rnd(0, 0.3)], delay: 0.25 + i * 0.09 })}
        life={0.9}
        gravity={-6}
        drag={0.2}
      />
    </>
  );
}

function Party({ r, color }: { r: Reaction; color: string }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          s.scale.setScalar(1.6 * outBack(clamp01(t / 0.35)));
          s.position.y = REDUCED ? 0 : Math.abs(Math.sin(t * 6)) * 0.2 * Math.max(0, 1 - t / r.life);
          m.rotation = REDUCED ? 0 : Math.sin(t * 5) * 0.12;
          m.opacity = 1;
        }}
      />
      <Particles
        count={36}
        geometry={GEO.confetti}
        colors={['#e53945', '#22b46b', '#f6bf26', '#2f7cf0', '#ff7cc6', '#ffffff', color]}
        spawn={(i) => ({ ...radial(2.2, 4.2), delay: i < 18 ? 0.05 : 0.45 })}
        life={2}
        gravity={-3.2}
        drag={1.6}
        spin={9}
      />
      <Particles count={10} geometry={GEO.spark} colors={['#ffd54a', '#ffffff']} spawn={() => radial(2.5, 2.5)} life={0.6} glow />
    </>
  );
}

function Cool({ r }: { r: Reaction }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          // Slides in from the side with a spin, lands, then a confident nod.
          const k = clamp01(t / 0.45);
          const e = outBack(k);
          s.position.x = REDUCED ? 0 : (1 - e) * -2.2;
          m.rotation = REDUCED ? 0 : (1 - e) * 2.4;
          const nod = t > 0.6 && t < 1.0 ? Math.sin(((t - 0.6) / 0.4) * Math.PI) * 0.12 : 0;
          s.position.y = -nod;
          s.scale.setScalar(1.6);
          m.opacity = clamp01(t / 0.2);
        }}
      />
      <Particles
        count={14}
        geometry={GEO.spark}
        colors={['#ffd54a', '#fff6c2']}
        spawn={(i) => {
          const a = (i / 14) * Math.PI * 2;
          return { p: [Math.cos(a) * 0.95, Math.sin(a) * 0.95, 0.1], v: [Math.cos(a) * 0.5, Math.sin(a) * 0.5 + 0.3, 0], delay: 0.45 };
        }}
        life={0.8}
        gravity={0}
        glow
      />
    </>
  );
}

function Pop({ r, color }: { r: Reaction; color: string }) {
  return (
    <>
      <EmojiSprite
        emoji={r.emoji}
        life={r.life}
        drive={(s, m, t) => {
          s.scale.setScalar(1.5 * outBack(clamp01(t / 0.3)));
          s.position.y = t * 0.3;
          m.opacity = 1;
        }}
      />
      <Particles count={12} geometry={GEO.spark} colors={[color, '#ffffff']} spawn={() => radial(1.8, 2)} life={0.7} glow gravity={-4} />
    </>
  );
}

// ---------------------------------------------------------------- layer

function ReactionFx({ r }: { r: Reaction }) {
  const [x, z] = yardCenter(r.color);
  const color = PLAYER_HEX[r.color];
  let body: ReactNode;
  switch (r.effect) {
    case 'laugh': body = <Laugh r={r} />; break;
    case 'love': body = <Love r={r} color={color} />; break;
    case 'fire': body = <Fire r={r} />; break;
    case 'clap': body = <Clap r={r} color={color} />; break;
    case 'wow': body = <Wow r={r} color={color} />; break;
    case 'cry': body = <Cry r={r} />; break;
    case 'party': body = <Party r={r} color={color} />; break;
    case 'cool': body = <Cool r={r} />; break;
    default: body = <Pop r={r} color={color} />;
  }
  // Slightly outward from the yard centre, toward the board corner, above the dice.
  return <group position={[x * 1.15, HEIGHT, z * 1.15]}>{body}</group>;
}

export function EmoteEffects() {
  const list = useReactions((s) => s.list);
  return (
    <>
      {list.map((r) => (
        <ReactionFx key={r.key} r={r} />
      ))}
    </>
  );
}
