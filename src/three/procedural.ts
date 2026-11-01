import * as THREE from 'three';

/** Procedural textures and geometry — no external assets required. */

let woodTex: THREE.CanvasTexture | null = null;

export function woodTexture(): THREE.CanvasTexture {
  if (woodTex) return woodTex;
  const size = 512;
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d')!;
  const grad = g.createLinearGradient(0, 0, size, size);
  grad.addColorStop(0, '#5a3119');
  grad.addColorStop(0.5, '#7a4526');
  grad.addColorStop(1, '#5e341c');
  g.fillStyle = grad;
  g.fillRect(0, 0, size, size);
  // Grain: long wavy strokes with varying tone.
  for (let i = 0; i < 220; i++) {
    const y0 = Math.random() * size;
    const amp = 2 + Math.random() * 6;
    const freq = 0.004 + Math.random() * 0.01;
    const phase = Math.random() * Math.PI * 2;
    g.beginPath();
    for (let x = 0; x <= size; x += 8) {
      const y = y0 + Math.sin(x * freq + phase) * amp + Math.sin(x * freq * 3.1) * amp * 0.3;
      if (x === 0) g.moveTo(x, y);
      else g.lineTo(x, y);
    }
    const dark = Math.random() < 0.6;
    g.strokeStyle = dark ? `rgba(35,16,6,${0.05 + Math.random() * 0.12})` : `rgba(190,120,70,${0.04 + Math.random() * 0.08})`;
    g.lineWidth = 0.6 + Math.random() * 2.2;
    g.stroke();
  }
  // A couple of knots.
  for (let k = 0; k < 3; k++) {
    const x = Math.random() * size;
    const y = Math.random() * size;
    for (let r = 2; r < 26; r += 3) {
      g.beginPath();
      g.ellipse(x, y, r * 2.2, r * 0.7, 0, 0, Math.PI * 2);
      g.strokeStyle = `rgba(40,18,8,${0.12 - r * 0.004})`;
      g.lineWidth = 1.2;
      g.stroke();
    }
  }
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.anisotropy = 4;
  woodTex = tex;
  return tex;
}

let paperTex: THREE.CanvasTexture | null = null;

/** Subtle speckled roughness for the printed play surface. */
export function surfaceTexture(): THREE.CanvasTexture {
  if (paperTex) return paperTex;
  const size = 256;
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const g = c.getContext('2d')!;
  g.fillStyle = '#f4ecdc';
  g.fillRect(0, 0, size, size);
  const img = g.getImageData(0, 0, size, size);
  for (let i = 0; i < img.data.length; i += 4) {
    const n = (Math.random() - 0.5) * 14;
    img.data[i] += n;
    img.data[i + 1] += n;
    img.data[i + 2] += n;
  }
  g.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(4, 4);
  paperTex = tex;
  return tex;
}

export function roundedRect(w: number, h: number, r: number): THREE.Shape {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

/** Extrude a 2D shape upward (+Y) into a bevelled slab whose base sits at y=0. */
export function slab(shape: THREE.Shape, depth: number, bevel: number, segments = 3): THREE.ExtrudeGeometry {
  const geo = new THREE.ExtrudeGeometry(shape, {
    depth,
    bevelEnabled: bevel > 0,
    bevelThickness: bevel,
    bevelSize: bevel,
    bevelSegments: segments,
    curveSegments: 10,
  });
  geo.rotateX(-Math.PI / 2);
  geo.translate(0, bevel, 0);
  geo.computeVertexNormals();
  return geo;
}

export function starShape(outer: number, inner: number, points = 5): THREE.Shape {
  const s = new THREE.Shape();
  for (let i = 0; i < points * 2; i++) {
    const r = i % 2 === 0 ? outer : inner;
    const a = (i / (points * 2)) * Math.PI * 2 + Math.PI / 2;
    const x = Math.cos(a) * r;
    const y = Math.sin(a) * r;
    if (i === 0) s.moveTo(x, y);
    else s.lineTo(x, y);
  }
  s.closePath();
  return s;
}

export function arrowShape(): THREE.Shape {
  // Points toward +X in shape space.
  const s = new THREE.Shape();
  s.moveTo(0.3, 0);
  s.lineTo(-0.05, 0.26);
  s.lineTo(-0.05, 0.1);
  s.lineTo(-0.28, 0.1);
  s.lineTo(-0.28, -0.1);
  s.lineTo(-0.05, -0.1);
  s.lineTo(-0.05, -0.26);
  s.closePath();
  return s;
}

/** Classic Ludo pawn: a lathe profile with a flared base, waist and spherical head. */
export function pawnGeometry(): THREE.LatheGeometry {
  const pts: THREE.Vector2[] = [
    [0, 0],
    [0.27, 0],
    [0.3, 0.015],
    [0.31, 0.045],
    [0.3, 0.075],
    [0.26, 0.1],
    [0.2, 0.14],
    [0.155, 0.2],
    [0.13, 0.28],
    [0.115, 0.37],
    [0.115, 0.42],
    [0.17, 0.45],
    [0.175, 0.47],
    [0.16, 0.49],
    [0.105, 0.51],
  ].map(([x, y]) => new THREE.Vector2(x, y));
  const cy = 0.615;
  const r = 0.145;
  for (let a = -48; a <= 90; a += 8) {
    const rad = (a * Math.PI) / 180;
    pts.push(new THREE.Vector2(Math.max(0.0001, Math.cos(rad) * r), cy + Math.sin(rad) * r));
  }
  pts.push(new THREE.Vector2(0, cy + r));
  const geo = new THREE.LatheGeometry(pts, 40);
  geo.computeVertexNormals();
  return geo;
}
