import { RoundedBox } from '@react-three/drei';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { HOME_COLUMN, SAFE_INDICES, START_OFFSET, TRACK, YARD_ORIGIN, YARD_SLOTS } from '../../shared/board';
import { COLORS, type Color } from '../../shared/types';
import { CENTER_APEX, CENTER_BASE, PLAYER_HEX, TILE_TOP, YARD_TOP, cellToWorld } from './layout';
import { arrowShape, roundedRect, slab, starShape, surfaceTexture, woodTexture } from './procedural';

/**
 * The board is real geometry: a rounded wooden case, a printed play surface,
 * 72 bevelled cell tiles (one instanced draw call), raised yards with token
 * wells, and a four-colour pyramid at the centre.
 */

const TILE_BASE = TILE_TOP - 0.1;

interface TileSpec {
  x: number;
  z: number;
  color: string;
}

function tileSpecs(): TileSpec[] {
  const specs: TileSpec[] = [];
  const startOf = new Map<number, Color>(COLORS.map((c) => [START_OFFSET[c], c]));
  TRACK.forEach((cell, i) => {
    const [x, z] = cellToWorld(cell);
    const c = startOf.get(i);
    specs.push({ x, z, color: c ? PLAYER_HEX[c] : '#fbf8f1' });
  });
  for (const c of COLORS) {
    for (const cell of HOME_COLUMN[c]) {
      const [x, z] = cellToWorld(cell);
      specs.push({ x, z, color: PLAYER_HEX[c] });
    }
  }
  return specs;
}

function CellTiles() {
  const ref = useRef<THREE.InstancedMesh>(null);
  const specs = useMemo(tileSpecs, []);
  const geo = useMemo(() => slab(roundedRect(0.86, 0.86, 0.12), 0.05, 0.025, 2), []);
  useLayoutEffect(() => {
    const m = ref.current!;
    const mat = new THREE.Matrix4();
    const col = new THREE.Color();
    specs.forEach((s, i) => {
      mat.makeTranslation(s.x, TILE_BASE, s.z);
      m.setMatrixAt(i, mat);
      m.setColorAt(i, col.set(s.color));
    });
    m.instanceMatrix.needsUpdate = true;
    if (m.instanceColor) m.instanceColor.needsUpdate = true;
  }, [specs]);
  return (
    <instancedMesh ref={ref} args={[geo, undefined, specs.length]} receiveShadow castShadow={false}>
      <meshPhysicalMaterial roughness={0.38} clearcoat={0.7} clearcoatRoughness={0.25} />
    </instancedMesh>
  );
}

function SafeMarkers() {
  const starGeo = useMemo(() => slab(starShape(0.3, 0.13), 0.015, 0.012, 1), []);
  const arrowGeo = useMemo(() => slab(arrowShape(), 0.015, 0.01, 1), []);
  const stars = SAFE_INDICES.filter((i) => !COLORS.some((c) => START_OFFSET[c] === i));
  return (
    <group>
      {stars.map((i) => {
        const [x, z] = cellToWorld(TRACK[i]);
        return (
          <mesh key={i} geometry={starGeo} position={[x, TILE_TOP, z]} receiveShadow>
            <meshStandardMaterial color="#c99a2e" metalness={0.75} roughness={0.28} />
          </mesh>
        );
      })}
      {COLORS.map((c) => {
        // Arrow on the square where each colour turns into its home column.
        const entry = TRACK[(START_OFFSET[c] + 50) % 52];
        const first = HOME_COLUMN[c][0];
        const [x, z] = cellToWorld(entry);
        const angle = Math.atan2(-(first[1] - entry[1]), first[0] - entry[0]);
        return (
          <mesh key={c} geometry={arrowGeo} position={[x, TILE_TOP, z]} rotation={[0, angle, 0]} receiveShadow>
            <meshStandardMaterial color={PLAYER_HEX[c]} roughness={0.35} />
          </mesh>
        );
      })}
      {COLORS.map((c) => {
        // White star ring marks each start square.
        const [x, z] = cellToWorld(TRACK[START_OFFSET[c]]);
        return (
          <mesh key={`s${c}`} geometry={starGeo} position={[x, TILE_TOP, z]} scale={0.85}>
            <meshStandardMaterial color="#ffffff" roughness={0.3} />
          </mesh>
        );
      })}
    </group>
  );
}

