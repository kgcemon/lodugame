import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { effects } from '../game/runtime';

/** Pooled particle bursts and shock rings for captures, home arrivals and victory. */

const MAX = 320;
const RINGS = 4;

export function Effects() {
  const mesh = useRef<THREE.InstancedMesh>(null);
  const ringRefs = useRef<(THREE.Mesh | null)[]>([]);
  const pool = useMemo(
    () => ({
      pos: new Float32Array(MAX * 3),
      vel: new Float32Array(MAX * 3),
      life: new Float32Array(MAX),
      maxLife: new Float32Array(MAX).fill(1),
      size: new Float32Array(MAX),
      cursor: 0,
      rings: Array.from({ length: RINGS }, () => ({ t: 1, x: 0, y: 0, z: 0 })),
      ringCursor: 0,
    }),
    [],
  );
  const geo = useMemo(() => new THREE.IcosahedronGeometry(0.06, 0), []);
  const tmp = useMemo(() => new THREE.Object3D(), []);
  const col = useMemo(() => new THREE.Color(), []);

  useLayoutEffect(() => {
    const m = mesh.current!;
    tmp.scale.setScalar(0);
    tmp.updateMatrix();
    for (let i = 0; i < MAX; i++) {
      m.setMatrixAt(i, tmp.matrix);
      m.setColorAt(i, col.set('#ffffff'));
    }
    m.instanceMatrix.needsUpdate = true;
    effects.api = {
      burst(x, y, z, color, count = 30, power = 1) {
        col.set(color);
        for (let n = 0; n < count; n++) {
          const i = pool.cursor;
          pool.cursor = (pool.cursor + 1) % MAX;
          pool.pos.set([x, y, z], i * 3);
          const a = Math.random() * Math.PI * 2;
          const up = 2 + Math.random() * 3.5;
          const out = (0.8 + Math.random() * 2.2) * power;
          pool.vel.set([Math.cos(a) * out, up * power, Math.sin(a) * out], i * 3);
          pool.maxLife[i] = pool.life[i] = 0.7 + Math.random() * 0.6;
          pool.size[i] = 0.6 + Math.random() * 0.9;
          m.setColorAt(i, col);
        }
        if (m.instanceColor) m.instanceColor.needsUpdate = true;
      },
      ring(x, y, z, color) {
        const r = pool.rings[pool.ringCursor];
        pool.ringCursor = (pool.ringCursor + 1) % RINGS;
        Object.assign(r, { t: 0, x, y, z });
        const ref = ringRefs.current[pool.rings.indexOf(r)];
        if (ref) (ref.material as THREE.MeshBasicMaterial).color.set(color);
      },
    };
    return () => {
      effects.api = null;
    };
  }, [pool, tmp, col]);

  useFrame((_, rawDt) => {
    const dt = Math.min(rawDt, 1 / 20);
    const m = mesh.current;
    if (!m) return;
    let any = false;
    for (let i = 0; i < MAX; i++) {
      if (pool.life[i] <= 0) continue;
      any = true;
      pool.life[i] -= dt;
      const j = i * 3;
      pool.vel[j + 1] -= 9 * dt;
      pool.pos[j] += pool.vel[j] * dt;
      pool.pos[j + 1] = Math.max(0.2, pool.pos[j + 1] + pool.vel[j + 1] * dt);
      pool.pos[j + 2] += pool.vel[j + 2] * dt;
      const k = Math.max(0, pool.life[i] / pool.maxLife[i]);
      tmp.position.set(pool.pos[j], pool.pos[j + 1], pool.pos[j + 2]);
      tmp.rotation.set(pool.life[i] * 7, pool.life[i] * 5, 0);
      tmp.scale.setScalar(pool.size[i] * k);
      tmp.updateMatrix();
      m.setMatrixAt(i, tmp.matrix);
    }
    if (any) m.instanceMatrix.needsUpdate = true;
    pool.rings.forEach((r, i) => {
      const ref = ringRefs.current[i];
      if (!ref) return;
      if (r.t >= 1) {
        ref.visible = false;
        return;
      }
      r.t = Math.min(1, r.t + dt * 1.6);
      ref.visible = true;
      ref.position.set(r.x, r.y, r.z);
      const s = 0.3 + r.t * 2.2;
      ref.scale.set(s, s, s);
      (ref.material as THREE.MeshBasicMaterial).opacity = (1 - r.t) * 0.8;
    });
  });

  return (
    <group>
      <instancedMesh ref={mesh} args={[geo, undefined, MAX]} frustumCulled={false}>
        <meshStandardMaterial emissiveIntensity={0.6} roughness={0.3} metalness={0.3} toneMapped={false} />
      </instancedMesh>
      {Array.from({ length: RINGS }, (_, i) => (
        <mesh key={i} ref={(el) => (ringRefs.current[i] = el)} rotation={[-Math.PI / 2, 0, 0]} visible={false}>
          <ringGeometry args={[0.42, 0.5, 48]} />
          <meshBasicMaterial transparent depthWrite={false} toneMapped={false} />
        </mesh>
      ))}
    </group>
  );
}
