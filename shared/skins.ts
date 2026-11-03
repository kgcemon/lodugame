/**
 * Dice skins: looks for a player's die, bought with diamonds in the dice shop. The server
 * owns the catalogue (prices, publishing and stock are checked there; an administrator can
 * change them); the app draws each premium die by its id and falls back to these colours
 * for an id it doesn't know. A skin never changes a roll: the server rolls the same for
 * every die. Colours are #rrggbb: `face` is the top of the face, `faceBottom` the bottom of
 * its gradient, `rim` the darker edge, `pip` the dots and `one` the single dot of the 1.
 * A flag die (`flag`, an ISO country code) shows that country's flag on every face.
 */
export type DiceRarity = 'common' | 'rare' | 'epic' | 'legendary' | 'mythic' | 'ultimate';
export type DiceCollection = 'classic' | 'royal' | 'gemstone' | 'fantasy' | 'special' | 'exclusive' | 'flags';
/** The little show a die puts on when it lands (looks only). */
export type DiceEffect =
  | 'none' | 'gold' | 'sparkle' | 'flame' | 'snow' | 'cosmic' | 'dragon' | 'hearts' | 'lightning' | 'neon' | 'legend';

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
  rarity: DiceRarity;
  collection: DiceCollection;
  effect: DiceEffect;
  /** One line for the shop. */
  blurb: string;
  /** When it went on sale (YYYY-MM-DD): new for 30 days after. */
  added: string;
}

export const DEFAULT_DICE_SKIN = 'classic';

export const RARITIES: DiceRarity[] = ['common', 'rare', 'epic', 'legendary', 'mythic', 'ultimate'];
export const COLLECTIONS: DiceCollection[] = ['royal', 'gemstone', 'fantasy', 'special', 'exclusive', 'classic', 'flags'];
export const EFFECTS: DiceEffect[] = ['none', 'gold', 'sparkle', 'flame', 'snow', 'cosmic', 'dragon', 'hearts', 'lightning', 'neon', 'legend'];

const OLD = '2026-10-01';
const NEW = '2026-11-03';

type Look = Pick<DiceSkin, 'face' | 'faceBottom' | 'rim' | 'pip' | 'one'>;
const look = (face: string, faceBottom: string, rim: string, pip: string, one: string): Look => ({ face, faceBottom, rim, pip, one });
const flag = (id: string, name: string, code: string, l: Look): DiceSkin => ({
  id, name, price: 60, flag: code, ...l, rarity: 'common', collection: 'flags', effect: 'none', blurb: `${name}'s flag on every face.`, added: OLD,
});

