import { RoundedBox } from '@react-three/drei';
import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { Color } from '../../shared/types';
import { sfx } from '../audio/audio';
import { ease, tween } from '../game/anim';
import { canRoll, requestRoll } from '../game/controller';
import { dice } from '../game/runtime';
import { useGame } from '../store/game';
import { DICE_HALF, PLAYER_HEX, YARD_TOP, yardCenter } from './layout';

/**
 * A real 3D die. The result always comes from the game logic (CSPRNG locally,
 * the server online); the throw is animated so that it settles exactly on the
 * orientation that shows that value on top. Opposite faces sum to seven.
 */

const SIZE = DICE_HALF * 2;
const REST_Y = YARD_TOP + DICE_HALF;

// Face value -> outward normal in the die's local frame.
const FACE_NORMAL: Record<number, THREE.Vector3> = {
  1: new THREE.Vector3(0, 1, 0),
  6: new THREE.Vector3(0, -1, 0),
  2: new THREE.Vector3(1, 0, 0),
  5: new THREE.Vector3(-1, 0, 0),
  3: new THREE.Vector3(0, 0, 1),
  4: new THREE.Vector3(0, 0, -1),
};

/** Rotation that turns face `v` to point up (+Y). */
export function faceUpQuaternion(v: number): THREE.Quaternion {
  return new THREE.Quaternion().setFromUnitVectors(FACE_NORMAL[v], new THREE.Vector3(0, 1, 0));
}

const PIPS: Record<number, [number, number][]> = {
  1: [[0, 0]],
  2: [[-1, -1], [1, 1]],
  3: [[-1, -1], [0, 0], [1, 1]],
  4: [[-1, -1], [1, -1], [-1, 1], [1, 1]],
  5: [[-1, -1], [1, -1], [0, 0], [-1, 1], [1, 1]],
  6: [[-1, -1], [-1, 0], [-1, 1], [1, -1], [1, 0], [1, 1]],
};

function pipMatrices(): THREE.Matrix4[] {
  const out: THREE.Matrix4[] = [];
  const z = new THREE.Vector3(0, 0, 1);
  for (let v = 1; v <= 6; v++) {
    const n = FACE_NORMAL[v];
    const u = Math.abs(n.y) > 0.5 ? new THREE.Vector3(1, 0, 0) : new THREE.Vector3(0, 1, 0);
    const w = new THREE.Vector3().crossVectors(n, u);
    const q = new THREE.Quaternion().setFromUnitVectors(z, n);
    const big = v === 1 ? 1.45 : 1;
    for (const [a, b] of PIPS[v]) {
      const p = n
        .clone()
        .multiplyScalar(DICE_HALF - 0.012)
        .addScaledVector(u, a * 0.2)
        .addScaledVector(w, b * 0.2);
      out.push(new THREE.Matrix4().compose(p, q, new THREE.Vector3(big, big, 0.42)));
    }
  }
  return out;
}

