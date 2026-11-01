import { ContactShadows, Environment, Lightformer, PerformanceMonitor } from '@react-three/drei';
import { Canvas, useFrame } from '@react-three/fiber';
import { Suspense, useState } from 'react';
import * as THREE from 'three';
import { tick } from '../game/anim';
import { IS_MOBILE, useSettings, type Quality } from '../store/settings';
import { Board } from './Board';
import { CameraRig } from './CameraRig';
import { Dice } from './Dice';
import { Effects } from './Effects';
import { EmoteEffects } from './EmoteEffects';
import { Tokens } from './Tokens';

function Ticker() {
  useFrame((_, dt) => tick(dt));
  return null;
}

const QUALITY: Record<Exclude<Quality, 'auto'>, { dpr: [number, number]; shadow: number; env: number }> = {
  low: { dpr: [0.75, 1], shadow: 0, env: 32 },
  medium: { dpr: [1, 1.5], shadow: 1024, env: 64 },
  high: { dpr: [1, 2], shadow: 2048, env: 128 },
};

function Lights({ shadow }: { shadow: number }) {
  return (
    <>
      <hemisphereLight args={['#fff6e8', '#2a2440', 0.4]} />
      <directionalLight
        position={[7, 16, 9]}
        intensity={2.2}
        color="#fff3e0"
        castShadow={shadow > 0}
        shadow-mapSize={[shadow || 512, shadow || 512]}
        shadow-camera-left={-10}
        shadow-camera-right={10}
        shadow-camera-top={10}
        shadow-camera-bottom={-10}
        shadow-camera-near={2}
        shadow-camera-far={40}
        shadow-bias={-0.0004}
        shadow-normalBias={0.02}
      />
      <directionalLight position={[-10, 8, -6]} intensity={0.55} color="#9fb8ff" />
    </>
  );
}

export function Scene({ onReady, active = true }: { onReady: () => void; active?: boolean }) {
  const quality = useSettings((s) => s.quality);
  const auto = quality === 'auto';
  const q = QUALITY[auto ? (IS_MOBILE ? 'medium' : 'high') : quality];
  const maxDpr = Math.min(q.dpr[1], typeof window !== 'undefined' ? window.devicePixelRatio : 1);
  const [dpr, setDpr] = useState(Math.min(IS_MOBILE ? 1.25 : 1.5, maxDpr));

  return (
    <Canvas
      key={quality}
      frameloop={active ? 'always' : 'never'}
      shadows={q.shadow > 0 ? 'soft' : false}
      dpr={auto ? dpr : [q.dpr[0], maxDpr]}
      camera={{ fov: 38, near: 0.5, far: 200, position: [0, 18, 22] }}
      gl={{ antialias: quality !== 'low', powerPreference: 'high-performance', alpha: true }}
      onCreated={({ gl }) => {
        gl.toneMapping = THREE.NeutralToneMapping;
        gl.toneMappingExposure = 0.92;
        requestAnimationFrame(() => onReady());
      }}
      style={{ touchAction: 'none' }}
    >
      {auto && (
        <PerformanceMonitor
          onIncline={() => setDpr(maxDpr)}
          onDecline={() => setDpr(Math.max(0.75, dpr - 0.25))}
          flipflops={3}
        />
      )}
      <Ticker />
      <Lights shadow={q.shadow} />
      <Suspense fallback={null}>
        <Environment resolution={q.env} frames={1}>
          <Lightformer form="rect" intensity={1.3} position={[0, 8, 0]} rotation-x={Math.PI / 2} scale={[14, 14, 1]} />
          <Lightformer form="rect" intensity={1.2} color="#ffd9a8" position={[-9, 3, 4]} rotation-y={Math.PI / 2} scale={[8, 3, 1]} />
          <Lightformer form="rect" intensity={1} color="#a8c4ff" position={[9, 3, -4]} rotation-y={-Math.PI / 2} scale={[8, 3, 1]} />
          <Lightformer form="ring" intensity={0.8} position={[0, 4, 10]} scale={4} />
        </Environment>
      </Suspense>
      <Board />
      <Tokens />
      <Dice />
      <Effects />
      <EmoteEffects />
      <ContactShadows position={[0, -0.81, 0]} scale={34} blur={2.8} opacity={0.55} far={6} frames={1} resolution={512} />
      <CameraRig />
    </Canvas>
  );
}
