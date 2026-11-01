import { OrbitControls } from '@react-three/drei';
import { useFrame, useThree } from '@react-three/fiber';
import { useEffect, useRef } from 'react';
import * as THREE from 'three';
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib';
import { damp } from '../game/anim';
import { cameraFocus } from '../game/runtime';
import { useGame } from '../store/game';
import { useSettings, type CameraPreset } from '../store/settings';
import { VIEW_AZIMUTH } from './layout';

/**
 * Tabletop camera. Presets define polar angle and distance; the azimuth keeps
 * the local player's yard at the bottom-left. Users may zoom and orbit within
 * limits; changes of preset, player or screen glide smoothly instead of cutting.
 */

const PRESETS: Record<CameraPreset, { polar: number; dist: number }> = {
  tabletop: { polar: 0.8, dist: 24.5 },
  top: { polar: 0.06, dist: 25 },
  close: { polar: 0.98, dist: 19 },
};

const AZIMUTH_FREEDOM = 0.7;
const MAX_POLAR = 1.12;

/** Distance needed to fit the board horizontally on narrow (portrait) screens. */
function fitDistance(base: number, aspect: number, fov: number): number {
  const half = THREE.MathUtils.degToRad(fov / 2);
  const hHalf = Math.atan(Math.tan(half) * aspect);
  const needed = 8.9 / Math.tan(hHalf);
  return Math.max(base, Math.min(needed, 46));
}

export function CameraRig() {
  const controls = useRef<OrbitControlsImpl>(null);
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera;
  const size = useThree((s) => s.size);
  const preset = useSettings((s) => s.camera);
  const screen = useGame((s) => s.screen);
  const viewColor = useGame((s) => s.viewColor);
  const inMenu = screen !== 'game';

  const goal = useRef({ theta: 0, phi: 0.9, radius: 22, gliding: true });

  useEffect(() => {
    const p = inMenu ? { polar: 0.9, dist: 25 } : PRESETS[preset];
    const radius = fitDistance(p.dist, size.width / size.height, camera.fov);
    goal.current = { theta: inMenu ? goal.current.theta : VIEW_AZIMUTH[viewColor], phi: p.polar, radius, gliding: true };
    const c = controls.current;
    if (c) {
      c.minDistance = radius * 0.6;
      c.maxDistance = radius * 1.35;
    }
  }, [preset, inMenu, viewColor, size.width, size.height, camera.fov]);

  const sph = useRef(new THREE.Spherical());
  const target = useRef(new THREE.Vector3());

  useFrame((state, dt) => {
    const c = controls.current;
    if (!c) return;
    const g = goal.current;

    if (inMenu || cameraFocus.celebrate) {
      // Slow cinematic orbit on the menu and after a win.
      c.autoRotate = true;
      c.autoRotateSpeed = cameraFocus.celebrate ? 1.4 : 0.5;
      c.minAzimuthAngle = -Infinity;
      c.maxAzimuthAngle = Infinity;
    } else {
      c.autoRotate = false;
    }

    if (g.gliding) {
      // Interpolate in spherical space so the camera swings around the board.
      sph.current.setFromVector3(camera.position.clone().sub(c.target));
      const k = damp(3.2, dt);
      let dTheta = g.theta - sph.current.theta;
      dTheta = Math.atan2(Math.sin(dTheta), Math.cos(dTheta));
      if (inMenu) dTheta = 0;
      sph.current.theta += dTheta * k;
      sph.current.phi += (g.phi - sph.current.phi) * k;
      sph.current.radius += (g.radius - sph.current.radius) * k;
      camera.position.setFromSpherical(sph.current).add(c.target);
      // Loosen the user limits while gliding so they can't snap the camera mid-flight.
      c.minDistance = 0;
      c.maxDistance = Infinity;
      c.maxPolarAngle = Math.PI / 2;
      const done = Math.abs(dTheta) < 0.003 && Math.abs(g.phi - sph.current.phi) < 0.003 && Math.abs(g.radius - sph.current.radius) < 0.03;
      c.enabled = false;
      if (done) {
        g.gliding = false;
        c.enabled = true;
        c.minDistance = g.radius * 0.6;
        c.maxDistance = g.radius * 1.35;
        c.maxPolarAngle = MAX_POLAR;
        if (!inMenu) {
          c.minAzimuthAngle = g.theta - AZIMUTH_FREEDOM;
          c.maxAzimuthAngle = g.theta + AZIMUTH_FREEDOM;
        }
      }
    } else if (!inMenu && !cameraFocus.celebrate) {
      c.enabled = true;
      c.minAzimuthAngle = g.theta - AZIMUTH_FREEDOM;
      c.maxAzimuthAngle = g.theta + AZIMUTH_FREEDOM;
    }

    // Lean gently toward the moving token, then settle back on the centre.
    const f = cameraFocus.point;
    target.current.set(f ? f[0] * 0.22 : 0, 0, f ? f[2] * 0.22 : 0);
    c.target.lerp(target.current, damp(f ? 2.5 : 1.8, dt));
    c.update();
    void state;
  });

  return (
    <OrbitControls
      ref={controls}
      makeDefault
      enablePan={false}
      enableDamping
      dampingFactor={0.08}
      minPolarAngle={0.02}
      maxPolarAngle={MAX_POLAR}
      rotateSpeed={0.6}
      zoomSpeed={0.7}
    />
  );
}