export function Dice() {
  const outer = useRef<THREE.Group>(null);
  const body = useRef<THREE.Group>(null);
  const pips = useRef<THREE.InstancedMesh>(null);
  const glow = useRef<THREE.Mesh>(null);
  const glowMat = useRef<THREE.MeshBasicMaterial>(null);
  const matrices = useMemo(pipMatrices, []);
  const pipGeo = useMemo(() => new THREE.SphereGeometry(0.075, 16, 10), []);

  const canRollNow = useGame((s) => !!s.view && !s.busy && !s.rollPending && !s.paused && s.view.phase === 'roll' && canRoll());
  const currentColor = useGame((s) => (s.view ? s.view.players[s.view.current].color : 'blue'));

  useLayoutEffect(() => {
    const m = pips.current!;
    matrices.forEach((mat, i) => m.setMatrixAt(i, mat));
    m.instanceMatrix.needsUpdate = true;
  }, [matrices]);

  useLayoutEffect(() => {
    const g = outer.current!;
    const b = body.current!;
    const restPoint = (color: Color) => {
      const [x, z] = yardCenter(color);
      return new THREE.Vector3(x, REST_Y, z);
    };

    dice.api = {
      place(color, value) {
        // Leave a freshly thrown die where it landed (it lands near the centre).
        const p = restPoint(color);
        if (g.position.distanceTo(p) > 0.5) g.position.copy(p);
        if (value) b.quaternion.copy(faceUpQuaternion(value));
      },
      async moveTo(color) {
        const to = restPoint(color);
        const from = g.position.clone();
        if (from.distanceTo(to) < 0.01) return;
        await tween(0.42, (t) => {
          const k = ease.inOutCubic(t);
          g.position.lerpVectors(from, to, k);
          g.position.y += Math.sin(Math.PI * t) * 1.3;
        });
        sfx('diceHit', 0.5);
      },
      async roll(value, color) {
        const end = restPoint(color);
        end.x += (Math.random() - 0.5) * 0.3;
        end.z += (Math.random() - 0.5) * 0.3;
        // Thrown in from beyond the yard's outer corner, high above the board.
        const out = new THREE.Vector3(end.x, 0, end.z).normalize();
        const start = new THREE.Vector3(end.x + out.x * 2.8 - out.z * 1.2, 3.4, end.z + out.z * 2.8 + out.x * 1.2);
        const axis = new THREE.Vector3(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).normalize();
        const turns = (3 + Math.random() * 1.5) * Math.PI * 2;
        const yaw = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.random() * Math.PI * 2);
        const final = yaw.multiply(faceUpQuaternion(value));
        const spin = new THREE.Quaternion();
        const hits = [0.364, 0.727, 0.909];
        let nextHit = 0;
        sfx('diceShake');
        await tween(1.25, (t) => {
          const tp = Math.min(1, t / 0.86);
          const k = ease.outCubic(tp);
          g.position.set(
            start.x + (end.x - start.x) * k,
            end.y + (start.y - end.y) * (1 - ease.outBounce(tp)),
            start.z + (end.z - start.z) * k,
          );
          // Final orientation times a decaying spin: lands exactly on `value`.
          spin.setFromAxisAngle(axis, turns * (1 - ease.outQuart(t)));
          b.quaternion.copy(final).multiply(spin);
          while (nextHit < hits.length && tp >= hits[nextHit]) {
            sfx('diceHit', 1 - nextHit * 0.3);
            nextHit++;
          }
        });
        b.quaternion.copy(final);
        g.position.copy(end);
        sfx('diceLand');
      },
    };
    // The canvas mounts after the store is initialised: start at the current player's yard.
    const v = useGame.getState().view;
    if (v) dice.api.place(v.players[v.current].color, v.dice ?? 6);
    return () => {
      dice.api = null;
    };
  }, []);

  useFrame((state) => {
    if (glow.current && glowMat.current) {
      const t = state.clock.elapsedTime;
      glow.current.visible = canRollNow;
      const s = 1 + Math.sin(t * 4) * 0.08;
      glow.current.scale.set(s, s, s);
      glowMat.current.opacity = 0.55 + Math.sin(t * 4) * 0.25;
      glow.current.position.set(outer.current!.position.x, YARD_TOP + 0.02, outer.current!.position.z);
    }
  });

  const onClick = (e: ThreeEvent<MouseEvent>) => {
    if (!canRollNow) return;
    e.stopPropagation();
    requestRoll();
  };

  return (
    <group>
      <group ref={outer} position={[0, REST_Y, 0]}>
        <group ref={body}>
          <RoundedBox
            args={[SIZE, SIZE, SIZE]}
            radius={0.11}
            smoothness={5}
            castShadow
            receiveShadow
            onClick={onClick}
            onPointerOver={() => canRollNow && (document.body.style.cursor = 'pointer')}
            onPointerOut={() => (document.body.style.cursor = '')}
          >
            <meshPhysicalMaterial color="#fffdf7" roughness={0.22} clearcoat={1} clearcoatRoughness={0.1} />
          </RoundedBox>
          <instancedMesh ref={pips} args={[pipGeo, undefined, 21]}>
            <meshStandardMaterial color="#1b1b24" roughness={0.35} />
          </instancedMesh>
        </group>
      </group>
      <mesh ref={glow} rotation={[-Math.PI / 2, 0, 0]} visible={false}>
        <ringGeometry args={[0.72, 0.86, 48]} />
        <meshBasicMaterial ref={glowMat} color={PLAYER_HEX[currentColor]} transparent depthWrite={false} toneMapped={false} />
      </mesh>
    </group>
  );
}