export const DICE_SKINS: DiceSkin[] = [
  // Classic.
  { id: 'classic', name: 'Classic', price: 0, ...look('#ffffff', '#e4e1ee', '#c9c5d8', '#23202e', '#d62839'), rarity: 'common', collection: 'classic', effect: 'none', blurb: 'The clean white die everyone starts with.', added: OLD },
  { id: 'midnight', name: 'Midnight', price: 80, ...look('#3a3f66', '#10122a', '#05060f', '#ffd34d', '#ff5c6c'), rarity: 'common', collection: 'classic', effect: 'none', blurb: 'Deep night blue with warm gold pips.', added: OLD },
  { id: 'candy', name: 'Candy', price: 100, ...look('#ffd6ef', '#ff7ac2', '#b33c86', '#7a1f5c', '#ffffff'), rarity: 'common', collection: 'classic', effect: 'none', blurb: 'Sweet pink sugar glaze.', added: OLD },

  // Royal collection.
  { id: 'gold', name: 'Royal Gold', price: 450, ...look('#ffe58a', '#d99a00', '#8a5a00', '#5a2d00', '#c4142d'), rarity: 'legendary', collection: 'royal', effect: 'gold', blurb: 'Polished solid gold with royal engravings and a golden aura.', added: OLD },
  { id: 'emperor', name: 'Emperor', price: 500, ...look('#3a3328', '#0d0b08', '#d4a017', '#ffcf40', '#ffcf40'), rarity: 'legendary', collection: 'royal', effect: 'gold', blurb: 'Black gold with an imperial crown on every face.', added: NEW },
  { id: 'king_crown', name: 'King Crown', price: 300, ...look('#ffffff', '#e8e4dc', '#c9b98a', '#b8860b', '#1a4fc4'), rarity: 'epic', collection: 'royal', effect: 'sparkle', blurb: 'White gold, crowns and set gemstones.', added: NEW },
  { id: 'royal_diamond', name: 'Royal Diamond', price: 700, ...look('#f2fdff', '#bfeaff', '#d4a017', '#d4a017', '#d4a017'), rarity: 'mythic', collection: 'royal', effect: 'sparkle', blurb: 'Clear crystal that throws diamond light, with gold pips.', added: NEW },
  { id: 'black_royal', name: 'Black Royal', price: 280, ...look('#2a2730', '#08070a', '#d4a017', '#e0115f', '#e0115f'), rarity: 'epic', collection: 'royal', effect: 'gold', blurb: 'Obsidian with gold edges and ruby pips.', added: NEW },

  // Gemstone collection.
  { id: 'diamond', name: 'Diamond Crystal', price: 320, ...look('#f2fdff', '#86e1ff', '#2fb8ea', '#1e9bff', '#1e9bff'), rarity: 'epic', collection: 'gemstone', effect: 'sparkle', blurb: 'Clear crystal with glowing blue pips.', added: OLD },
  { id: 'ruby', name: 'Ruby Gem', price: 120, ...look('#ff6b78', '#c4142d', '#7a0b1c', '#ffd34d', '#ffe27a'), rarity: 'rare', collection: 'gemstone', effect: 'sparkle', blurb: 'Deep red gemstone with polished gold.', added: OLD },
  { id: 'sapphire', name: 'Sapphire', price: 120, ...look('#5fa8ff', '#1a4fc4', '#0d2a6e', '#e6ecf5', '#e6ecf5'), rarity: 'rare', collection: 'gemstone', effect: 'sparkle', blurb: 'Rich blue crystal with silver pips.', added: OLD },
  { id: 'emerald', name: 'Emerald', price: 120, ...look('#4be39a', '#0f8a4e', '#0a4f2d', '#ffd34d', '#ffe27a'), rarity: 'rare', collection: 'gemstone', effect: 'sparkle', blurb: 'Emerald green, trimmed with gold.', added: OLD },
  { id: 'amethyst', name: 'Amethyst', price: 140, ...look('#d6a6ff', '#7b2fd1', '#45137a', '#ffffff', '#ffe27a'), rarity: 'rare', collection: 'gemstone', effect: 'sparkle', blurb: 'Purple crystal with light dancing inside.', added: NEW },

  // Fantasy collection.
  { id: 'dragon', name: 'Dragon', price: 550, ...look('#3d3a40', '#121014', '#d4a017', '#ffcf40', '#ff4500'), rarity: 'legendary', collection: 'fantasy', effect: 'dragon', blurb: 'A golden dragon coils round dark steel.', added: NEW },
  { id: 'phoenix', name: 'Phoenix', price: 520, ...look('#ffb347', '#e0301e', '#7a1408', '#fff3c4', '#fff3c4'), rarity: 'legendary', collection: 'fantasy', effect: 'flame', blurb: 'Glowing fire feathers that never go out.', added: NEW },
  { id: 'ice_king', name: 'Ice King', price: 300, ...look('#eefbff', '#9fdcf5', '#5aa9cf', '#1f6f9e', '#1f6f9e'), rarity: 'epic', collection: 'fantasy', effect: 'snow', blurb: 'Frozen crystal with snowflake engravings.', added: NEW },
  { id: 'galaxy', name: 'Galaxy', price: 320, ...look('#3b2a7a', '#0a0620', '#6f4bd8', '#9ef0ff', '#ff7ad9'), rarity: 'epic', collection: 'fantasy', effect: 'cosmic', blurb: 'Stars and nebulae, with glowing pips.', added: NEW },
  { id: 'cosmic', name: 'Legendary Cosmic', price: 800, ...look('#5b2a9e', '#1a0838', '#ffcf40', '#ffe27a', '#ffe27a'), rarity: 'mythic', collection: 'fantasy', effect: 'cosmic', blurb: 'Purple and gold, alive with cosmic energy.', added: NEW },

  // Special collection.
  { id: 'neon', name: 'Neon Cyber', price: 150, ...look('#1d2350', '#070a24', '#39ffd6', '#39ffd6', '#ff3df2'), rarity: 'rare', collection: 'special', effect: 'neon', blurb: 'Glowing cyan and purple edges.', added: OLD },
  { id: 'fire', name: 'Fire', price: 260, ...look('#3a2a26', '#120806', '#ff6a00', '#ffb347', '#ffb347'), rarity: 'epic', collection: 'special', effect: 'flame', blurb: 'Volcanic rock with glowing lava cracks.', added: NEW },
  { id: 'love_heart', name: 'Love Heart', price: 160, ...look('#ffd6e8', '#ff7aa8', '#c2185b', '#ffffff', '#e91e63'), rarity: 'rare', collection: 'special', effect: 'hearts', blurb: 'Pink crystal full of hearts.', added: NEW },
  { id: 'skull_king', name: 'Skull King', price: 280, ...look('#4a4a50', '#151518', '#9aa0a8', '#e8e8e8', '#ff3b3b'), rarity: 'epic', collection: 'special', effect: 'none', blurb: 'Dark steel with skull engravings.', added: NEW },
  { id: 'lightning', name: 'Lightning', price: 260, ...look('#3b7bff', '#0b1e6e', '#9ee6ff', '#eaffff', '#ffe600'), rarity: 'epic', collection: 'special', effect: 'lightning', blurb: 'Electric blue with crackling bolts.', added: NEW },

  // Exclusive collection.
  { id: 'golden_tiger', name: 'Golden Tiger', price: 600, ...look('#ffd56b', '#c78100', '#5a3a00', '#2a1600', '#2a1600'), rarity: 'legendary', collection: 'exclusive', effect: 'gold', blurb: 'A golden tiger prowls every face.', added: NEW },
  { id: 'peacock', name: 'Royal Peacock', price: 350, ...look('#1fb5a6', '#0b4f7a', '#d4a017', '#ffcf40', '#ffcf40'), rarity: 'epic', collection: 'exclusive', effect: 'sparkle', blurb: 'Blue, green and gold feathers.', added: NEW },
  { id: 'pharaoh', name: 'Ancient Pharaoh', price: 600, ...look('#f0c35a', '#a8740a', '#1a4fc4', '#1a3a8a', '#1a3a8a'), rarity: 'legendary', collection: 'exclusive', effect: 'gold', blurb: 'Pharaoh gold and lapis, with ancient symbols.', added: NEW },
  { id: 'butterfly', name: 'Crystal Butterfly', price: 330, ...look('#f4f0ff', '#c9b8ff', '#9a7bd8', '#7a4fd8', '#ff7ad9'), rarity: 'epic', collection: 'exclusive', effect: 'sparkle', blurb: 'Rainbow crystal with butterfly wings.', added: NEW },
  { id: 'ludox_ultimate', name: 'LudoX Ultimate', price: 1500, ...look('#7b2fd1', '#2a0a5e', '#ffcf40', '#ffcf40', '#ffcf40'), rarity: 'ultimate', collection: 'exclusive', effect: 'legend', blurb: 'The LudoX signature die: crown, purple gems, gold.', added: NEW },

  // Country flags.
  flag('flag_bd', 'Bangladesh', 'BD', look('#0b8a5f', '#006a4e', '#00412f', '#ffffff', '#ffffff')),
  flag('flag_in', 'India', 'IN', look('#ffffff', '#f2f2f2', '#138808', '#ffffff', '#ffffff')),
  flag('flag_pk', 'Pakistan', 'PK', look('#0b6b3a', '#01411c', '#012a12', '#ffffff', '#ffffff')),
  flag('flag_np', 'Nepal', 'NP', look('#f04060', '#dc143c', '#003893', '#ffffff', '#ffffff')),
  flag('flag_lk', 'Sri Lanka', 'LK', look('#ffcf4a', '#ffbe29', '#8d153a', '#ffffff', '#ffffff')),
  flag('flag_ae', 'UAE', 'AE', look('#ffffff', '#e8e8e8', '#00732f', '#ffffff', '#ffffff')),
  flag('flag_sa', 'Saudi Arabia', 'SA', look('#1a8a4c', '#006c35', '#00431f', '#ffffff', '#ffffff')),
];

/** Shown first in the shop until an administrator picks others. */
export const DEFAULT_FEATURED = ['ludox_ultimate', 'cosmic', 'dragon', 'royal_diamond'];

export function diceSkin(id: string | null | undefined): DiceSkin | undefined {
  return DICE_SKINS.find((s) => s.id === id);
}