function Yard({ color }: { color: Color }) {
  const o = YARD_ORIGIN[color];
  const [cx, cz] = cellToWorld([o[0] + 2.5, o[1] + 2.5]);
  const plate = useMemo(() => slab(roundedRect(5.7, 5.7, 0.45), 0.06, 0.03), []);
  const inner = useMemo(() => slab(roundedRect(4.25, 4.25, 0.35), 0.04, 0.02), []);
  const hex = PLAYER_HEX[color];
  const light = useMemo(() => new THREE.Color(hex).lerp(new THREE.Color('#ffffff'), 0.35).getStyle(), [hex]);
  return (
    <group>
      <mesh geometry={plate} position={[cx, 0.1, cz]} receiveShadow>
        <meshPhysicalMaterial color={hex} roughness={0.32} clearcoat={0.8} clearcoatRoughness={0.2} />
      </mesh>
      <mesh geometry={inner} position={[cx, 0.19, cz]} receiveShadow>
        <meshPhysicalMaterial color="#fbf8f1" roughness={0.4} clearcoat={0.5} />
      </mesh>
      {YARD_SLOTS.map((s, i) => {
        const [x, z] = cellToWorld([o[0] + s[0], o[1] + s[1]]);
        return (
          <group key={i} position={[x, YARD_TOP - 0.03, z]}>
            <mesh receiveShadow>
              <cylinderGeometry args={[0.43, 0.43, 0.035, 40]} />
              <meshStandardMaterial color={light} roughness={0.45} />
            </mesh>
            <mesh rotation={[Math.PI / 2, 0, 0]} position={[0, 0.02, 0]}>
              <torusGeometry args={[0.43, 0.035, 12, 48]} />
              <meshPhysicalMaterial color={hex} roughness={0.3} clearcoat={1} />
            </mesh>
          </group>
        );
      })}
    </group>
  );
}

function CenterHome() {
  const wedges = useMemo(() => {
    const h = 1.5;
    const corners: Record<Color, [[number, number], [number, number]]> = {
      red: [[-h, -h], [-h, h]],
      green: [[h, -h], [-h, -h]],
      yellow: [[h, h], [h, -h]],
      blue: [[-h, h], [h, h]],
    };
    return COLORS.map((c) => {
      const [a, b] = corners[c];
      const pa = new THREE.Vector3(a[0], CENTER_BASE, a[1]);
      const pb = new THREE.Vector3(b[0], CENTER_BASE, b[1]);
      const apex = new THREE.Vector3(0, CENTER_APEX, 0);
      // Face plus a vertical skirt down to the surface so there is no gap.
      const fa = new THREE.Vector3(a[0], 0.08, a[1]);
      const fb = new THREE.Vector3(b[0], 0.08, b[1]);
      const verts = [pa, apex, pb, fa, pa, pb, fa, pb, fb];
      const n = new THREE.Vector3().subVectors(apex, pa).cross(new THREE.Vector3().subVectors(pb, pa));
      if (n.y < 0) {
        verts[1] = pb;
        verts[2] = apex;
      }
      const geo = new THREE.BufferGeometry().setFromPoints(verts);
      geo.computeVertexNormals();
      return { c, geo };
    });
  }, []);
  return (
    <group>
      {wedges.map(({ c, geo }) => (
        <mesh key={c} geometry={geo} receiveShadow castShadow={false}>
          <meshPhysicalMaterial color={PLAYER_HEX[c]} roughness={0.3} clearcoat={1} clearcoatRoughness={0.15} side={THREE.DoubleSide} />
        </mesh>
      ))}
      <mesh position={[0, CENTER_APEX, 0]} castShadow>
        <sphereGeometry args={[0.13, 24, 16]} />
        <meshStandardMaterial color="#e8c15a" metalness={0.9} roughness={0.2} />
      </mesh>
    </group>
  );
}

export function Board() {
  const wood = useMemo(woodTexture, []);
  const surface = useMemo(surfaceTexture, []);
  return (
    <group>
      {/* Wooden case */}
      <RoundedBox args={[16.7, 0.8, 16.7]} radius={0.32} smoothness={5} position={[0, -0.4, 0]} receiveShadow>
        <meshPhysicalMaterial map={wood} roughness={0.5} clearcoat={0.45} clearcoatRoughness={0.35} />
      </RoundedBox>
      {/* Gold inlay around the play surface */}
      <RoundedBox args={[15.55, 0.1, 15.55]} radius={0.05} smoothness={2} position={[0, 0.02, 0]}>
        <meshStandardMaterial color="#c9a14a" metalness={0.85} roughness={0.25} />
      </RoundedBox>
      {/* Printed play surface */}
      <RoundedBox args={[15.3, 0.12, 15.3]} radius={0.05} smoothness={2} position={[0, 0.04, 0]} receiveShadow>
        <meshStandardMaterial map={surface} color="#efe4cf" roughness={0.75} />
      </RoundedBox>
      <CellTiles />
      <SafeMarkers />
      {COLORS.map((c) => (
        <Yard key={c} color={c} />
      ))}
      <CenterHome />
    </group>
  );
}
