/**
 * Dice skins: looks for a player's die, bought with diamonds in the dice shop. The server
 * owns the catalogue (prices are checked there); clients draw a die with these colours.
 * Colours are #rrggbb: `face` is the top of the face, `faceBottom` the bottom of its
 * gradient, `rim` the darker edge, `pip` the dots and `one` the single dot of the 1.
 * A flag die (`flag`, an ISO country code) shows that country's flag on every face; its
 * colours are the flag's main colours, for clients that draw plain dice.
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
  /** Two-letter country whose flag covers the faces (flag dice only). */
  flag?: string;
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
  // Country flags.
  { id: 'flag_bd', name: 'Bangladesh', price: 60, flag: 'BD', face: '#0b8a5f', faceBottom: '#006a4e', rim: '#00412f', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_in', name: 'India', price: 60, flag: 'IN', face: '#ffffff', faceBottom: '#f2f2f2', rim: '#138808', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_pk', name: 'Pakistan', price: 60, flag: 'PK', face: '#0b6b3a', faceBottom: '#01411c', rim: '#012a12', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_np', name: 'Nepal', price: 60, flag: 'NP', face: '#f04060', faceBottom: '#dc143c', rim: '#003893', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_lk', name: 'Sri Lanka', price: 60, flag: 'LK', face: '#ffcf4a', faceBottom: '#ffbe29', rim: '#8d153a', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_ae', name: 'UAE', price: 60, flag: 'AE', face: '#ffffff', faceBottom: '#e8e8e8', rim: '#00732f', pip: '#ffffff', one: '#ffffff' },
  { id: 'flag_sa', name: 'Saudi Arabia', price: 60, flag: 'SA', face: '#1a8a4c', faceBottom: '#006c35', rim: '#00431f', pip: '#ffffff', one: '#ffffff' },
];

export function diceSkin(id: string | null | undefined): DiceSkin | undefined {
  return DICE_SKINS.find((s) => s.id === id);
}
