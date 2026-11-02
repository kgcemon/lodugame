/**
 * Dice skins: looks for a player's die, bought with diamonds in the dice shop. The server
 * owns the catalogue (prices are checked there); clients draw a die with these colours.
 * Colours are #rrggbb: `face` is the top of the face, `faceBottom` the bottom of its
 * gradient, `rim` the darker edge, `pip` the dots and `one` the single dot of the 1.
 */
export interface DiceSkin {
  id: string;
  name: string;
  /** Price in diamonds (0 = everyone has it). */
  price: number;
  face: string;
  faceBottom: string;
  rim: string;
  pip: string;
  one: string;
}

export const DEFAULT_DICE_SKIN = 'classic';

export const DICE_SKINS: DiceSkin[] = [
  { id: 'classic', name: 'Classic', price: 0, face: '#ffffff', faceBottom: '#e4e1ee', rim: '#c9c5d8', pip: '#23202e', one: '#d62839' },
  { id: 'ruby', name: 'Ruby', price: 30, face: '#ff6b78', faceBottom: '#c4142d', rim: '#7a0b1c', pip: '#ffffff', one: '#ffe27a' },
  { id: 'emerald', name: 'Emerald', price: 30, face: '#4be39a', faceBottom: '#0f8a4e', rim: '#0a4f2d', pip: '#ffffff', one: '#ffe27a' },
  { id: 'sapphire', name: 'Sapphire', price: 30, face: '#5fa8ff', faceBottom: '#1a4fc4', rim: '#0d2a6e', pip: '#ffffff', one: '#ffe27a' },
  { id: 'gold', name: 'Royal Gold', price: 80, face: '#ffe58a', faceBottom: '#d99a00', rim: '#8a5a00', pip: '#5a2d00', one: '#c4142d' },
  { id: 'midnight', name: 'Midnight', price: 80, face: '#3a3f66', faceBottom: '#10122a', rim: '#05060f', pip: '#ffd34d', one: '#ff5c6c' },
  { id: 'candy', name: 'Candy', price: 120, face: '#ffd6ef', faceBottom: '#ff7ac2', rim: '#b33c86', pip: '#7a1f5c', one: '#ffffff' },
  { id: 'neon', name: 'Neon', price: 150, face: '#1d2350', faceBottom: '#070a24', rim: '#39ffd6', pip: '#39ffd6', one: '#ff3df2' },
  { id: 'diamond', name: 'Diamond', price: 250, face: '#f2fdff', faceBottom: '#86e1ff', rim: '#2fb8ea', pip: '#0b4f8a', one: '#0b4f8a' },
];

export function diceSkin(id: string | null | undefined): DiceSkin | undefined {
  return DICE_SKINS.find((s) => s.id === id);
}
