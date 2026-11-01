import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { memo, useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { validMoves } from '../../shared/engine';
import type { Color } from '../../shared/types';
import { damp } from '../game/anim';
import { chooseToken } from '../game/controller';
import { targets, tokenRuntime } from '../game/runtime';
import { isLocalTurn, useGame } from '../store/game';
import { PLAYER_HEX, tokenKey } from './layout';
import { pawnGeometry } from './procedural';

/** Shared GPU resources: one pawn geometry, one material per colour. */
const shared = {
  pawn: null as THREE.LatheGeometry | null,
  band: new THREE.TorusGeometry(0.135, 0.022, 10, 36),
  ring: new THREE.TorusGeometry(0.4, 0.035, 10, 48),
  hit: new THREE.CylinderGeometry(0.42, 0.42, 0.9, 12),
  gold: new THREE.MeshStandardMaterial({ color: '#e7c35e', metalness: 0.9, roughness: 0.22 }),
  hitMat: new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false }),
  body: {} as Record<Color, THREE.MeshPhysicalMaterial>,
  glow: {} as Record<Color, THREE.MeshBasicMaterial>,
};

function resources() {
  if (!shared.pawn) {
    shared.pawn = pawnGeometry();
    for (const c of ['red', 'green', 'yellow', 'blue'] as Color[]) {
      shared.body[c] = new THREE.MeshPhysicalMaterial({
        color: PLAYER_HEX[c],
        roughness: 0.18,
        metalness: 0.05,
        clearcoat: 1,
        clearcoatRoughness: 0.08,
        sheen: 0.3,
        sheenColor: new THREE.Color('#ffffff'),
      });
      shared.glow[c] = new THREE.MeshBasicMaterial({
        color: new THREE.Color(PLAYER_HEX[c]).lerp(new THREE.Color('#ffffff'), 0.35),
        transparent: true,
        opacity: 0.9,
        depthWrite: false,
        toneMapped: false,
      });
    }
  }
  return shared;
}

interface TokenProps {
  player: number;
  token: number;
  color: Color;
  selectable: boolean;
}

const Token = memo(function Token({ player, token, color, selectable }: TokenProps) {
  const key = tokenKey(player, token);
  const outer = useRef<THREE.Group>(null);
  const inner = useRef<THREE.Group>(null);
  const ring = useRef<THREE.Mesh>(null);
  const r = resources();
  const phase = useMemo(() => Math.random() * Math.PI * 2, []);

  useLayoutEffect(() => {
    const rt = tokenRuntime(key);
    rt.group = outer.current;
    const t = targets.get(key);
    if (t && outer.current) {
      outer.current.position.set(t.x, t.y, t.z);
      outer.current.scale.setScalar(t.scale);
    }
    return () => {
      if (rt.group === outer.current) rt.group = null;
    };
  }, [key]);

  useFrame((state, dt) => {
    const g = outer.current;
    const i = inner.current;
    if (!g || !i) return;
    const rt = tokenRuntime(key);
    const time = state.clock.elapsedTime;
    if (!rt.scripted) {
      const t = targets.get(key);
      if (t) {
        const k = damp(10, dt);
        g.position.x += (t.x - g.position.x) * k;
        g.position.y += (t.y - g.position.y) * k;
        g.position.z += (t.z - g.position.z) * k;
        const s = g.scale.x + (t.scale - g.scale.x) * k;
        g.scale.setScalar(s);
      }
    }
    // Idle life: selectable tokens hop gently, others breathe almost imperceptibly.
    const bob = selectable ? Math.abs(Math.sin(time * 4.2 + phase)) * 0.14 : Math.sin(time * 1.6 + phase) * 0.008;
    i.position.y = rt.scripted ? 0 : bob;
    rt.squash = Math.max(0, rt.squash - dt * 5);
    const sq = Math.sin(rt.squash * Math.PI) * 0.22;
    i.scale.set(1 + sq * 0.5, 1 - sq, 1 + sq * 0.5);
    i.rotation.x = rt.spin;
    if (ring.current) {
      const p = 1 + Math.sin(time * 5 + phase) * 0.08;
      ring.current.scale.set(p, p, p);
      ring.current.visible = selectable && !rt.scripted;
    }
  });

  const onClick = (e: ThreeEvent<MouseEvent>) => {
    if (!selectable) return;
    e.stopPropagation();
    chooseToken(token);
  };
  const onOver = (e: ThreeEvent<PointerEvent>) => {
    if (!selectable) return;
    e.stopPropagation();
    document.body.style.cursor = 'pointer';
  };
  const onOut = () => {
    document.body.style.cursor = '';
  };

  return (
    <group ref={outer}>
      <group ref={inner}>
        <mesh geometry={r.pawn!} material={r.body[color]} castShadow receiveShadow />
        <mesh geometry={r.band} material={r.gold} position={[0, 0.47, 0]} rotation={[Math.PI / 2, 0, 0]} castShadow />
      </group>
      <mesh ref={ring} geometry={r.ring} material={r.glow[color]} rotation={[Math.PI / 2, 0, 0]} position={[0, 0.03, 0]} visible={false} />
      <mesh
        geometry={r.hit}
        material={r.hitMat}
        position={[0, 0.4, 0]}
        onClick={onClick}
        onPointerOver={onOver}
        onPointerOut={onOut}
      />
    </group>
  );
});

export function Tokens() {
  const view = useGame((s) => s.view);
  const busy = useGame((s) => s.busy);
  const rollPending = useGame((s) => s.rollPending);
  const paused = useGame((s) => s.paused);
  const localColors = useGame((s) => s.localColors);
  const matchKind = useGame((s) => s.matchKind);
  if (!view) return null;
  const canPick = !busy && !rollPending && !paused && isLocalTurn({ view, localColors, matchKind });
  const movable = canPick ? validMoves(view) : [];
  return (
    <group>
      {view.players.map((p, pi) =>
        p.tokens.map((_, ti) => (
          <Token
            key={`${p.color}-${ti}`}
            player={pi}
            token={ti}
            color={p.color}
            selectable={pi === view.current && movable.includes(ti)}
          />
        )),
      )}
    </group>
  );
}
